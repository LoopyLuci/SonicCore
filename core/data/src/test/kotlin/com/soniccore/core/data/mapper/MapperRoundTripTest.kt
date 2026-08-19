package com.soniccore.core.data.mapper

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.CodecStrategy
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.effects.BassBoostSettings
import com.soniccore.core.model.effects.CrossfeedSettings
import com.soniccore.core.model.effects.DynamicsSettings
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqPreset
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import com.soniccore.core.model.profile.AppOverride
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.InputSettings
import com.soniccore.core.model.profile.OutputSettings
import com.soniccore.core.model.profile.ProfileIcon
import com.soniccore.core.model.profile.VolumeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entity mapping is where user data is most easily lost: the nested settings tree
 * is flattened into JSON columns, so a single missed field silently discards a
 * user's configuration on the next load.
 */
class MapperRoundTripTest {

    private fun fullProfile() = AudioProfile(
        id = "profile-1",
        name = "Mastering",
        icon = ProfileIcon.RECORDING,
        colorArgb = 0xFFEF476F.toInt(),
        description = "Flat with crossfeed",
        boundDeviceKeys = setOf("USB:OUTPUT:modi", "BLUETOOTH_CLASSIC:OUTPUT:AA:BB"),
        volume = VolumeSettings(
            streamPercents = mapOf(
                AudioStream.MUSIC to 0.55f,
                AudioStream.VOICE_CALL to 0.7f,
                AudioStream.ALARM to 0.4f,
            ),
            volumeLimitPercent = 0.85f,
            fadeInMs = 250,
            lockVolume = true,
            volumeKeyTarget = AudioStream.MUSIC,
            softwareGainDb = -1.5f,
            useSoftwareFineSteps = true,
            fineStepCount = 100,
        ),
        output = OutputSettings(
            targetDeviceKey = "USB:OUTPUT:modi",
            channelMode = ChannelMode.STEREO,
            balance = -0.15f,
            preferredSampleRate = 96_000,
            lowLatencyMode = true,
            codecStrategy = CodecStrategy.MAX_QUALITY,
            preferredCodec = BluetoothCodec.LDAC,
            codecBitrateKbps = 990,
            crossfadeSeconds = 2.5f,
            ancLevel = 0.8f,
            impedanceCompensationDb = 3f,
        ),
        input = InputSettings(
            micSource = MicSource.UNPROCESSED,
            gainDb = 6f,
            noiseGateEnabled = true,
            noiseGateThresholdDb = -46f,
            sidetoneEnabled = true,
            sidetoneLevel = 0.35f,
            deEsserEnabled = true,
            micEq = EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bands = listOf(EqBand("m1", FilterType.HIGH_PASS, 80f, 0f, 0.707f)),
            ),
        ),
        eq = EqSettings(
            enabled = true,
            mode = EqMode.PARAMETRIC,
            bands = listOf(
                EqBand("b1", FilterType.LOW_SHELF, 105f, -2.1f, 0.7f),
                EqBand("b2", FilterType.PEAK, 2_500f, 3.4f, 2.2f, enabled = false),
                EqBand("b3", FilterType.HIGH_SHELF, 10_000f, 1.8f, 0.7f),
            ),
            preampDb = -6.2f,
            autoPreamp = false,
            phaseLinear = true,
        ),
        effects = EffectsSettings(
            bassBoost = BassBoostSettings(enabled = true, strength = 0.4f, cutoffHz = 90f),
            crossfeed = CrossfeedSettings(enabled = true, amount = 0.25f, cutoffHz = 700f),
            dynamics = DynamicsSettings(compressorEnabled = true, ratio = 3.5f, thresholdDb = -20f),
            balance = 0.1f,
            stereoWidth = 1.2f,
        ),
        appOverrides = listOf(
            AppOverride("com.spotify.music", "Spotify", volumePercent = 0.7f, duckOthers = true),
            AppOverride("com.google.android.apps.maps", "Maps", muted = false, duckAmountDb = -14f),
        ),
        priority = 22,
        createdAtEpochMs = 1_700_000_000_000,
        modifiedAtEpochMs = 1_700_000_100_000,
        activationCount = 7,
        lastActivatedEpochMs = 1_700_000_200_000,
    )

    @Test
    fun `profile survives entity round trip with every nested field intact`() {
        val original = fullProfile()
        val restored = original.toEntity().toDomain()

        assertEquals(original, restored)
    }

    @Test
    fun `nested eq bands keep type frequency gain and q`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(3, restored.eq.bands.size)
        assertEquals(FilterType.LOW_SHELF, restored.eq.bands[0].type)
        assertEquals(105f, restored.eq.bands[0].frequencyHz, 0.001f)
        assertEquals(-2.1f, restored.eq.bands[0].gainDb, 0.001f)
        assertEquals(0.7f, restored.eq.bands[0].q, 0.001f)
        assertTrue("disabled band must stay disabled", !restored.eq.bands[1].enabled)
        assertEquals(-6.2f, restored.eq.preampDb, 0.001f)
        assertTrue(restored.eq.phaseLinear)
    }

    @Test
    fun `per stream volumes survive the map serialization`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(3, restored.volume.streamPercents.size)
        assertEquals(0.55f, restored.volume.streamPercents[AudioStream.MUSIC]!!, 0.001f)
        assertEquals(0.7f, restored.volume.streamPercents[AudioStream.VOICE_CALL]!!, 0.001f)
        assertEquals(0.85f, restored.volume.volumeLimitPercent!!, 0.001f)
        assertTrue(restored.volume.useSoftwareFineSteps)
    }

    @Test
    fun `bound device keys survive as a set`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(2, restored.boundDeviceKeys.size)
        assertTrue(restored.boundDeviceKeys.contains("USB:OUTPUT:modi"))
    }

    @Test
    fun `app overrides survive with their per app settings`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(2, restored.appOverrides.size)
        val spotify = restored.appOverrides.first { it.packageName == "com.spotify.music" }
        assertEquals(0.7f, spotify.volumePercent!!, 0.001f)
        assertTrue(spotify.duckOthers)
        val maps = restored.appOverrides.first { it.packageName.contains("maps") }
        assertEquals(-14f, maps.duckAmountDb, 0.001f)
    }

    @Test
    fun `mic eq nested two levels deep survives`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(1, restored.input.micEq.bands.size)
        assertEquals(FilterType.HIGH_PASS, restored.input.micEq.bands[0].type)
        assertEquals(MicSource.UNPROCESSED, restored.input.micSource)
        assertTrue(restored.input.deEsserEnabled)
    }

    @Test
    fun `output codec preferences survive`() {
        val restored = fullProfile().toEntity().toDomain()
        assertEquals(BluetoothCodec.LDAC, restored.output.preferredCodec)
        assertEquals(CodecStrategy.MAX_QUALITY, restored.output.codecStrategy)
        assertEquals(990, restored.output.codecBitrateKbps)
        assertEquals(96_000, restored.output.preferredSampleRate)
    }

    @Test
    fun `a minimal profile round trips without losing defaults`() {
        val minimal = AudioProfile(id = "p", name = "Bare")
        val restored = minimal.toEntity().toDomain()
        assertEquals(minimal, restored)
    }

    @Test
    fun `corrupt json in a column degrades to defaults instead of crashing`() {
        // A truncated write or a schema change must not make the app unopenable.
        val entity = fullProfile().toEntity().copy(
            eqJson = "{ this is not json",
            volumeJson = "",
            appOverridesJson = "[[[",
        )
        val restored = entity.toDomain()
        assertNotNull(restored)
        assertEquals("Mastering", restored.name)
        assertTrue("corrupt eq falls back to empty", restored.eq.bands.isEmpty())
        assertTrue("corrupt overrides fall back to empty", restored.appOverrides.isEmpty())
    }

    @Test
    fun `unknown enum name falls back rather than throwing`() {
        val entity = fullProfile().toEntity().copy(icon = "SOME_FUTURE_ICON")
        assertEquals(ProfileIcon.CUSTOM, entity.toDomain().icon)
    }

    @Test
    fun `device entity round trip preserves user authored fields`() {
        val device = AudioDevice(
            stableKey = "BLUETOOTH_CLASSIC:OUTPUT:AA:BB",
            systemId = 12,
            displayName = "WH-1000XM5",
            productName = "WH-1000XM5",
            address = "AA:BB",
            transport = DeviceTransport.BLUETOOTH_CLASSIC,
            kind = DeviceKind.HEADPHONES,
            direction = DeviceDirection.OUTPUT,
            capabilities = DeviceCapabilities(
                supportsOutput = true,
                sampleRates = listOf(44_100, 48_000, 96_000),
                channelCounts = listOf(2),
                supportsCodecSelection = true,
                supportsBatteryReporting = true,
            ),
            batteryPercent = 85,
            activeCodec = BluetoothCodec.LDAC,
            userLabel = "Desk cans",
            isFavorite = true,
            lastSeenEpochMs = 1_700_000_000_000,
        )

        val restored = device.toEntity(connectionCount = 5, notes = "Bought 2024").toDomain()

        assertEquals(device.stableKey, restored.stableKey)
        assertEquals("Desk cans", restored.userLabel)
        assertEquals("Desk cans", restored.label)
        assertTrue(restored.isFavorite)
        assertEquals(85, restored.batteryPercent)
        assertEquals(BluetoothCodec.LDAC, restored.activeCodec)
        assertEquals(listOf(44_100, 48_000, 96_000), restored.capabilities.sampleRates)
        assertTrue(restored.capabilities.supportsCodecSelection)
        // systemId is deliberately transient — it churns across reconnects.
        assertNull(restored.systemId)
    }

    @Test
    fun `eq preset round trip keeps frequency gain q rather than band indices`() {
        val preset = EqPreset(
            id = "preset-1",
            name = "Harman target",
            settings = EqSettings(
                enabled = true,
                mode = EqMode.PARAMETRIC,
                bands = listOf(
                    EqBand("1", FilterType.LOW_SHELF, 105f, 5.5f, 0.7f),
                    EqBand("2", FilterType.PEAK, 3_000f, -3.2f, 1.8f),
                ),
                preampDb = -5.5f,
            ),
            targetDeviceKey = "BLUETOOTH_CLASSIC:OUTPUT:AA",
            author = "AutoEQ",
            description = "Imported",
            createdAtEpochMs = 1_700_000_000_000,
        )

        val restored = preset.toEntity().toDomain()
        assertEquals(preset, restored)
        assertEquals(105f, restored.settings.bands[0].frequencyHz, 0.001f)
        assertEquals(-3.2f, restored.settings.bands[1].gainDb, 0.001f)
    }
}
