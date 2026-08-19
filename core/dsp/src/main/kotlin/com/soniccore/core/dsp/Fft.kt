package com.soniccore.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * Iterative in-place radix-2 Cooley–Tukey FFT.
 *
 * Twiddle factors are precomputed and all scratch buffers are pre-sized: nothing
 * allocates per frame, so this is safe to call from an audio callback.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val half = size / 2
    private val cosTable = DoubleArray(half) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(half) { sin(-2.0 * PI * it / size) }

    /** Hann window with coherent-gain correction folded in. */
    private val window = DoubleArray(size) { 0.5 * (1.0 - cos(2.0 * PI * it / (size - 1))) }
    private val windowGain = window.sum() / size

    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)

    /** In-place complex forward transform. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size >= size && im.size >= size)

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Butterfly stages.
        var stageSize = 2
        while (stageSize <= size) {
            val halfStage = stageSize / 2
            val tableStep = size / stageSize
            var i = 0
            while (i < size) {
                var k = 0
                for (m in i until i + halfStage) {
                    val l = m + halfStage
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[m] - tr
                    im[l] = im[m] - ti
                    re[m] += tr
                    im[m] += ti
                    k += tableStep
                }
                i += stageSize
            }
            stageSize = stageSize shl 1
        }
    }

    /**
     * Windowed magnitude spectrum of real input, in dBFS.
     * Output length is [size] / 2 (positive frequencies only).
     */
    fun magnitudeSpectrumDb(
        samples: FloatArray,
        offset: Int = 0,
        output: FloatArray = FloatArray(half),
        floorDb: Float = -120f,
    ): FloatArray {
        for (i in 0 until size) {
            val idx = offset + i
            val value = if (idx < samples.size) samples[idx].toDouble() else 0.0
            real[i] = value * window[i]
            imag[i] = 0.0
        }

        transform(real, imag)

        val scale = 1.0 / (size * windowGain)
        for (bin in 0 until half) {
            val magnitude = hypot(real[bin], imag[bin]) * scale * 2.0
            val db = 20.0 * log10(magnitude.coerceAtLeast(1e-12))
            output[bin] = db.toFloat().coerceAtLeast(floorDb)
        }
        return output
    }

    /** Centre frequency of an output bin. */
    fun binFrequency(bin: Int, sampleRate: Int): Float = bin * sampleRate / size.toFloat()

    companion object {
        /**
         * Collapse linear FFT bins into log-spaced display bands — a linear
         * mapping makes the bass end unreadable.
         */
        fun toLogBands(
            magnitudesDb: FloatArray,
            sampleRate: Int,
            fftSize: Int,
            bandCount: Int,
            minFrequency: Float = 20f,
            maxFrequency: Float = 20_000f,
            output: FloatArray = FloatArray(bandCount),
        ): FloatArray {
            val safeMax = maxFrequency.coerceAtMost(sampleRate / 2f)
            val logMin = kotlin.math.ln(minFrequency.toDouble())
            val logMax = kotlin.math.ln(safeMax.toDouble())
            val step = (logMax - logMin) / bandCount
            val binWidth = sampleRate.toFloat() / fftSize

            for (band in 0 until bandCount) {
                val lowFreq = kotlin.math.exp(logMin + step * band)
                val highFreq = kotlin.math.exp(logMin + step * (band + 1))
                val startBin = (lowFreq / binWidth).toInt().coerceIn(0, magnitudesDb.size - 1)
                val endBin = (highFreq / binWidth).toInt().coerceIn(startBin, magnitudesDb.size - 1)

                var peak = Float.NEGATIVE_INFINITY
                for (bin in startBin..endBin) {
                    if (magnitudesDb[bin] > peak) peak = magnitudesDb[bin]
                }
                output[band] = if (peak.isFinite()) peak else -120f
            }
            return output
        }
    }
}

/**
 * Per-bin ballistics: fast attack, slow release, plus a decaying peak hold.
 * This is what makes an analyzer read naturally instead of flickering.
 */
class SpectrumSmoother(
    private val bandCount: Int,
    private var attack: Float = 0.55f,
    private var release: Float = 0.12f,
    private val peakDecayDbPerFrame: Float = 0.6f,
) {
    private val current = FloatArray(bandCount) { FLOOR_DB }
    private val peaks = FloatArray(bandCount) { FLOOR_DB }

    fun setSmoothing(amount: Float) {
        val clamped = amount.coerceIn(0f, 1f)
        attack = 0.9f - clamped * 0.6f
        release = 0.35f - clamped * 0.3f
    }

    fun process(input: FloatArray): FloatArray {
        for (i in 0 until minOf(bandCount, input.size)) {
            val target = input[i]
            current[i] = if (target > current[i]) {
                current[i] + (target - current[i]) * attack
            } else {
                current[i] + (target - current[i]) * release
            }
            peaks[i] = if (current[i] > peaks[i]) current[i] else peaks[i] - peakDecayDbPerFrame
        }
        return current
    }

    fun peakHold(): FloatArray = peaks

    fun reset() {
        current.fill(FLOOR_DB)
        peaks.fill(FLOOR_DB)
    }

    companion object {
        const val FLOOR_DB = -120f
    }
}
