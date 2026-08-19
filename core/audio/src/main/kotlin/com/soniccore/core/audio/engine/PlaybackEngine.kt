package com.soniccore.core.audio.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Context
import com.soniccore.core.audio.focus.AudioFocusManager
import com.soniccore.core.common.diagnostics.DiagnosticLog
import com.soniccore.core.dsp.ChannelProcessor
import com.soniccore.core.dsp.Crossfeed
import com.soniccore.core.dsp.Dynamics
import com.soniccore.core.dsp.EqualizerEngine
import com.soniccore.core.dsp.Fft
import com.soniccore.core.dsp.GainStage
import com.soniccore.core.dsp.LevelMeter
import com.soniccore.core.dsp.SpectrumSmoother
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.SpectrumFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import javax.inject.Inject
import javax.inject.Singleton

/** Test-signal shapes for verifying a device and auditioning EQ. */
enum class TestSignal(val displayName: String) {
    SINE_SWEEP("Frequency sweep"),
    PINK_NOISE("Pink noise"),
    WHITE_NOISE("White noise"),
    SINE_1K("1 kHz tone"),
    LEFT_RIGHT("Left / right check"),
    PHASE_CHECK("Phase check"),
    SILENCE("Silence"),
}

data class PlaybackEngineState(
    val isRunning: Boolean = false,
    val signal: TestSignal = TestSignal.SILENCE,
    val sampleRate: Int = 48_000,
    val bufferFrames: Int = 0,
    val peakDbfs: Float = -120f,
    val isClipping: Boolean = false,
    val underruns: Int = 0,
    val latencyEstimateMs: Float = 0f,
    /** Times the AudioTrack was rebuilt after the route died. */
    val recoveries: Int = 0,
    /** True while another app holds focus and we have attenuated ourselves. */
    val isDucked: Boolean = false,
)

/**
 * Low-latency playback path we fully own, used for test tones and for auditioning
 * the DSP chain against a specific device.
 *
 * `PERFORMANCE_MODE_LOW_LATENCY` routes through AAudio under the hood, and the
 * buffer is sized from the device's own `PROPERTY_OUTPUT_FRAMES_PER_BUFFER` — a
 * hardcoded buffer is the classic cause of glitching on high-end DACs.
 */
@Singleton
class PlaybackEngine @Inject constructor(
    private val context: Context,
    private val focusManager: AudioFocusManager,
    private val diagnostics: DiagnosticLog,
) {
    private var audioTrack: AudioTrack? = null
    private var renderJob: Job? = null

    private val equalizer = EqualizerEngine()
    private val crossfeed = Crossfeed()
    private val dynamics = Dynamics()
    private val gainStage = GainStage()
    private val levelMeter = LevelMeter()
    private val fft = Fft(FFT_SIZE)
    private val smoother = SpectrumSmoother(SPECTRUM_BANDS)

    private var effects: EffectsSettings = EffectsSettings()
    private var sweepPhase = 0.0
    private var sweepFrequency = 20.0
    private var pinkState = DoubleArray(7)

    private val _state = MutableStateFlow(PlaybackEngineState())
    val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    private val _spectrum = MutableSharedFlow<SpectrumFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val spectrum: Flow<SpectrumFrame> = _spectrum.asSharedFlow()

    /** Native output config — always ask the device instead of assuming. */
    fun nativeSampleRate(): Int = runCatching {
        val am = ContextCompat.getSystemService(context, AudioManager::class.java)
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }.getOrNull() ?: DEFAULT_SAMPLE_RATE

    fun nativeFramesPerBuffer(): Int = runCatching {
        val am = ContextCompat.getSystemService(context, AudioManager::class.java)
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
    }.getOrNull() ?: DEFAULT_FRAMES_PER_BUFFER

    fun configureDsp(eq: EqSettings, effects: EffectsSettings, sampleRate: Int = nativeSampleRate()) {
        this.effects = effects
        equalizer.configure(eq, sampleRate, CHANNELS)
        crossfeed.configure(effects.crossfeed, sampleRate)
        dynamics.configure(effects.dynamics, sampleRate)
        gainStage.setGainDb(0f)
    }

    fun start(scope: CoroutineScope, signal: TestSignal, preferredDeviceId: Int? = null): Boolean {
        stop()
        if (signal == TestSignal.SILENCE) return false

        val sampleRate = nativeSampleRate()
        val framesPerBuffer = nativeFramesPerBuffer()

        // Never produce audio without focus: playing over a phone call or an alarm is
        // both hostile and a Play policy violation.
        if (!focusManager.requestPlaybackFocus()) {
            diagnostics.w(TAG, "audio focus denied — not starting playback")
            return false
        }
        focusManager.onPause = { stop() }
        focusManager.onStop = { stop() }

        val track = buildTrack(sampleRate, framesPerBuffer) ?: return false

        audioTrack = track
        sweepFrequency = 20.0
        sweepPhase = 0.0

        runCatching { track.play() }.onFailure {
            diagnostics.e(TAG, "AudioTrack.play() failed", it)
            return false
        }

        _state.value = PlaybackEngineState(
            isRunning = true,
            signal = signal,
            sampleRate = sampleRate,
            bufferFrames = framesPerBuffer,
            latencyEstimateMs = framesPerBuffer * 2f * 1000f / sampleRate,
        )

        renderJob = scope.launch(Dispatchers.Default) {
            val buffer = FloatArray(framesPerBuffer * CHANNELS)
            var spectrumBins = FloatArray(FFT_SIZE / 2)
            var underruns = 0

            while (isActive) {
                generate(buffer, framesPerBuffer, signal, sampleRate)

                equalizer.processInterleaved(buffer, framesPerBuffer)
                crossfeed.process(buffer, framesPerBuffer)
                ChannelProcessor.applyChannelMode(buffer, framesPerBuffer, effects.channelMode)
                ChannelProcessor.applyStereoWidth(buffer, framesPerBuffer, effects.stereoWidth)
                ChannelProcessor.applyBalance(buffer, framesPerBuffer, effects.balance)
                ChannelProcessor.invertPhase(
                    buffer,
                    framesPerBuffer,
                    effects.phaseInvertLeft,
                    effects.phaseInvertRight,
                )
                dynamics.process(buffer, framesPerBuffer, CHANNELS)
                gainStage.process(buffer, framesPerBuffer, CHANNELS)

                // Apply the focus duck AFTER the DSP chain so ducking never changes
                // how the effects behave — only how loud the result is.
                val duck = focusManager.gainMultiplier.value
                if (duck != 1f) {
                    for (i in 0 until framesPerBuffer * CHANNELS) buffer[i] *= duck
                }

                levelMeter.process(buffer, framesPerBuffer, CHANNELS)

                if (buffer.size >= FFT_SIZE) {
                    spectrumBins = fft.magnitudeSpectrumDb(buffer, 0, spectrumBins)
                    val bands = Fft.toLogBands(spectrumBins, sampleRate, FFT_SIZE, SPECTRUM_BANDS)
                    _spectrum.tryEmit(
                        SpectrumFrame(
                            magnitudesDb = smoother.process(bands).copyOf(),
                            sampleRate = sampleRate,
                            timestampNanos = System.nanoTime(),
                        ),
                    )
                }

                val written = runCatching {
                    track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(AudioTrack.ERROR)

                when {
                    // The output device vanished mid-write (USB DAC unplugged, BT
                    // dropped). The AudioTrack is unusable; recreate it against the
                    // new default route instead of spinning on a dead object.
                    written == AudioTrack.ERROR_DEAD_OBJECT -> {
                        diagnostics.w(TAG, "AudioTrack died (route lost) — recreating")
                        _state.value = _state.value.copy(recoveries = _state.value.recoveries + 1)
                        if (!recreateTrack()) {
                            diagnostics.e(TAG, "could not recover AudioTrack; stopping")
                            break
                        }
                        continue
                    }

                    written == AudioTrack.ERROR_INVALID_OPERATION -> {
                        diagnostics.w(TAG, "AudioTrack rejected write (invalid operation)")
                        break
                    }

                    written < 0 -> {
                        diagnostics.e(TAG, "AudioTrack write failed with code $written")
                        break
                    }

                    written < buffer.size -> underruns++
                }

                _state.value = _state.value.copy(
                    peakDbfs = levelMeter.peakDbfs(),
                    isClipping = levelMeter.isClipping(),
                    underruns = underruns,
                )
            }
        }
        return true
    }

    /**
     * Rebuild the AudioTrack after ERROR_DEAD_OBJECT.
     *
     * Returns false when the device is genuinely gone, so the caller stops cleanly
     * rather than looping on a dead handle.
     */
    private fun recreateTrack(): Boolean {
        val previous = audioTrack
        runCatching { previous?.release() }
        audioTrack = null

        val current = _state.value
        val track = buildTrack(current.sampleRate, current.bufferFrames) ?: return false
        return runCatching {
            track.play()
            audioTrack = track
            true
        }.getOrElse {
            diagnostics.e(TAG, "recreated AudioTrack refused to play", it)
            runCatching { track.release() }
            false
        }
    }

    /**
     * Build an AudioTrack for the current default route.
     *
     * Extracted so [recreateTrack] can rebuild it after ERROR_DEAD_OBJECT without
     * duplicating the configuration.
     */
    private fun buildTrack(sampleRate: Int, framesPerBuffer: Int): AudioTrack? {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, ENCODING)
        if (minBuffer <= 0) {
            diagnostics.e(TAG, "getMinBufferSize returned $minBuffer for ${sampleRate}Hz")
            return null
        }

        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, framesPerBuffer * CHANNELS * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
        }.getOrElse {
            diagnostics.e(TAG, "AudioTrack construction failed at ${sampleRate}Hz", it)
            null
        }
    }

    private fun generate(buffer: FloatArray, frames: Int, signal: TestSignal, sampleRate: Int) {
        when (signal) {
            TestSignal.SINE_1K -> {
                val step = 2.0 * PI * 1000.0 / sampleRate
                for (f in 0 until frames) {
                    val v = (sin(sweepPhase) * 0.5).toFloat()
                    buffer[f * 2] = v
                    buffer[f * 2 + 1] = v
                    sweepPhase += step
                    if (sweepPhase > 2 * PI) sweepPhase -= 2 * PI
                }
            }

            TestSignal.SINE_SWEEP -> {
                // Logarithmic sweep 20 Hz -> 20 kHz over ~12 s.
                val octavesPerSecond = 10.0 / 12.0
                for (f in 0 until frames) {
                    val v = (sin(sweepPhase) * 0.4).toFloat()
                    buffer[f * 2] = v
                    buffer[f * 2 + 1] = v
                    sweepPhase += 2.0 * PI * sweepFrequency / sampleRate
                    if (sweepPhase > 2 * PI) sweepPhase -= 2 * PI
                    sweepFrequency *= Math.pow(2.0, octavesPerSecond / sampleRate)
                    if (sweepFrequency > 20_000.0) sweepFrequency = 20.0
                }
            }

            TestSignal.WHITE_NOISE -> {
                for (f in 0 until frames) {
                    val v = ((Math.random() * 2 - 1) * 0.3).toFloat()
                    buffer[f * 2] = v
                    buffer[f * 2 + 1] = v
                }
            }

            TestSignal.PINK_NOISE -> {
                // Paul Kellet's economical pink-noise filter.
                for (f in 0 until frames) {
                    val white = Math.random() * 2 - 1
                    pinkState[0] = 0.99886 * pinkState[0] + white * 0.0555179
                    pinkState[1] = 0.99332 * pinkState[1] + white * 0.0750759
                    pinkState[2] = 0.96900 * pinkState[2] + white * 0.1538520
                    pinkState[3] = 0.86650 * pinkState[3] + white * 0.3104856
                    pinkState[4] = 0.55000 * pinkState[4] + white * 0.5329522
                    pinkState[5] = -0.7616 * pinkState[5] - white * 0.0168980
                    val pink = (pinkState.sum() + white * 0.5362) * 0.11
                    pinkState[6] = white * 0.115926
                    val v = pink.toFloat().coerceIn(-1f, 1f) * 0.6f
                    buffer[f * 2] = v
                    buffer[f * 2 + 1] = v
                }
            }

            TestSignal.LEFT_RIGHT -> {
                // 2 s left, 2 s right, alternating.
                val periodFrames = sampleRate * 2
                for (f in 0 until frames) {
                    val v = (sin(sweepPhase) * 0.4).toFloat()
                    val leftActive = ((sweepPhase / (2 * PI)).toInt() / (periodFrames / 100)) % 2 == 0
                    buffer[f * 2] = if (leftActive) v else 0f
                    buffer[f * 2 + 1] = if (leftActive) 0f else v
                    sweepPhase += 2.0 * PI * 440.0 / sampleRate
                }
            }

            TestSignal.PHASE_CHECK -> {
                val step = 2.0 * PI * 250.0 / sampleRate
                for (f in 0 until frames) {
                    val v = (sin(sweepPhase) * 0.4).toFloat()
                    buffer[f * 2] = v
                    buffer[f * 2 + 1] = -v
                    sweepPhase += step
                    if (sweepPhase > 2 * PI) sweepPhase -= 2 * PI
                }
            }

            TestSignal.SILENCE -> buffer.fill(0f)
        }
    }

    fun stop() {
        renderJob?.cancel()
        renderJob = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        levelMeter.reset()
        smoother.reset()
        // Always hand focus back: holding it after stopping prevents other apps from
        // resuming and is the most common audio-focus bug.
        focusManager.onPause = null
        focusManager.onStop = null
        focusManager.abandon()
        _state.value = PlaybackEngineState()
    }

    /** Session id so platform effects can attach to our own output. */
    fun audioSessionId(): Int? = audioTrack?.audioSessionId

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_FRAMES_PER_BUFFER = 256
        const val FFT_SIZE = 1024
        const val SPECTRUM_BANDS = 48
        private const val CHANNELS = 2
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val TAG = "PlaybackEngine"
    }
}
