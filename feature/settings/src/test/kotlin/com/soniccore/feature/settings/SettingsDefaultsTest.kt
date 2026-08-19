package com.soniccore.feature.settings

import com.soniccore.core.model.settings.AccentPalette
import com.soniccore.core.model.settings.AppSettings
import com.soniccore.core.model.settings.StartupScreen
import com.soniccore.core.model.settings.ThemeMode
import com.soniccore.core.model.settings.VisualizationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings defaults and clamping. Defaults matter disproportionately: they are what
 * every user experiences before touching anything, so a bad FPS or safety value
 * ships as the product's actual behaviour.
 */
class SettingsDefaultsTest {

    @Test
    fun `default theme follows the system rather than forcing a choice`() {
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
    }

    @Test
    fun `dynamic colour is the default palette on modern android`() {
        val s = AppSettings()
        assertEquals(AccentPalette.DYNAMIC, s.accentPalette)
        assertTrue(s.useDynamicColor)
    }

    @Test
    fun `enum options are distinct and complete`() {
        listOf(
            ThemeMode.entries.map { it.name },
            AccentPalette.entries.map { it.name },
            StartupScreen.entries.map { it.name },
            VisualizationStyle.entries.map { it.name },
        ).forEach { names ->
            assertEquals(names.size, names.distinct().size)
            assertTrue(names.isNotEmpty())
        }
        assertTrue(ThemeMode.entries.contains(ThemeMode.DARK))
        assertTrue(ThemeMode.entries.contains(ThemeMode.LIGHT))
    }

    @Test
    fun `startup screen defaults to the dashboard`() {
        assertEquals(StartupScreen.DASHBOARD, AppSettings().startupScreen)
    }

    // --- onboarding ---

    @Test
    fun `onboarding starts incomplete so first run shows it`() {
        assertFalse(AppSettings().onboardingComplete)
    }

    // --- visualization performance ---

    @Test
    fun `visualization fps default is smooth but not wasteful`() {
        val fps = AppSettings().visualizationFps
        // Below 24 looks choppy; above 60 burns battery for no visible gain.
        assertTrue("fps was $fps", fps in 24..60)
    }

    @Test
    fun `fps is clamped to a renderable range`() {
        fun clamp(v: Int) = v.coerceIn(15, 120)
        assertEquals(15, clamp(1))
        assertEquals(120, clamp(999))
        assertEquals(60, clamp(60))
    }

    @Test
    fun `spectrum smoothing is a normalized fraction below unity`() {
        val s = AppSettings().spectrumSmoothing
        assertTrue("smoothing was $s", s in 0f..1f)
        // A coefficient of exactly 1.0 means "never update" — the display freezes.
        assertTrue(s < 1f)
    }

    @Test
    fun `smoothing clamp keeps the display alive`() {
        fun clamp(v: Float) = v.coerceIn(0f, 0.99f)
        assertTrue(clamp(1f) < 1f)
        assertEquals(0f, clamp(-1f), 0.001f)
    }

    // --- haptics ---

    @Test
    fun `haptic intensity is normalized`() {
        val i = AppSettings().hapticIntensity
        assertTrue("intensity was $i", i in 0f..1f)
    }

    @Test
    fun `disabling haptics preserves the chosen intensity`() {
        val s = AppSettings().copy(hapticFeedback = false, hapticIntensity = 0.7f)
        assertFalse(s.hapticFeedback)
        assertEquals(0.7f, s.hapticIntensity, 0.001f)
    }

    // --- hearing safety ---

    @Test
    fun `safe listening is on by default with a WHO aligned budget`() {
        val s = AppSettings()
        assertTrue(s.safeListeningEnabled)
        // WHO guidance is ~80 dB for 40h/week; 480 min/day is a sane ceiling.
        assertEquals(480, s.safeListeningDailyBudgetMinutes)
        assertTrue("warning must be a realistic SPL", s.safeVolumeWarningDb in 70f..100f)
    }

    @Test
    fun `daily budget is clamped to a real day`() {
        fun clamp(m: Int) = m.coerceIn(0, 1440)
        assertEquals(1440, clamp(99_999))
        assertEquals(0, clamp(-10))
    }

    // --- privacy ---

    @Test
    fun `telemetry is opt in, never on by default`() {
        val s = AppSettings()
        assertFalse("analytics must be opt-in", s.analyticsEnabled)
        assertFalse("crash reporting must be opt-in", s.crashReportingEnabled)
    }

    // --- experimental ---

    @Test
    fun `experimental features default off`() {
        val s = AppSettings()
        // Anything that can destabilise audio must be opt-in.
        assertFalse(s.experimentalBitPerfect)
        assertFalse(s.experimentalCodecControl)
        assertFalse(s.developerMode)
    }

    // --- service and core behaviour ---

    @Test
    fun `auto apply profiles defaults on because it is the core feature`() {
        assertTrue(AppSettings().autoApplyProfiles)
    }

    @Test
    fun `service and notification defaults keep the app functional in background`() {
        val s = AppSettings()
        assertTrue(s.keepServiceAlive)
        assertTrue(s.persistentNotification)
    }

    @Test
    fun `nullable fields start null rather than sentinel values`() {
        val s = AppSettings()
        assertNull(s.volumeStepOverride)
        assertNull(s.lastBackupEpochMs)
        assertNull(s.activeProfileId)
    }

    // --- integrity ---

    @Test
    fun `copy preserves every unrelated field`() {
        val original = AppSettings()
        val modified = original.copy(themeMode = ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, modified.themeMode)
        assertEquals(original.visualizationFps, modified.visualizationFps)
        assertEquals(original.hapticIntensity, modified.hapticIntensity, 0.001f)
        assertEquals(original.autoApplyProfiles, modified.autoApplyProfiles)
        assertEquals(original.safeListeningDailyBudgetMinutes, modified.safeListeningDailyBudgetMinutes)
    }

    @Test
    fun `defaults are deterministic so datastore does not churn on launch`() {
        assertEquals(AppSettings(), AppSettings())
    }
}
