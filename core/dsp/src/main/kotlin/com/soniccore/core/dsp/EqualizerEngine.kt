package com.soniccore.core.dsp

import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.ResponsePoint
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Multi-band, multi-channel equalizer built from [Biquad] stages.
 *
 * Filter state is per channel per band. Coefficient changes are applied with a
 * short gain ramp to avoid the click that an instantaneous coefficient swap
 * produces mid-stream.
 */
class EqualizerEngine(
    private var sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private var channelCount: Int = 2,
) {

    /** [channel][band] */
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var bands: List<EqBand> = emptyList()

    private var preampLinear: Double = 1.0
    private var targetPreampLinear: Double = 1.0
    private var enabled: Boolean = false

    private var peakLevel: Float = 0f
    private var clipped: Boolean = false

    @Synchronized
    fun configure(settings: EqSettings, sampleRate: Int = this.sampleRate, channelCount: Int = this.channelCount) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channelCount = channelCount.coerceIn(1, MAX_CHANNELS)
        this.enabled = settings.enabled
        this.bands = settings.bands.map { it.sanitized(this.sampleRate) }

        filters = Array(this.channelCount) {
            Array(bands.size) { bandIndex ->
                Biquad().apply { configure(bands[bandIndex], this@EqualizerEngine.sampleRate) }
            }
        }

        val preampDb = if (settings.autoPreamp) {
            autoPreampDb(settings, this.sampleRate) + settings.preampDb
        } else {
            settings.preampDb
        }
        targetPreampLinear = dbToLinear(preampDb.toDouble())
        if (preampLinear == 1.0) preampLinear = targetPreampLinear
    }

    @Synchronized
    fun reset() {
        filters.forEach { channel -> channel.forEach { it.reset() } }
        peakLevel = 0f
        clipped = false
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) reset()
    }

    /**
     * Process interleaved float PCM in place.
     * Returns true if any sample clipped after processing.
     */
    @Synchronized
    fun processInterleaved(buffer: FloatArray, frameCount: Int = buffer.size / channelCount): Boolean {
        if (!enabled || filters.isEmpty()) return false

        var localPeak = 0f
        var didClip = false
        val ch = channelCount

        for (frame in 0 until frameCount) {
            // Smoothly approach the target preamp to avoid zipper noise.
            preampLinear += (targetPreampLinear - preampLinear) * PREAMP_SMOOTHING

            for (c in 0 until ch) {
                val index = frame * ch + c
                if (index >= buffer.size) break

                var sample = buffer[index] * preampLinear
                val chain = filters[c]
                for (b in chain.indices) {
                    sample = chain[b].process(sample)
                }

                val out = sample.toFloat()
                val magnitude = abs(out)
                if (magnitude > localPeak) localPeak = magnitude
                if (magnitude >= CLIP_THRESHOLD) didClip = true

                buffer[index] = out.coerceIn(-1f, 1f)
            }
        }

        peakLevel = localPeak
        clipped = didClip
        return didClip
    }

    fun currentPeak(): Float = peakLevel
    fun isClipping(): Boolean = clipped

    /**
     * Combined magnitude response on a log-spaced grid. Summing dB across bands
     * is equivalent to multiplying linear magnitudes.
     */
    @Synchronized
    fun frequencyResponse(
        pointCount: Int = DEFAULT_CURVE_POINTS,
        minFrequency: Float = 20f,
        maxFrequency: Float = 20_000f,
    ): List<ResponsePoint> {
        val safeMax = maxFrequency.coerceAtMost(sampleRate / 2f * 0.98f)
        val chain = filters.firstOrNull() ?: return emptyList()
        val logMin = ln(minFrequency.toDouble())
        val logMax = ln(safeMax.toDouble())
        val step = (logMax - logMin) / (pointCount - 1).coerceAtLeast(1)
        val preampDb = linearToDb(targetPreampLinear)

        return (0 until pointCount).map { i ->
            val freq = kotlin.math.exp(logMin + step * i)
            var totalDb = preampDb
            for (filter in chain) {
                totalDb += filter.magnitudeDbAt(freq, sampleRate)
            }
            ResponsePoint(freq.toFloat(), totalDb.toFloat())
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_CURVE_POINTS = 220
        private const val MAX_CHANNELS = 8
        private const val CLIP_THRESHOLD = 0.999f
        private const val PREAMP_SMOOTHING = 0.002

        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

        fun linearToDb(linear: Double): Double =
            20.0 * kotlin.math.log10(linear.coerceAtLeast(1e-12))

        /**
         * Preamp needed to keep the summed response at or below 0 dB.
         * Any positive EQ gain can clip; this is not optional.
         */
        fun autoPreampDb(
            settings: EqSettings,
            sampleRate: Int = DEFAULT_SAMPLE_RATE,
            pointCount: Int = 160,
        ): Float {
            val active = settings.bands.filter { it.enabled }
            if (active.isEmpty()) return 0f

            val probes = active.map { band ->
                Biquad().apply { configure(band, sampleRate) }
            }

            val logMin = ln(20.0)
            val logMax = ln((sampleRate / 2.0 * 0.98).coerceAtMost(20_000.0))
            val step = (logMax - logMin) / (pointCount - 1)

            var maxDb = 0.0
            for (i in 0 until pointCount) {
                val freq = kotlin.math.exp(logMin + step * i)
                var sum = 0.0
                for (probe in probes) sum += probe.magnitudeDbAt(freq, sampleRate)
                if (sum > maxDb) maxDb = sum
            }
            return (-maxDb).toFloat().coerceIn(-24f, 0f)
        }
    }
}
