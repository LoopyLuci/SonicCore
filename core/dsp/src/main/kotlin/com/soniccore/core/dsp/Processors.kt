package com.soniccore.core.dsp

import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.effects.CrossfeedSettings
import com.soniccore.core.model.effects.DynamicsSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Software gain stage with click-free ramping. Also provides the fine volume steps
 *  the platform stream index cannot express. */
class GainStage(private var sampleRate: Int = 48_000) {
    private var currentLinear = 1.0
    private var targetLinear = 1.0
    private var rampCoefficient = 0.001

    fun setGainDb(db: Float, rampMs: Float = 20f) {
        targetLinear = 10.0.pow(db.coerceIn(-90f, 24f) / 20.0)
        val rampSamples = (rampMs / 1000f * sampleRate).coerceAtLeast(1f)
        rampCoefficient = 1.0 - exp(-1.0 / rampSamples)
    }

    fun setLinear(value: Float) {
        targetLinear = value.coerceIn(0f, 8f).toDouble()
    }

    fun snap() { currentLinear = targetLinear }

    fun process(buffer: FloatArray, frameCount: Int, channelCount: Int) {
        for (frame in 0 until frameCount) {
            currentLinear += (targetLinear - currentLinear) * rampCoefficient
            for (c in 0 until channelCount) {
                val i = frame * channelCount + c
                if (i < buffer.size) buffer[i] = (buffer[i] * currentLinear).toFloat()
            }
        }
    }
}

/** Balance, mono downmix, channel swap, stereo widening. */
object ChannelProcessor {

    fun applyChannelMode(buffer: FloatArray, frameCount: Int, mode: ChannelMode) {
        if (buffer.size < frameCount * 2) return
        for (frame in 0 until frameCount) {
            val l = frame * 2
            val r = l + 1
            when (mode) {
                ChannelMode.STEREO, ChannelMode.VIRTUAL_51, ChannelMode.VIRTUAL_71 -> Unit
                ChannelMode.MONO -> {
                    val mixed = (buffer[l] + buffer[r]) * 0.5f
                    buffer[l] = mixed
                    buffer[r] = mixed
                }
                ChannelMode.SWAP_LR -> {
                    val tmp = buffer[l]
                    buffer[l] = buffer[r]
                    buffer[r] = tmp
                }
                ChannelMode.LEFT_ONLY -> buffer[r] = buffer[l]
                ChannelMode.RIGHT_ONLY -> buffer[l] = buffer[r]
            }
        }
    }

    /** [balance] -1 = full left, 0 = centre, +1 = full right. Constant-power law. */
    fun applyBalance(buffer: FloatArray, frameCount: Int, balance: Float) {
        val b = balance.coerceIn(-1f, 1f)
        if (b == 0f || buffer.size < frameCount * 2) return
        val angle = (b + 1f) * 0.25 * Math.PI
        val leftGain = kotlin.math.cos(angle).toFloat() * sqrt(2f)
        val rightGain = kotlin.math.sin(angle).toFloat() * sqrt(2f)
        for (frame in 0 until frameCount) {
            buffer[frame * 2] *= leftGain
            buffer[frame * 2 + 1] *= rightGain
        }
    }

    /** Mid/side stereo width. 1.0 = unchanged, 0 = mono, >1 = wider. */
    fun applyStereoWidth(buffer: FloatArray, frameCount: Int, width: Float) {
        val w = width.coerceIn(0f, 2f)
        if (w == 1f || buffer.size < frameCount * 2) return
        for (frame in 0 until frameCount) {
            val l = frame * 2
            val r = l + 1
            val mid = (buffer[l] + buffer[r]) * 0.5f
            val side = (buffer[l] - buffer[r]) * 0.5f * w
            buffer[l] = (mid + side).coerceIn(-1f, 1f)
            buffer[r] = (mid - side).coerceIn(-1f, 1f)
        }
    }

    fun invertPhase(buffer: FloatArray, frameCount: Int, left: Boolean, right: Boolean) {
        if (!left && !right) return
        for (frame in 0 until frameCount) {
            if (left) buffer[frame * 2] = -buffer[frame * 2]
            if (right) buffer[frame * 2 + 1] = -buffer[frame * 2 + 1]
        }
    }
}

/**
 * Headphone crossfeed: bleeds a low-passed, slightly delayed copy of each channel
 * into the other to reduce the unnatural hard separation of headphone listening.
 */
class Crossfeed(private var sampleRate: Int = 48_000) {

    private val leftLowPass = Biquad()
    private val rightLowPass = Biquad()
    private var delayBufferL = FloatArray(MAX_DELAY_SAMPLES)
    private var delayBufferR = FloatArray(MAX_DELAY_SAMPLES)
    private var writeIndex = 0
    private var delaySamples = 14
    private var amount = 0f
    private var enabled = false

    fun configure(settings: CrossfeedSettings, sampleRate: Int = this.sampleRate) {
        this.sampleRate = sampleRate
        enabled = settings.enabled
        amount = settings.amount.coerceIn(0f, 1f)
        delaySamples = ((settings.delayMicros / 1_000_000f) * sampleRate)
            .toInt().coerceIn(0, MAX_DELAY_SAMPLES - 1)
        leftLowPass.configure(com.soniccore.core.model.eq.FilterType.LOW_PASS, settings.cutoffHz.toDouble(), sampleRate, 0.707, 0.0)
        rightLowPass.configure(com.soniccore.core.model.eq.FilterType.LOW_PASS, settings.cutoffHz.toDouble(), sampleRate, 0.707, 0.0)
    }

    fun process(buffer: FloatArray, frameCount: Int) {
        if (!enabled || amount <= 0f || buffer.size < frameCount * 2) return
        for (frame in 0 until frameCount) {
            val li = frame * 2
            val ri = li + 1
            val left = buffer[li]
            val right = buffer[ri]

            delayBufferL[writeIndex] = left
            delayBufferR[writeIndex] = right
            val readIndex = (writeIndex - delaySamples + MAX_DELAY_SAMPLES) % MAX_DELAY_SAMPLES

            val bleedToRight = leftLowPass.process(delayBufferL[readIndex].toDouble()).toFloat()
            val bleedToLeft = rightLowPass.process(delayBufferR[readIndex].toDouble()).toFloat()

            val norm = 1f / (1f + amount)
            buffer[li] = ((left + bleedToLeft * amount) * norm).coerceIn(-1f, 1f)
            buffer[ri] = ((right + bleedToRight * amount) * norm).coerceIn(-1f, 1f)

            writeIndex = (writeIndex + 1) % MAX_DELAY_SAMPLES
        }
    }

    fun reset() {
        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        writeIndex = 0
        leftLowPass.reset()
        rightLowPass.reset()
    }

    companion object { private const val MAX_DELAY_SAMPLES = 256 }
}

/**
 * Feed-forward compressor with soft knee, plus a hard limiter for the final stage.
 * Detector runs on the max of both channels so the stereo image is preserved.
 */
class Dynamics(private var sampleRate: Int = 48_000) {

    private var envelopeDb = -120.0
    private var attackCoefficient = 0.0
    private var releaseCoefficient = 0.0
    private var settings = DynamicsSettings()

    private var limiterEnvelope = 1.0

    fun configure(settings: DynamicsSettings, sampleRate: Int = this.sampleRate) {
        this.settings = settings
        this.sampleRate = sampleRate
        attackCoefficient = timeToCoefficient(settings.attackMs)
        releaseCoefficient = timeToCoefficient(settings.releaseMs)
    }

    private fun timeToCoefficient(ms: Float): Double {
        val samples = (ms / 1000.0 * sampleRate).coerceAtLeast(1.0)
        return 1.0 - exp(-1.0 / samples)
    }

    fun process(buffer: FloatArray, frameCount: Int, channelCount: Int) {
        val night = settings.nightMode
        val effectiveThreshold = if (night) settings.thresholdDb - 12f else settings.thresholdDb
        val effectiveRatio = if (night) maxOf(settings.ratio, 4f) else settings.ratio

        if (settings.compressorEnabled || night) {
            val makeup = 10.0.pow(settings.makeupGainDb / 20.0)
            for (frame in 0 until frameCount) {
                var peak = 0f
                for (c in 0 until channelCount) {
                    val i = frame * channelCount + c
                    if (i < buffer.size) peak = maxOf(peak, abs(buffer[i]))
                }
                val inputDb = 20.0 * log10(peak.coerceAtLeast(1e-7f).toDouble())
                val coefficient = if (inputDb > envelopeDb) attackCoefficient else releaseCoefficient
                envelopeDb += (inputDb - envelopeDb) * coefficient

                val overshoot = envelopeDb - effectiveThreshold
                val gainReductionDb = when {
                    overshoot <= -settings.kneeDb / 2 -> 0.0
                    overshoot >= settings.kneeDb / 2 -> overshoot * (1.0 - 1.0 / effectiveRatio)
                    else -> {
                        val kneeRange = overshoot + settings.kneeDb / 2
                        (1.0 - 1.0 / effectiveRatio) * kneeRange * kneeRange / (2.0 * settings.kneeDb)
                    }
                }
                val gain = 10.0.pow(-gainReductionDb / 20.0) * makeup

                for (c in 0 until channelCount) {
                    val i = frame * channelCount + c
                    if (i < buffer.size) buffer[i] = (buffer[i] * gain).toFloat()
                }
            }
        }

        if (settings.limiterEnabled) applyLimiter(buffer, frameCount, channelCount)
    }

    private fun applyLimiter(buffer: FloatArray, frameCount: Int, channelCount: Int) {
        val ceiling = 10.0.pow(settings.limiterCeilingDb / 20.0)
        val release = timeToCoefficient(50f)
        for (frame in 0 until frameCount) {
            var peak = 0.0
            for (c in 0 until channelCount) {
                val i = frame * channelCount + c
                if (i < buffer.size) peak = maxOf(peak, abs(buffer[i]).toDouble())
            }
            val required = if (peak > ceiling) ceiling / peak else 1.0
            limiterEnvelope = if (required < limiterEnvelope) required
            else limiterEnvelope + (1.0 - limiterEnvelope) * release

            for (c in 0 until channelCount) {
                val i = frame * channelCount + c
                if (i < buffer.size) {
                    buffer[i] = (buffer[i] * limiterEnvelope).toFloat().coerceIn(-1f, 1f)
                }
            }
        }
    }

    fun reset() {
        envelopeDb = -120.0
        limiterEnvelope = 1.0
    }
}

/** Downward expander / noise gate for the microphone chain. */
class NoiseGate(private var sampleRate: Int = 48_000) {

    private var envelope = 0.0
    private var gateGain = 0.0
    private var thresholdLinear = 0.003
    private var attackCoefficient = 0.01
    private var releaseCoefficient = 0.001
    private var enabled = false

    fun configure(enabled: Boolean, thresholdDb: Float, attackMs: Float, releaseMs: Float, sampleRate: Int = this.sampleRate) {
        this.enabled = enabled
        this.sampleRate = sampleRate
        thresholdLinear = 10.0.pow(thresholdDb.coerceIn(-90f, 0f) / 20.0)
        attackCoefficient = 1.0 - exp(-1.0 / (attackMs / 1000.0 * sampleRate).coerceAtLeast(1.0))
        releaseCoefficient = 1.0 - exp(-1.0 / (releaseMs / 1000.0 * sampleRate).coerceAtLeast(1.0))
    }

    fun process(buffer: FloatArray, frameCount: Int, channelCount: Int = 1) {
        if (!enabled) return
        for (frame in 0 until frameCount) {
            var peak = 0.0
            for (c in 0 until channelCount) {
                val i = frame * channelCount + c
                if (i < buffer.size) peak = maxOf(peak, abs(buffer[i]).toDouble())
            }
            envelope += (peak - envelope) * if (peak > envelope) 0.3 else 0.02
            val target = if (envelope > thresholdLinear) 1.0 else 0.0
            val coefficient = if (target > gateGain) attackCoefficient else releaseCoefficient
            gateGain += (target - gateGain) * coefficient

            for (c in 0 until channelCount) {
                val i = frame * channelCount + c
                if (i < buffer.size) buffer[i] = (buffer[i] * gateGain).toFloat()
            }
        }
    }

    fun currentGateGain(): Float = gateGain.toFloat()

    fun reset() { envelope = 0.0; gateGain = 0.0 }
}

/** De-esser: band-limited compression targeting sibilance (5–9 kHz). */
class DeEsser(sampleRate: Int = 48_000) {
    private val detector = Biquad().apply {
        configure(com.soniccore.core.model.eq.FilterType.BAND_PASS, 7000.0, sampleRate, 1.2, 0.0)
    }
    private val reducer = Biquad()
    private var enabled = false
    private var envelope = 0.0
    private val sr = sampleRate

    fun configure(enabled: Boolean, thresholdDb: Float = -28f) {
        this.enabled = enabled
        threshold = 10.0.pow(thresholdDb / 20.0)
    }

    private var threshold = 0.04

    fun process(buffer: FloatArray, frameCount: Int, channelCount: Int = 1) {
        if (!enabled) return
        for (frame in 0 until frameCount) {
            val i = frame * channelCount
            if (i >= buffer.size) break
            val sibilance = abs(detector.process(buffer[i].toDouble()))
            envelope += (sibilance - envelope) * if (sibilance > envelope) 0.4 else 0.05
            if (envelope > threshold) {
                val reduction = (threshold / envelope).coerceIn(0.3, 1.0)
                reducer.configure(com.soniccore.core.model.eq.FilterType.HIGH_SHELF, 6500.0, sr, 0.7, 20.0 * log10(reduction))
                for (c in 0 until channelCount) {
                    val idx = frame * channelCount + c
                    if (idx < buffer.size) buffer[idx] = reducer.process(buffer[idx].toDouble()).toFloat()
                }
            }
        }
    }
}

/** Peak / RMS metering with hold, for level meters and clip indicators. */
class LevelMeter(private val sampleRate: Int = 48_000) {
    private var peak = 0f
    private var rmsAccumulator = 0.0
    private var sampleCount = 0
    private var heldPeak = 0f
    private var holdCounter = 0
    private val holdFrames = sampleRate / 2

    fun process(buffer: FloatArray, frameCount: Int, channelCount: Int) {
        var framePeak = 0f
        var sum = 0.0
        val total = frameCount * channelCount
        for (i in 0 until minOf(total, buffer.size)) {
            val v = buffer[i]
            val magnitude = abs(v)
            if (magnitude > framePeak) framePeak = magnitude
            sum += v.toDouble() * v
        }
        peak = framePeak
        rmsAccumulator = sum / total.coerceAtLeast(1)
        sampleCount = total

        if (framePeak >= heldPeak) {
            heldPeak = framePeak
            holdCounter = holdFrames
        } else if (holdCounter > 0) {
            holdCounter -= frameCount
        } else {
            heldPeak *= 0.98f
        }
    }

    fun peakDbfs(): Float = toDb(peak)
    fun rmsDbfs(): Float = toDb(sqrt(rmsAccumulator).toFloat())
    fun heldPeakDbfs(): Float = toDb(heldPeak)
    fun isClipping(): Boolean = peak >= 0.999f

    private fun toDb(value: Float): Float =
        (20.0 * log10(value.coerceAtLeast(1e-7f).toDouble())).toFloat()

    fun reset() { peak = 0f; heldPeak = 0f; rmsAccumulator = 0.0; holdCounter = 0 }
}
