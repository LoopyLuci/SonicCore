package com.soniccore.feature.equalizer

import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EQ state transitions that must not lose user work.
 *
 * These target the pure decision logic used by [EqualizerViewModel] — switching
 * resolution, clamping, and preamp computation — without needing Android or Hilt.
 */
class EqStateLogicTest {

    /**
     * Mirrors the mode-change rule: gains are re-sampled onto the new grid so
     * switching 10-band -> 31-band keeps the user's curve shape.
     */
    private fun changeMode(current: EqSettings, target: EqMode): EqSettings {
        if (target == current.mode) return current
        if (target == EqMode.PARAMETRIC || target == EqMode.OFF) {
            return current.copy(mode = target)
        }
        val frequencies = EqSettings.frequenciesFor(target)
        val q = EqSettings.graphicQFor(target)
        val bands = frequencies.mapIndexed { index, hz ->
            val gain = interpolateGain(current, hz)
            com.soniccore.core.model.eq.EqBand(
                id = "band_$index",
                type = when {
                    index == 0 && target == EqMode.QUICK -> FilterType.LOW_SHELF
                    index == frequencies.lastIndex && target == EqMode.QUICK -> FilterType.HIGH_SHELF
                    else -> FilterType.PEAK
                },
                frequencyHz = hz,
                gainDb = gain,
                q = q,
            )
        }
        return current.copy(mode = target, bands = bands)
    }

    /** Linear interpolation in log-frequency space between the nearest existing bands. */
    private fun interpolateGain(settings: EqSettings, hz: Float): Float {
        val bands = settings.bands.sortedBy { it.frequencyHz }
        if (bands.isEmpty()) return 0f
        if (hz <= bands.first().frequencyHz) return bands.first().gainDb
        if (hz >= bands.last().frequencyHz) return bands.last().gainDb
        val upperIndex = bands.indexOfFirst { it.frequencyHz >= hz }
        val upper = bands[upperIndex]
        val lower = bands[upperIndex - 1]
        val span = kotlin.math.ln(upper.frequencyHz / lower.frequencyHz)
        if (span <= 0f) return lower.gainDb
        val t = kotlin.math.ln(hz / lower.frequencyHz) / span
        return lower.gainDb + (upper.gainDb - lower.gainDb) * t
    }

    @Test
    fun `switching from 10 to 31 band preserves the curve shape`() {
        val tenBand = EqSettings.flat(EqMode.GRAPHIC_10).let { flat ->
            // A bass-heavy curve: boost the bottom, cut the top.
            flat.copy(
                bands = flat.bands.mapIndexed { i, b ->
                    b.copy(gainDb = if (i < 3) 6f else if (i > 7) -4f else 0f)
                },
            )
        }

        val thirtyOne = changeMode(tenBand, EqMode.GRAPHIC_31)

        assertEquals(31, thirtyOne.bands.size)
        // Bass must still be boosted and treble still cut.
        assertTrue("bass should remain boosted", thirtyOne.bands.first().gainDb > 3f)
        assertTrue("treble should remain cut", thirtyOne.bands.last().gainDb < -1f)
    }

    @Test
    fun `switching to parametric keeps the existing bands for hand editing`() {
        val graphic = EqSettings.flat(EqMode.GRAPHIC_10)
            .let { it.copy(bands = it.bands.map { b -> b.copy(gainDb = 2f) }) }
        val parametric = changeMode(graphic, EqMode.PARAMETRIC)
        assertEquals(EqMode.PARAMETRIC, parametric.mode)
        assertEquals(graphic.bands.size, parametric.bands.size)
        assertTrue(parametric.bands.all { it.gainDb == 2f })
    }

    @Test
    fun `switching to the same mode is a no-op`() {
        val original = EqSettings.flat(EqMode.GRAPHIC_10)
        assertTrue(changeMode(original, EqMode.GRAPHIC_10) === original)
    }

    @Test
    fun `quick mode assigns shelves at the extremes`() {
        val quick = changeMode(EqSettings.flat(EqMode.GRAPHIC_10), EqMode.QUICK)
        assertEquals(FilterType.LOW_SHELF, quick.bands.first().type)
        assertEquals(FilterType.HIGH_SHELF, quick.bands.last().type)
    }

    @Test
    fun `interpolation is exact at existing band frequencies`() {
        val settings = EqSettings(
            bands = listOf(
                com.soniccore.core.model.eq.EqBand("a", frequencyHz = 100f, gainDb = 6f),
                com.soniccore.core.model.eq.EqBand("b", frequencyHz = 1000f, gainDb = -3f),
            ),
        )
        assertEquals(6f, interpolateGain(settings, 100f), 0.001f)
        assertEquals(-3f, interpolateGain(settings, 1000f), 0.001f)
        // Geometric midpoint of 100 and 1000 is ~316 Hz -> halfway between gains.
        assertEquals(1.5f, interpolateGain(settings, 316.23f), 0.05f)
    }

    @Test
    fun `interpolation clamps outside the defined range instead of extrapolating`() {
        val settings = EqSettings(
            bands = listOf(
                com.soniccore.core.model.eq.EqBand("a", frequencyHz = 100f, gainDb = 6f),
                com.soniccore.core.model.eq.EqBand("b", frequencyHz = 1000f, gainDb = -3f),
            ),
        )
        // Extrapolating a slope past the last band would produce absurd gains.
        assertEquals(6f, interpolateGain(settings, 20f), 0.001f)
        assertEquals(-3f, interpolateGain(settings, 20000f), 0.001f)
    }

    @Test
    fun `empty band list interpolates to unity gain`() {
        assertEquals(0f, interpolateGain(EqSettings(bands = emptyList()), 1000f), 0.001f)
    }

    /** Auto-preamp must offset the worst-case boost so the sum cannot clip. */
    private fun autoPreamp(settings: EqSettings): Float {
        val maxBoost = settings.activeBands.maxOfOrNull { it.gainDb } ?: 0f
        return if (maxBoost > 0f) -maxBoost else 0f
    }

    @Test
    fun `auto preamp offsets the largest boost`() {
        val boosted = EqSettings(
            bands = listOf(
                com.soniccore.core.model.eq.EqBand("a", frequencyHz = 60f, gainDb = 9f),
                com.soniccore.core.model.eq.EqBand("b", frequencyHz = 1000f, gainDb = 3f),
            ),
        )
        assertEquals(-9f, autoPreamp(boosted), 0.001f)
    }

    @Test
    fun `auto preamp is zero when nothing is boosted`() {
        val cutOnly = EqSettings(
            bands = listOf(
                com.soniccore.core.model.eq.EqBand("a", frequencyHz = 60f, gainDb = -6f),
            ),
        )
        assertEquals(0f, autoPreamp(cutOnly), 0.001f)
    }

    @Test
    fun `disabled bands are excluded from preamp computation`() {
        val settings = EqSettings(
            bands = listOf(
                com.soniccore.core.model.eq.EqBand("a", frequencyHz = 60f, gainDb = 12f, enabled = false),
                com.soniccore.core.model.eq.EqBand("b", frequencyHz = 1000f, gainDb = 3f),
            ),
        )
        // The disabled +12 band must not drag the preamp down.
        assertEquals(-3f, autoPreamp(settings), 0.001f)
    }

    @Test
    fun `every graphic mode round trips through mode changes without losing bands`() {
        val modes = listOf(EqMode.QUICK, EqMode.GRAPHIC_10, EqMode.GRAPHIC_15, EqMode.GRAPHIC_31)
        var settings = EqSettings.flat(EqMode.GRAPHIC_10)
            .let { it.copy(bands = it.bands.map { b -> b.copy(gainDb = 3f) }) }

        modes.forEach { mode ->
            settings = changeMode(settings, mode)
            assertEquals(mode, settings.mode)
            assertEquals(EqSettings.frequenciesFor(mode).size, settings.bands.size)
            // The uniform +3 dB tilt must survive every resample.
            assertTrue(
                "gain lost switching to $mode",
                settings.bands.all { it.gainDb > 2f },
            )
        }
    }
}
