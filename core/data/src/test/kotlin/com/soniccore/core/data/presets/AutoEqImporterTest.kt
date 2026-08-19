package com.soniccore.core.data.presets

import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutoEQ interchange parsing. This format is how thousands of community headphone
 * corrections are shared, so tolerance for real-world formatting variation matters
 * more than strictness.
 */
class AutoEqImporterTest {

    private val importer = AutoEqImporter()

    /** Verbatim shape of a published AutoEQ ParametricEQ.txt file. */
    private val realWorldPreset = """
        Sennheiser HD 600 ParametricEQ
        Preamp: -6.7 dB
        Filter 1: ON PK Fc 105 Hz Gain 4.7 dB Q 0.70
        Filter 2: ON PK Fc 1150 Hz Gain -1.4 dB Q 1.20
        Filter 3: ON PK Fc 3400 Hz Gain 3.2 dB Q 2.50
        Filter 4: ON PK Fc 6100 Hz Gain -4.1 dB Q 3.00
        Filter 5: ON PK Fc 10000 Hz Gain 2.8 dB Q 0.90
    """.trimIndent()

    @Test
    fun `parses a real autoeq preset into parametric bands`() {
        val result = importer.parse(realWorldPreset)
        assertTrue(result is AutoEqImporter.ImportResult.Success)
        val preset = (result as AutoEqImporter.ImportResult.Success).preset

        assertEquals(5, preset.settings.bands.size)
        assertEquals(EqMode.PARAMETRIC, preset.settings.mode)
        assertEquals(-6.7f, preset.settings.preampDb, 0.001f)
        // An explicit preamp must disable auto-preamp or it would be applied twice.
        assertTrue(!preset.settings.autoPreamp)
    }

    @Test
    fun `band values are parsed exactly`() {
        val preset = (importer.parse(realWorldPreset) as AutoEqImporter.ImportResult.Success).preset
        val first = preset.settings.bands[0]
        assertEquals(FilterType.PEAK, first.type)
        assertEquals(105f, first.frequencyHz, 0.001f)
        assertEquals(4.7f, first.gainDb, 0.001f)
        assertEquals(0.70f, first.q, 0.001f)

        val fourth = preset.settings.bands[3]
        assertEquals(6100f, fourth.frequencyHz, 0.001f)
        assertEquals(-4.1f, fourth.gainDb, 0.001f)
        assertEquals(3.00f, fourth.q, 0.001f)
    }

    @Test
    fun `shelf filter tokens are recognised`() {
        val text = """
            Preamp: -3.0 dB
            Filter 1: ON LSC Fc 105 Hz Gain 4.3 dB Q 0.70
            Filter 2: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
        """.trimIndent()
        val preset = (importer.parse(text) as AutoEqImporter.ImportResult.Success).preset
        assertEquals(FilterType.LOW_SHELF, preset.settings.bands[0].type)
        assertEquals(FilterType.HIGH_SHELF, preset.settings.bands[1].type)
    }

    @Test
    fun `all supported filter tokens map to a filter type`() {
        val tokens = mapOf(
            "PK" to FilterType.PEAK,
            "PEQ" to FilterType.PEAK,
            "LSC" to FilterType.LOW_SHELF,
            "LS" to FilterType.LOW_SHELF,
            "HSC" to FilterType.HIGH_SHELF,
            "HS" to FilterType.HIGH_SHELF,
            "LPQ" to FilterType.LOW_PASS,
            "HPQ" to FilterType.HIGH_PASS,
            "NO" to FilterType.NOTCH,
            "BP" to FilterType.BAND_PASS,
            "AP" to FilterType.ALL_PASS,
        )
        tokens.forEach { (token, expected) ->
            val text = "Preamp: 0.0 dB\nFilter 1: ON $token Fc 1000 Hz Gain 1.0 dB Q 1.00"
            val result = importer.parse(text)
            assertTrue("$token should parse", result is AutoEqImporter.ImportResult.Success)
            val band = (result as AutoEqImporter.ImportResult.Success).preset.settings.bands.first()
            assertEquals("token $token", expected, band.type)
        }
    }

    @Test
    fun `disabled filters are imported but marked off`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON PK Fc 100 Hz Gain 3.0 dB Q 1.00
            Filter 2: OFF PK Fc 200 Hz Gain 2.0 dB Q 1.00
        """.trimIndent()
        val preset = (importer.parse(text) as AutoEqImporter.ImportResult.Success).preset
        assertEquals(2, preset.settings.bands.size)
        assertTrue(preset.settings.bands[0].enabled)
        assertTrue(!preset.settings.bands[1].enabled)
    }

    @Test
    fun `filters without gain or q use safe defaults`() {
        // High/low-pass lines often omit Gain entirely.
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON HPQ Fc 30 Hz
        """.trimIndent()
        val preset = (importer.parse(text) as AutoEqImporter.ImportResult.Success).preset
        val band = preset.settings.bands.first()
        assertEquals(FilterType.HIGH_PASS, band.type)
        assertEquals(30f, band.frequencyHz, 0.001f)
        assertEquals(0f, band.gainDb, 0.001f)
        assertTrue(band.q > 0f)
    }

    @Test
    fun `unsupported filter type is warned about rather than failing the import`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON PK Fc 100 Hz Gain 3.0 dB Q 1.00
            Filter 2: ON XYZ Fc 200 Hz Gain 2.0 dB Q 1.00
        """.trimIndent()
        val result = importer.parse(text) as AutoEqImporter.ImportResult.Success
        assertEquals(1, result.preset.settings.bands.size)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.first().contains("XYZ"))
    }

    @Test
    fun `garbage input fails with a readable reason`() {
        val result = importer.parse("this is not an eq preset at all")
        assertTrue(result is AutoEqImporter.ImportResult.Failure)
        assertTrue(
            (result as AutoEqImporter.ImportResult.Failure).reason.contains("AutoEQ", ignoreCase = true),
        )
    }

    @Test
    fun `empty input fails cleanly`() {
        assertTrue(importer.parse("") is AutoEqImporter.ImportResult.Failure)
        assertTrue(importer.parse("   \n  \n ") is AutoEqImporter.ImportResult.Failure)
    }

    @Test
    fun `missing preamp line defaults to auto preamp`() {
        val text = "Filter 1: ON PK Fc 1000 Hz Gain 6.0 dB Q 1.00"
        val preset = (importer.parse(text) as AutoEqImporter.ImportResult.Success).preset
        assertEquals(0f, preset.settings.preampDb, 0.001f)
        // Without an explicit preamp we must compute one, or a +6 dB boost clips.
        assertTrue("auto preamp must engage", preset.settings.autoPreamp)
    }

    @Test
    fun `case insensitive keywords are accepted`() {
        val text = "preamp: -2.0 db\nfilter 1: on pk fc 1000 hz gain 3.0 db q 1.00"
        val result = importer.parse(text)
        assertTrue(result is AutoEqImporter.ImportResult.Success)
        assertEquals(-2f, (result as AutoEqImporter.ImportResult.Success).preset.settings.preampDb, 0.001f)
    }

    @Test
    fun `explicit name overrides the inferred header`() {
        val preset = (
            importer.parse(realWorldPreset, presetName = "My HD600") as AutoEqImporter.ImportResult.Success
            ).preset
        assertEquals("My HD600", preset.name)
    }

    @Test
    fun `inferred name comes from the header line`() {
        val preset = (importer.parse(realWorldPreset) as AutoEqImporter.ImportResult.Success).preset
        assertTrue(preset.name.contains("HD 600"))
    }

    @Test
    fun `imported bands are sanitized against NaN-producing values`() {
        val text = """
            Preamp: 0.0 dB
            Filter 1: ON PK Fc 99000 Hz Gain 99.0 dB Q 0.001
        """.trimIndent()
        val preset = (importer.parse(text) as AutoEqImporter.ImportResult.Success).preset
        val band = preset.settings.bands.first()
        assertTrue("frequency must be clamped", band.frequencyHz < 25_000f)
        assertTrue("gain must be clamped", band.gainDb <= 24f)
        assertTrue("q must be clamped", band.q >= 0.05f)
    }

    @Test
    fun `export produces text the importer can read back`() {
        val original = (importer.parse(realWorldPreset) as AutoEqImporter.ImportResult.Success).preset
        val exported = importer.export(original)
        val reimported = importer.parse(exported)

        assertTrue(reimported is AutoEqImporter.ImportResult.Success)
        val restored = (reimported as AutoEqImporter.ImportResult.Success).preset

        assertEquals(original.settings.bands.size, restored.settings.bands.size)
        original.settings.bands.forEachIndexed { index, band ->
            val other = restored.settings.bands[index]
            assertEquals(band.type, other.type)
            assertEquals(band.frequencyHz, other.frequencyHz, 1f)
            assertEquals(band.gainDb, other.gainDb, 0.1f)
            assertEquals(band.q, other.q, 0.01f)
        }
    }

    @Test
    fun `exported text carries the standard header lines`() {
        val preset = (importer.parse(realWorldPreset) as AutoEqImporter.ImportResult.Success).preset
        val exported = importer.export(preset)
        assertTrue(exported.contains("Preamp:"))
        assertTrue(exported.contains("Filter 1:"))
        assertTrue(exported.contains("Fc"))
        assertTrue(exported.contains("Gain"))
        assertTrue(exported.contains("Q"))
    }
}
