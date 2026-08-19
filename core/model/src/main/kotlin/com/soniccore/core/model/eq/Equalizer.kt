package com.soniccore.core.model.eq

import kotlinx.serialization.Serializable

/** Biquad filter shapes (RBJ cookbook). */
@Serializable
enum class FilterType(val displayName: String, val shortLabel: String, val usesQ: Boolean, val usesGain: Boolean) {
    PEAK("Peaking", "PK", true, true),
    LOW_SHELF("Low shelf", "LSC", true, true),
    HIGH_SHELF("High shelf", "HSC", true, true),
    LOW_PASS("Low pass", "LPQ", true, false),
    HIGH_PASS("High pass", "HPQ", true, false),
    BAND_PASS("Band pass", "BP", true, false),
    NOTCH("Notch", "NO", true, false),
    ALL_PASS("All pass", "AP", true, false),
}

/**
 * One EQ band. Persisted by frequency/gain/Q — never by band index, because band
 * counts differ across devices and EQ modes.
 */
@Serializable
data class EqBand(
    val id: String,
    val type: FilterType = FilterType.PEAK,
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = DEFAULT_Q,
    val enabled: Boolean = true,
) {
    /** Clamp to a range that cannot produce NaN in the biquad. */
    fun sanitized(sampleRate: Int = 48_000): EqBand = copy(
        frequencyHz = frequencyHz.coerceIn(MIN_FREQ, sampleRate * 0.49f),
        gainDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
        q = q.coerceIn(MIN_Q, MAX_Q),
    )

    companion object {
        const val MIN_FREQ = 10f
        const val MIN_GAIN_DB = -24f
        const val MAX_GAIN_DB = 24f
        const val MIN_Q = 0.05f
        const val MAX_Q = 40f
        const val DEFAULT_Q = 1.41f
    }
}

/** Which EQ surface the user is editing. */
@Serializable
enum class EqMode(val displayName: String, val bandCount: Int?) {
    GRAPHIC_10("10-band", 10),
    GRAPHIC_15("15-band", 15),
    GRAPHIC_31("31-band", 31),
    PARAMETRIC("Parametric", null),
    QUICK("Bass / Treble", 2),
    OFF("Off", 0),
}

/**
 * A complete EQ configuration.
 *
 * [preampDb] must offset positive gain or the chain clips; use
 * [autoPreampDb] to compute the safe value.
 */
@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val mode: EqMode = EqMode.GRAPHIC_10,
    val bands: List<EqBand> = emptyList(),
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val useParametricEngine: Boolean = true,
    val phaseLinear: Boolean = false,
    val channelLink: Boolean = true,
    val leftBands: List<EqBand> = emptyList(),
    val rightBands: List<EqBand> = emptyList(),
) {
    val activeBands: List<EqBand> get() = bands.filter { it.enabled }

    companion object {
        /** ISO 1/3-octave centres. */
        val ISO_31_BANDS = listOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f,
            400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f,
            5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
        )

        /** ISO 1-octave centres. */
        val ISO_10_BANDS = listOf(
            31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )

        /** 2/3-octave, 15 bands. */
        val ISO_15_BANDS = listOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f,
            1600f, 2500f, 4000f, 6300f, 10000f, 16000f,
        )

        fun frequenciesFor(mode: EqMode): List<Float> = when (mode) {
            EqMode.GRAPHIC_10 -> ISO_10_BANDS
            EqMode.GRAPHIC_15 -> ISO_15_BANDS
            EqMode.GRAPHIC_31 -> ISO_31_BANDS
            EqMode.QUICK -> listOf(100f, 8000f)
            EqMode.PARAMETRIC, EqMode.OFF -> emptyList()
        }

        /** Fixed Q for n-octave graphic spacing: sqrt(2^n)/(2^n - 1). */
        fun graphicQFor(mode: EqMode): Float = when (mode) {
            EqMode.GRAPHIC_31 -> 4.32f
            EqMode.GRAPHIC_15 -> 2.14f
            EqMode.GRAPHIC_10 -> 1.41f
            EqMode.QUICK -> 0.7f
            else -> EqBand.DEFAULT_Q
        }

        fun flat(mode: EqMode): EqSettings {
            val freqs = frequenciesFor(mode)
            val q = graphicQFor(mode)
            return EqSettings(
                mode = mode,
                bands = freqs.mapIndexed { i, f ->
                    EqBand(
                        id = "band_$i",
                        type = when {
                            mode == EqMode.QUICK && i == 0 -> FilterType.LOW_SHELF
                            mode == EqMode.QUICK -> FilterType.HIGH_SHELF
                            else -> FilterType.PEAK
                        },
                        frequencyHz = f,
                        gainDb = 0f,
                        q = q,
                    )
                },
            )
        }
    }
}

/** A named, storable EQ preset. */
@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val settings: EqSettings,
    val isBuiltIn: Boolean = false,
    val targetDeviceKey: String? = null,
    val author: String? = null,
    val description: String? = null,
    val createdAtEpochMs: Long = 0L,
)

/** One point of a computed frequency response, for curve rendering. */
data class ResponsePoint(val frequencyHz: Float, val magnitudeDb: Float)

/** Live FFT frame for the analyzer. */
data class SpectrumFrame(
    val magnitudesDb: FloatArray,
    val sampleRate: Int,
    val timestampNanos: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrumFrame) return false
        return timestampNanos == other.timestampNanos &&
            sampleRate == other.sampleRate &&
            magnitudesDb.contentEquals(other.magnitudesDb)
    }

    override fun hashCode(): Int {
        var result = magnitudesDb.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampNanos.hashCode()
        return result
    }
}

/** Loudness measurement per ITU-R BS.1770-4. */
data class LoudnessReading(
    val momentaryLufs: Float,
    val shortTermLufs: Float,
    val integratedLufs: Float,
    val truePeakDbfs: Float,
    val loudnessRange: Float,
    val isClipping: Boolean,
)
