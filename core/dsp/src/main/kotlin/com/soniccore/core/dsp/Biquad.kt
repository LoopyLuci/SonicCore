package com.soniccore.core.dsp

import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single-channel biquad filter, RBJ audio-EQ-cookbook coefficients,
 * direct form 1. One instance is required **per channel per band** — sharing
 * state across channels collapses the stereo image.
 *
 * All coefficient inputs are clamped so a bad UI value can never inject NaN
 * into the sample path (NaN in a recursive filter is permanent).
 */
class Biquad {

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    var isBypassed: Boolean = false
        private set

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun setBypassed(bypassed: Boolean) {
        isBypassed = bypassed
        if (bypassed) reset()
    }

    fun configure(band: EqBand, sampleRate: Int) {
        val safe = band.sanitized(sampleRate)
        configure(safe.type, safe.frequencyHz.toDouble(), sampleRate, safe.q.toDouble(), safe.gainDb.toDouble())
        isBypassed = !band.enabled
    }

    fun configure(
        type: FilterType,
        frequencyHz: Double,
        sampleRate: Int,
        q: Double,
        gainDb: Double,
    ) {
        val nyquist = sampleRate / 2.0
        val freq = frequencyHz.coerceIn(MIN_FREQ, nyquist * MAX_FREQ_RATIO)
        val qq = q.coerceIn(MIN_Q, MAX_Q)
        val gain = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        val amp = 10.0.pow(gain / 40.0)
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * qq)

        val nb0: Double; val nb1: Double; val nb2: Double
        val na0: Double; val na1: Double; val na2: Double

        when (type) {
            FilterType.PEAK -> {
                nb0 = 1.0 + alpha * amp
                nb1 = -2.0 * cosW0
                nb2 = 1.0 - alpha * amp
                na0 = 1.0 + alpha / amp
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha / amp
            }
            FilterType.LOW_SHELF -> {
                val beta = 2.0 * sqrt(amp) * alpha
                nb0 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 + beta)
                nb1 = 2.0 * amp * ((amp - 1.0) - (amp + 1.0) * cosW0)
                nb2 = amp * ((amp + 1.0) - (amp - 1.0) * cosW0 - beta)
                na0 = (amp + 1.0) + (amp - 1.0) * cosW0 + beta
                na1 = -2.0 * ((amp - 1.0) + (amp + 1.0) * cosW0)
                na2 = (amp + 1.0) + (amp - 1.0) * cosW0 - beta
            }
            FilterType.HIGH_SHELF -> {
                val beta = 2.0 * sqrt(amp) * alpha
                nb0 = amp * ((amp + 1.0) + (amp - 1.0) * cosW0 + beta)
                nb1 = -2.0 * amp * ((amp - 1.0) + (amp + 1.0) * cosW0)
                nb2 = amp * ((amp + 1.0) + (amp - 1.0) * cosW0 - beta)
                na0 = (amp + 1.0) - (amp - 1.0) * cosW0 + beta
                na1 = 2.0 * ((amp - 1.0) - (amp + 1.0) * cosW0)
                na2 = (amp + 1.0) - (amp - 1.0) * cosW0 - beta
            }
            FilterType.LOW_PASS -> {
                nb0 = (1.0 - cosW0) / 2.0
                nb1 = 1.0 - cosW0
                nb2 = (1.0 - cosW0) / 2.0
                na0 = 1.0 + alpha
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha
            }
            FilterType.HIGH_PASS -> {
                nb0 = (1.0 + cosW0) / 2.0
                nb1 = -(1.0 + cosW0)
                nb2 = (1.0 + cosW0) / 2.0
                na0 = 1.0 + alpha
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha
            }
            FilterType.BAND_PASS -> {
                nb0 = alpha
                nb1 = 0.0
                nb2 = -alpha
                na0 = 1.0 + alpha
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha
            }
            FilterType.NOTCH -> {
                nb0 = 1.0
                nb1 = -2.0 * cosW0
                nb2 = 1.0
                na0 = 1.0 + alpha
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha
            }
            FilterType.ALL_PASS -> {
                nb0 = 1.0 - alpha
                nb1 = -2.0 * cosW0
                nb2 = 1.0 + alpha
                na0 = 1.0 + alpha
                na1 = -2.0 * cosW0
                na2 = 1.0 - alpha
            }
        }

        if (na0 == 0.0 || na0.isNaN()) return

        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0

        // Explicit checks, not listOf(...).any { }: updateCoefficients() runs whenever
        // the user drags an EQ band, and allocating a List per call churns the
        // allocator on the audio thread.
        if (b0.isNaN() || b0.isInfinite() ||
            b1.isNaN() || b1.isInfinite() ||
            b2.isNaN() || b2.isInfinite() ||
            a1.isNaN() || a1.isInfinite() ||
            a2.isNaN() || a2.isInfinite()
        ) {
            setIdentity()
        }
    }

    private fun setIdentity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
        reset()
    }

    /** Process one sample. Hot path — no allocation, no branching beyond bypass. */
    fun process(input: Double): Double {
        if (isBypassed) return input
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    /** Process a block in place. */
    fun processBlock(buffer: FloatArray, offset: Int = 0, length: Int = buffer.size - offset) {
        if (isBypassed) return
        var i = offset
        val end = offset + length
        while (i < end) {
            buffer[i] = process(buffer[i].toDouble()).toFloat()
            i++
        }
    }

    /**
     * Magnitude response in dB at [frequencyHz] — evaluates |H(e^jw)| analytically
     * so the UI can draw the curve without any audio flowing.
     */
    fun magnitudeDbAt(frequencyHz: Double, sampleRate: Int): Double {
        if (isBypassed) return 0.0
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)
        val sinW = sin(w)
        val sin2W = sin(2.0 * w)

        val numeratorReal = b0 + b1 * cosW + b2 * cos2W
        val numeratorImag = -(b1 * sinW + b2 * sin2W)
        val denominatorReal = 1.0 + a1 * cosW + a2 * cos2W
        val denominatorImag = -(a1 * sinW + a2 * sin2W)

        val numerator = hypot(numeratorReal, numeratorImag)
        val denominator = hypot(denominatorReal, denominatorImag).coerceAtLeast(EPSILON)
        return 20.0 * log10((numerator / denominator).coerceAtLeast(EPSILON))
    }

    companion object {
        private const val MIN_FREQ = 5.0
        private const val MAX_FREQ_RATIO = 0.98
        private const val MIN_Q = 0.05
        private const val MAX_Q = 40.0
        private const val MIN_GAIN_DB = -40.0
        private const val MAX_GAIN_DB = 40.0
        private const val EPSILON = 1e-12
    }
}
