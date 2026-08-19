package com.soniccore.core.audio.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import com.soniccore.core.audio.focus.AudioFocusManager
import com.soniccore.core.common.diagnostics.DiagnosticLog
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccore.core.dsp.DeEsser
import com.soniccore.core.dsp.EqualizerEngine
import com.soniccore.core.dsp.Fft
import com.soniccore.core.dsp.GainStage
import com.soniccore.core.dsp.LevelMeter
import com.soniccore.core.dsp.NoiseGate
import com.soniccore.core.dsp.SpectrumSmoother
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.eq.SpectrumFrame
import com.soniccore.core.model.profile.InputSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class MicLevelState(
    val peakDbfs: Float = -120f,
    val rmsDbfs: Float = -120f,
    val heldPeakDbfs: Float = -120f,
    val isClipping: Boolean = false,
    val gateOpen: Boolean = false,
    val isCapturing: Boolean = false,
    /** Set when the input device disappeared mid-capture, so the UI can explain it. */
    val inputLost: Boolean = false,
)

/**
 * Microphone capture + monitoring engine.
 *
 * Android exposes no public microphone *gain* setter, so input level is implemented
 * as a software [GainStage] over the captured buffer, followed by the gate,
 * de-esser and mic EQ. The [MicSource] choice is the real switch for platform
 * pre-processing (AGC/NS/AEC), which is why it is a first-class setting.
 */
@Singleton
class MicrophoneEngine @Inject constructor(
    private val context: Context,
    private val focusManager: AudioFocusManager,
    private val diagnostics: DiagnosticLog,
) {
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var captureJob: Job? = null
    private var monitorTrack: AudioTrack? = null

    private val gainStage = GainStage()
    private val noiseGate = NoiseGate()
    private val deEsser = DeEsser()
    private val levelMeter = LevelMeter()
    private val eqEngine = EqualizerEngine(SAMPLE_RATE, 1)
    private val fft = Fft(FFT_SIZE)
    private val smoother = SpectrumSmoother(SPECTRUM_BANDS)

    private val _levels = MutableStateFlow(MicLevelState())
    val levels: StateFlow<MicLevelState> = _levels.asStateFlow()

    private val _spectrum = MutableSharedFlow<SpectrumFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val spectrum: Flow<SpectrumFrame> = _spectrum.asSharedFlow()

    val hasRecordPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Platform capabilities, so the UI only offers what the device really has. */
    fun availableProcessing(): MicProcessingSupport = MicProcessingSupport(
        noiseSuppression = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
        echoCancellation = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
        automaticGainControl = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false),
        unprocessedSource = supportsUnprocessed(),
    )

    private fun supportsUnprocessed(): Boolean = runCatching {
        val audioManager = ContextCompat.getSystemService(context, AudioManager::class.java)
        audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, settings: InputSettings): Boolean {
        if (!hasRecordPermission) return false
        stop(scope)

        // Capture also needs focus: a monitoring session that talks over a phone call
        // is the same policy problem as playback. TRANSIENT_MAY_DUCK lets a music app
        // keep playing quietly while the user checks their mic.
        if (!focusManager.requestCaptureFocus()) {
            diagnostics.w(TAG, "audio focus denied — not starting capture")
            return false
        }

        val sourceId = platformSourceFor(settings.micSource)
        val sampleRate = settings.preferredSampleRate ?: SAMPLE_RATE
        val channelMask = if (settings.channelCount >= 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, ENCODING)
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, FFT_SIZE * 4)

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(sourceId)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        }.getOrNull() ?: return false

        audioRecord = record
        attachPlatformProcessing(record.audioSessionId, settings)

        gainStage.setGainDb(settings.gainDb)
        noiseGate.configure(
            enabled = settings.noiseGateEnabled,
            thresholdDb = settings.noiseGateThresholdDb,
            attackMs = settings.noiseGateAttackMs,
            releaseMs = settings.noiseGateReleaseMs,
            sampleRate = sampleRate,
        )
        deEsser.configure(settings.deEsserEnabled)
        eqEngine.configure(settings.micEq, sampleRate, settings.channelCount.coerceAtLeast(1))

        if (settings.sidetoneEnabled) startMonitor(sampleRate, settings)

        runCatching { record.startRecording() }.onFailure { return false }

        captureJob = scope.launch(Dispatchers.Default) {
            val channels = settings.channelCount.coerceAtLeast(1)
            val buffer = FloatArray(FFT_SIZE * channels)
            var spectrumBins = FloatArray(FFT_SIZE / 2)

            while (isActive) {
                val read = runCatching {
                    record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                }.getOrDefault(AudioRecord.ERROR)

                when {
                    // The input device disappeared (USB mic unplugged, BT headset
                    // dropped). Stop cleanly and report it instead of spinning.
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        diagnostics.w(TAG, "AudioRecord died (input route lost)")
                        _levels.value = _levels.value.copy(inputLost = true)
                        break
                    }

                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        diagnostics.w(TAG, "AudioRecord rejected read (invalid operation)")
                        break
                    }

                    read < 0 -> {
                        diagnostics.e(TAG, "AudioRecord read failed with code $read")
                        break
                    }

                    read == 0 -> break
                }

                val frames = read / channels

                gainStage.process(buffer, frames, channels)
                noiseGate.process(buffer, frames, channels)
                deEsser.process(buffer, frames, channels)
                eqEngine.processInterleaved(buffer, frames)
                levelMeter.process(buffer, frames, channels)

                _levels.value = MicLevelState(
                    peakDbfs = levelMeter.peakDbfs(),
                    rmsDbfs = levelMeter.rmsDbfs(),
                    heldPeakDbfs = levelMeter.heldPeakDbfs(),
                    isClipping = levelMeter.isClipping(),
                    gateOpen = noiseGate.currentGateGain() > 0.5f,
                    isCapturing = true,
                )

                spectrumBins = fft.magnitudeSpectrumDb(buffer, 0, spectrumBins)
                val bands = Fft.toLogBands(spectrumBins, sampleRate, FFT_SIZE, SPECTRUM_BANDS)
                _spectrum.tryEmit(
                    SpectrumFrame(
                        magnitudesDb = smoother.process(bands).copyOf(),
                        sampleRate = sampleRate,
                        timestampNanos = System.nanoTime(),
                    ),
                )

                if (settings.sidetoneEnabled) {
                    val level = settings.sidetoneLevel.coerceIn(0f, 1f)
                    val monitorBuffer = FloatArray(frames * channels) { buffer[it] * level }
                    runCatching {
                        monitorTrack?.write(monitorBuffer, 0, monitorBuffer.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun attachPlatformProcessing(sessionId: Int, settings: InputSettings) {
        if (settings.noiseSuppression != com.soniccore.core.model.audio.NoiseSuppressionMode.OFF) {
            runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId).apply { enabled = true }
                }
            }
        }
        if (settings.echoCancellation) {
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId).apply { enabled = true }
                }
            }
        }
        if (settings.autoGainControl) {
            runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(sessionId).apply { enabled = true }
                }
            }
        }
    }

    private fun startMonitor(sampleRate: Int, settings: InputSettings) {
        val channelMask = if (settings.channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, ENCODING)
        if (minBuffer <= 0) return

        monitorTrack = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
                .also { it.play() }
        }.getOrNull()
    }

    suspend fun stopAndJoin() {
        captureJob?.cancelAndJoin()
        releaseResources()
    }

    fun stop(scope: CoroutineScope) {
        captureJob?.cancel()
        captureJob = null
        releaseResources()
    }

    private fun releaseResources() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { automaticGainControl?.release() }
        noiseSuppressor = null
        echoCanceler = null
        automaticGainControl = null

        runCatching { monitorTrack?.stop() }
        runCatching { monitorTrack?.release() }
        monitorTrack = null

        // Hand focus back so other apps can resume.
        focusManager.abandon()

        levelMeter.reset()
        noiseGate.reset()
        smoother.reset()
        _levels.value = MicLevelState()
    }

    private fun platformSourceFor(source: MicSource): Int = when (source) {
        MicSource.MIC -> MediaRecorder.AudioSource.MIC
        MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.UNPROCESSED ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && supportsUnprocessed()) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        MicSource.VOICE_PERFORMANCE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.VOICE_PERFORMANCE
            } else {
                MediaRecorder.AudioSource.MIC
            }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT_SIZE = 1024
        const val SPECTRUM_BANDS = 48
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val TAG = "MicrophoneEngine"
    }
}

data class MicProcessingSupport(
    val noiseSuppression: Boolean,
    val echoCancellation: Boolean,
    val automaticGainControl: Boolean,
    val unprocessedSource: Boolean,
)
