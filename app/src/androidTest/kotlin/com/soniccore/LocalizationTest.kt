package com.soniccore

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.soniccore.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

/**
 * Proves the app is actually translatable.
 *
 * Extracting strings to resources is only worth anything if the platform resolves them
 * per locale. This loads the same keys under en and es and asserts they differ — if a
 * literal ever creeps back into Kotlin, the Spanish lookup silently returns English and
 * this test fails.
 */
class LocalizationTest {

    private fun stringsFor(locale: Locale): Triple<String, String, String> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        val ctx = base.createConfigurationContext(config)
        return Triple(
            ctx.getString(R.string.diagnostics_export),
            ctx.getString(R.string.tile_equalizer),
            ctx.getString(R.string.empty_rules_title),
        )
    }

    @Test
    fun spanishDiffersFromEnglish() {
        val en = stringsFor(Locale.ENGLISH)
        val es = stringsFor(Locale("es"))

        assertEquals("Export log", en.first)
        assertEquals("Exportar registro", es.first)
        assertNotEquals(en.second, es.second)
        assertNotEquals(en.third, es.third)
    }

    @Test
    fun pluralsResolvePerLocaleAndQuantity() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun plural(locale: Locale, qty: Int): String {
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config).resources
                .getQuantityString(R.plurals.count_devices, qty, qty)
        }

        // Singular vs plural must differ — the whole reason these are plurals and not
        // "%d " + a hardcoded word, which is unfixable in most languages.
        assertEquals("1 device", plural(Locale.ENGLISH, 1))
        assertEquals("3 devices", plural(Locale.ENGLISH, 3))
        assertEquals("1 dispositivo", plural(Locale("es"), 1))
        assertEquals("3 dispositivos", plural(Locale("es"), 3))
    }

    @Test
    fun untranslatedKeysFallBackToDefaultRatherThanCrashing() {
        // The Spanish translation is deliberately partial. Any key missing from values-es
        // must fall back to values/ — never throw, never render empty.
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale("es")) }
        val ctx = base.createConfigurationContext(config)

        val untranslated = ctx.getString(R.string.format_khz, "44.1")
        assertEquals("44.1 kHz", untranslated)
    }
}
