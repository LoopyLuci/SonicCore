package com.soniccore.core.data.presets

import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqPreset
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports and exports AutoEQ / Equalizer APO "ParametricEQ" text, the de-facto
 * interchange format for headphone correction curves.
 *
 * Example input:
 * ```
 * Preamp: -6.2 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.1 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain 4.3 dB Q 0.70
 * Filter 3: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
 * ```
 * Supporting this single format unlocks thousands of community presets, so it is
 * worth the small parser.
 */
@Singleton
class AutoEqImporter @Inject constructor() {

    private val filterRegex = Regex(
        """Filter\s+(\d+)\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+(-?[\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )
    private val preampRegex = Regex("""Preamp\s*:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

    fun parse(text: String, presetName: String? = null): ImportResult {
        val bands = mutableListOf<EqBand>()
        val warnings = mutableListOf<String>()

        val preampDb = preampRegex.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        filterRegex.findAll(text).forEach { match ->
            val groups = match.groupValues
            val index = groups[1]
            val state = groups[2]
            val typeToken = groups[3]
            val freqToken = groups[4]
            val gainToken = groups[5]
            val qToken = groups[6]
            val type = mapFilterType(typeToken)
            if (type == null) {
                warnings += "Unsupported filter type '$typeToken' on filter $index — skipped"
                return@forEach
            }
            val frequency = freqToken.toFloatOrNull()
            if (frequency == null) {
                warnings += "Bad frequency on filter $index — skipped"
                return@forEach
            }
            bands += EqBand(
                id = "imported_$index",
                type = type,
                frequencyHz = frequency,
                gainDb = gainToken.toFloatOrNull() ?: 0f,
                q = qToken.toFloatOrNull() ?: EqBand.DEFAULT_Q,
                enabled = state.equals("ON", ignoreCase = true),
            ).sanitized()
        }

        if (bands.isEmpty()) {
            return ImportResult.Failure("No valid filter lines found. Expected AutoEQ ParametricEQ format.")
        }

        val name = presetName
            ?: text.lineSequence().firstOrNull { it.isNotBlank() && !it.contains("Preamp") && !it.contains("Filter") }
                ?.trim()
                ?.take(60)
            ?: "Imported preset"

        return ImportResult.Success(
            preset = EqPreset(
                id = UUID.randomUUID().toString(),
                name = name,
                description = "Imported AutoEQ profile (${bands.size} bands)",
                createdAtEpochMs = System.currentTimeMillis(),
                settings = EqSettings(
                    enabled = true,
                    mode = EqMode.PARAMETRIC,
                    bands = bands,
                    preampDb = preampDb,
                    autoPreamp = preampDb == 0f,
                    useParametricEngine = true,
                ),
            ),
            warnings = warnings,
        )
    }

    fun export(preset: EqPreset): String = buildString {
        appendLine(preset.name)
        appendLine("Preamp: ${"%.1f".format(preset.settings.preampDb)} dB")
        preset.settings.bands.forEachIndexed { index, band ->
            append("Filter ${index + 1}: ")
            append(if (band.enabled) "ON " else "OFF ")
            append("${tokenFor(band.type)} ")
            append("Fc ${"%.0f".format(band.frequencyHz)} Hz ")
            append("Gain ${"%.1f".format(band.gainDb)} dB ")
            appendLine("Q ${"%.2f".format(band.q)}")
        }
    }

    private fun mapFilterType(token: String): FilterType? = when (token.uppercase()) {
        "PK", "PEQ", "PEAK", "MODAL" -> FilterType.PEAK
        "LSC", "LS", "LOWSHELF" -> FilterType.LOW_SHELF
        "HSC", "HS", "HIGHSHELF" -> FilterType.HIGH_SHELF
        "LPQ", "LP", "LP2" -> FilterType.LOW_PASS
        "HPQ", "HP", "HP2" -> FilterType.HIGH_PASS
        "NO", "NOTCH" -> FilterType.NOTCH
        "BP", "BPQ" -> FilterType.BAND_PASS
        "AP", "APQ" -> FilterType.ALL_PASS
        else -> null
    }

    private fun tokenFor(type: FilterType): String = when (type) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSC"
        FilterType.HIGH_SHELF -> "HSC"
        FilterType.LOW_PASS -> "LPQ"
        FilterType.HIGH_PASS -> "HPQ"
        FilterType.NOTCH -> "NO"
        FilterType.BAND_PASS -> "BP"
        FilterType.ALL_PASS -> "AP"
    }

    sealed interface ImportResult {
        data class Success(val preset: EqPreset, val warnings: List<String>) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }
}
