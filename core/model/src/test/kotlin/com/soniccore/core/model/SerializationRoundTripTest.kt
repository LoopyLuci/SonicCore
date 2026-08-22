package com.soniccore.core.model

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleCondition
import com.soniccore.core.model.automation.RuleTrigger
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import com.soniccore.core.model.profile.AppOverride
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.ProfileIcon
import com.soniccore.core.model.profile.VolumeSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings are persisted as JSON columns in Room, so a serialization regression
 * silently destroys user data. These round-trips are the guard.
 */
class SerializationRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `a fully populated profile survives encode and decode`() {
        val original = AudioProfile(
            id = "p1",
            name = "Studio",
            icon = ProfileIcon.RECORDING,
            colorArgb = 0xFF4F8CFF.toInt(),
            description = "Flat reference",
            boundDeviceKeys = setOf("USB:OUTPUT:dac", "BLUETOOTH_CLASSIC:OUTPUT:AA"),
            volume = VolumeSettings(
                streamPercents = mapOf(AudioStream.MUSIC to 0.55f, AudioStream.ALARM to 0.3f),
                volumeLimitPercent = 0.8f,
                lockVolume = true,
                softwareGainDb = -2.5f,
            ),
            eq = EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bands = listOf(
                    EqBand("b1", FilterType.LOW_SHELF, 105f, -2.1f, 0.7f),
                    EqBand("b2", FilterType.PEAK, 2500f, 3.4f, 2.2f, enabled = false),
                ),
                preampDb = -6.2f,
                autoPreamp = false,
            ),
            effects = EffectsSettings(),
            appOverrides = listOf(
                AppOverride("com.spotify.music", "Spotify", volumePercent = 0.7f, duckOthers = true),
            ),
            priority = 12,
        )

        val decoded = json.decodeFromString(AudioProfile.serializer(), json.encodeToString(AudioProfile.serializer(), original))

        assertEquals(original, decoded)
        // Spot-check the nested structures that a shallow copy would lose.
        assertEquals(2, decoded.eq.bands.size)
        assertEquals(FilterType.LOW_SHELF, decoded.eq.bands[0].type)
        assertEquals(-6.2f, decoded.eq.preampDb, 0.0001f)
        assertTrue(!decoded.eq.bands[1].enabled)
        assertEquals(0.55f, decoded.volume.streamPercents[AudioStream.MUSIC])
        assertEquals(2, decoded.boundDeviceKeys.size)
        assertEquals("Spotify", decoded.appOverrides.first().appLabel)
    }

    @Test
    fun `polymorphic automation rule survives round trip`() {
        val rule = AutomationRule(
            id = "r1",
            name = "Gym",
            trigger = RuleTrigger.DeviceConnected("BLUETOOTH_CLASSIC:OUTPUT:AA"),
            conditions = listOf(
                RuleCondition.BatteryAbove(20),
                RuleCondition.Not(RuleCondition.IsCharging(true)),
            ),
            actions = listOf(
                RuleAction.ActivateProfile("builtin_workout"),
                RuleAction.SetStreamVolume(AudioStream.MUSIC, 0.75f),
                RuleAction.FadeVolume(AudioStream.MUSIC, 0.9f, 3_000),
            ),
            priority = 18,
        )

        val decoded = json.decodeFromString(
            AutomationRule.serializer(),
            json.encodeToString(AutomationRule.serializer(), rule),
        )

        assertEquals(rule, decoded)
        // Sealed-interface subtypes must survive, not degrade to a base type.
        assertTrue(decoded.trigger is RuleTrigger.DeviceConnected)
        assertTrue(decoded.conditions[1] is RuleCondition.Not)
        assertTrue(decoded.actions[2] is RuleAction.FadeVolume)
        assertEquals(3_000L, (decoded.actions[2] as RuleAction.FadeVolume).durationMs)
    }

    @Test
    fun `every trigger subtype is serializable`() {
        val triggers = listOf<RuleTrigger>(
            RuleTrigger.DeviceConnected("k"),
            RuleTrigger.DeviceDisconnected("k"),
            RuleTrigger.AppForeground("com.app"),
            RuleTrigger.AppAudioStarted("com.app"),
            RuleTrigger.TimeWindow(1380, 420, setOf(1, 2, 3)),
            RuleTrigger.BatteryBelow(20),
            RuleTrigger.DeviceBatteryBelow("k", 15),
            RuleTrigger.CallStateChanged(ringing = true, offHook = false),
            RuleTrigger.HeadsetPlugged(true),
            RuleTrigger.WifiNetwork("Home", true),
            RuleTrigger.VolumeChanged(AudioStream.MUSIC),
            RuleTrigger.ScreenOff,
            RuleTrigger.BootCompleted,
            RuleTrigger.Manual("Tap"),
        )
        triggers.forEach { trigger ->
            val decoded = json.decodeFromString(
                RuleTrigger.serializer(),
                json.encodeToString(RuleTrigger.serializer(), trigger),
            )
            assertEquals(trigger, decoded)
        }
    }

    @Test
    fun `every action subtype is serializable`() {
        val actions = listOf<RuleAction>(
            RuleAction.ActivateProfile("p"),
            RuleAction.SetStreamVolume(AudioStream.RING, 0.5f),
            RuleAction.SetMute(AudioStream.NOTIFICATION, true),
            RuleAction.RouteOutput("k"),
            RuleAction.RouteInput("k"),
            RuleAction.ApplyEqPreset("preset"),
            RuleAction.SetEqEnabled(true),
            RuleAction.SetAnc(0.8f),
            RuleAction.Notify("t", "m"),
            RuleAction.OpenApp,
            RuleAction.Delay(500),
            RuleAction.FadeVolume(AudioStream.MUSIC, 0.2f, 2000),
        )
        actions.forEach { action ->
            val decoded = json.decodeFromString(
                RuleAction.serializer(),
                json.encodeToString(RuleAction.serializer(), action),
            )
            assertEquals(action, decoded)
        }
    }

    @Test
    fun `unknown keys from a newer version are ignored rather than crashing`() {
        // Forward compatibility: a backup from a future build must still import.
        val futureJson = """
            {"enabled":true,"mode":"GRAPHIC_10","bands":[],"preampDb":0.0,
             "autoPreamp":true,"someFutureField":"whatever","anotherOne":42}
        """.trimIndent()
        val decoded = json.decodeFromString(EqSettings.serializer(), futureJson)
        assertTrue(decoded.enabled)
        assertEquals(EqMode.GRAPHIC_10, decoded.mode)
    }

    @Test
    fun `defaults fill in when fields are absent`() {
        val minimal = """{"id":"p","name":"Minimal"}"""
        val decoded = json.decodeFromString(AudioProfile.serializer(), minimal)
        assertEquals("Minimal", decoded.name)
        assertEquals(ProfileIcon.CUSTOM, decoded.icon)
        assertTrue(decoded.boundDeviceKeys.isEmpty())
        assertTrue(decoded.autoActivate)
    }
}

/** EQ band presets are stored by frequency/gain/Q, never by index. */
class EqSettingsTest {

    @Test
    fun `iso band grids have the documented sizes`() {
        assertEquals(31, EqSettings.ISO_31_BANDS.size)
        assertEquals(15, EqSettings.ISO_15_BANDS.size)
        assertEquals(10, EqSettings.ISO_10_BANDS.size)
    }

    @Test
    fun `iso grids are strictly ascending and inside the audible band`() {
        listOf(EqSettings.ISO_10_BANDS, EqSettings.ISO_15_BANDS, EqSettings.ISO_31_BANDS).forEach { grid ->
            grid.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", a < b) }
            assertTrue(grid.first() >= 20f)
            assertTrue(grid.last() <= 20_000f)
        }
    }

    @Test
    fun `flat preset has zero gain on every band`() {
        listOf(EqMode.GRAPHIC_10, EqMode.GRAPHIC_15, EqMode.GRAPHIC_31, EqMode.QUICK).forEach { mode ->
            val flat = EqSettings.flat(mode)
            assertEquals(EqSettings.frequenciesFor(mode).size, flat.bands.size)
            assertTrue(flat.bands.all { it.gainDb == 0f })
            assertTrue(flat.bands.all { it.enabled })
        }
    }

    @Test
    fun `quick mode uses shelves so bass and treble tilt rather than peak`() {
        val quick = EqSettings.flat(EqMode.QUICK)
        assertEquals(2, quick.bands.size)
        assertEquals(FilterType.LOW_SHELF, quick.bands[0].type)
        assertEquals(FilterType.HIGH_SHELF, quick.bands[1].type)
    }

    @Test
    fun `graphic q narrows as the band spacing narrows`() {
        val q31 = EqSettings.graphicQFor(EqMode.GRAPHIC_31)
        val q15 = EqSettings.graphicQFor(EqMode.GRAPHIC_15)
        val q10 = EqSettings.graphicQFor(EqMode.GRAPHIC_10)
        assertTrue("1/3-octave must be narrower than 1-octave", q31 > q15 && q15 > q10)
    }

    @Test
    fun `band sanitize clamps every parameter into a NaN-safe range`() {
        val insane = EqBand("x", FilterType.PEAK, frequencyHz = -900f, gainDb = 999f, q = -5f)
        val safe = insane.sanitized(48_000)
        assertTrue(safe.frequencyHz >= EqBand.MIN_FREQ)
        assertTrue(safe.gainDb <= EqBand.MAX_GAIN_DB)
        assertTrue(safe.q >= EqBand.MIN_Q)

        val tooHigh = EqBand("y", FilterType.PEAK, frequencyHz = 96_000f, gainDb = 0f, q = 1f)
        assertTrue("must clamp below Nyquist", tooHigh.sanitized(48_000).frequencyHz < 24_000f)
    }

    @Test
    fun `active bands exclude disabled ones`() {
        val settings = EqSettings(
            bands = listOf(
                EqBand("a", frequencyHz = 100f, enabled = true),
                EqBand("b", frequencyHz = 200f, enabled = false),
            ),
        )
        assertEquals(1, settings.activeBands.size)
        assertEquals("a", settings.activeBands.first().id)
    }

    @Test
    fun `parametric and off modes declare no fixed grid`() {
        assertTrue(EqSettings.frequenciesFor(EqMode.PARAMETRIC).isEmpty())
        assertTrue(EqSettings.frequenciesFor(EqMode.OFF).isEmpty())
    }
}
