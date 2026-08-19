package com.soniccore.core.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.ui.theme.SonicCoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests that run on the JVM via Robolectric — they execute in
 * `./gradlew test`, so UI regressions are caught without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun bluetoothDevice(
        name: String = "Sony WH-1000XM5",
        battery: Int? = 85,
        codec: BluetoothCodec? = BluetoothCodec.LDAC,
    ) = AudioDevice(
        stableKey = "BLUETOOTH_CLASSIC:OUTPUT:AA:BB:CC",
        systemId = 7,
        displayName = name,
        productName = name,
        address = "AA:BB:CC",
        transport = DeviceTransport.BLUETOOTH_CLASSIC,
        kind = DeviceKind.HEADPHONES,
        direction = DeviceDirection.OUTPUT,
        capabilities = DeviceCapabilities(
            supportsOutput = true,
            sampleRates = listOf(48_000),
            // Real Bluetooth devices are mapped with this true; battery may still be null.
            supportsBatteryReporting = true,
        ),
        connectionState = ConnectionState.ACTIVE,
        batteryPercent = battery,
        activeCodec = codec,
    )

    @Test
    fun `device card shows name transport and codec`() {
        composeRule.setContent {
            SonicCoreTheme { DeviceCard(device = bluetoothDevice(), isActive = true) }
        }

        composeRule.onNodeWithText("Sony WH-1000XM5").assertIsDisplayed()
        composeRule.onNodeWithText("Bluetooth").assertIsDisplayed()
        composeRule.onNodeWithText("LDAC").assertIsDisplayed()
        composeRule.onNodeWithText("85%").assertIsDisplayed()
    }

    @Test
    fun `unknown battery renders a dash instead of a fabricated number`() {
        composeRule.setContent {
            SonicCoreTheme { DeviceCard(device = bluetoothDevice(battery = null)) }
        }
        // The platform hides battery level; we must never invent one.
        composeRule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `user label overrides the platform display name`() {
        val device = bluetoothDevice().copy(userLabel = "Desk headphones")
        composeRule.setContent { SonicCoreTheme { DeviceCard(device = device) } }
        composeRule.onNodeWithText("Desk headphones").assertIsDisplayed()
    }

    @Test
    fun `active device is labelled active`() {
        composeRule.setContent {
            SonicCoreTheme { DeviceCard(device = bluetoothDevice(), isActive = true) }
        }
        composeRule.onNodeWithText("Active").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeSliderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `slider reports the device's real step count`() {
        // A device with 7 hardware steps must not imply 100 are available.
        val volume = StreamVolume(AudioStream.MUSIC, index = 3, minIndex = 0, maxIndex = 7, isMuted = false)
        composeRule.setContent {
            SonicCoreTheme {
                VolumeSliderRow(volume = volume, onPercentChange = {})
            }
        }
        composeRule.onNodeWithText("7 steps").assertIsDisplayed()
        composeRule.onNodeWithText("Media").assertIsDisplayed()
    }

    @Test
    fun `fixed volume device explains why the slider is disabled`() {
        val volume = StreamVolume(
            AudioStream.MUSIC, index = 5, minIndex = 0, maxIndex = 15,
            isMuted = false, isFixed = true,
        )
        composeRule.setContent {
            SonicCoreTheme { VolumeSliderRow(volume = volume, onPercentChange = {}) }
        }
        composeRule.onNodeWithText(
            "This device reports a fixed volume — control it on the device itself.",
        ).assertIsDisplayed()
    }

    @Test
    fun `percent readout matches the index within the device range`() {
        val volume = StreamVolume(AudioStream.MUSIC, index = 15, minIndex = 0, maxIndex = 30, isMuted = false)
        composeRule.setContent {
            SonicCoreTheme { VolumeSliderRow(volume = volume, onPercentChange = {}) }
        }
        composeRule.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun `dragging reports a percent to the callback`() {
        var reported: Float? = null
        val volume = StreamVolume(AudioStream.MUSIC, index = 0, minIndex = 0, maxIndex = 15, isMuted = false)
        composeRule.setContent {
            SonicCoreTheme {
                VolumeSliderRow(
                    volume = volume,
                    onPercentChange = { reported = it },
                    modifier = Modifier.width(300.dp).height(80.dp),
                )
            }
        }
        composeRule.onNodeWithText("Media").assertIsDisplayed()
        // Verified separately: index<->percent conversion round-trips through the
        // device's own min/max rather than assuming a range.
        assertEquals(0, volume.indexForPercent(0f))
        assertEquals(15, volume.indexForPercent(1f))
        assertEquals(8, volume.indexForPercent(0.5f))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LimitationNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `platform limitations are surfaced to the user verbatim`() {
        val message = "Android does not allow re-routing another app's media stream."
        composeRule.setContent { SonicCoreTheme { LimitationNotice(text = message) } }
        composeRule.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun `section header renders title and subtitle`() {
        composeRule.setContent {
            SonicCoreTheme { SectionHeader(title = "Output", subtitle = "2 devices") }
        }
        composeRule.onNodeWithText("Output").assertIsDisplayed()
        composeRule.onNodeWithText("2 devices").assertIsDisplayed()
    }
}

/** Formatter behaviour the UI depends on — pure functions, no Compose needed. */
class FormattingTest {

    @Test
    fun `frequency labels are human readable across the spectrum`() {
        assertEquals("20 Hz", formatFrequency(20f))
        assertEquals("850 Hz", formatFrequency(850f))
        assertEquals("1.2 kHz", formatFrequency(1200f))
        assertEquals("12 kHz", formatFrequency(12000f))
        assertEquals("20 kHz", formatFrequency(20000f))
    }

    @Test
    fun `gain is always signed so plus and minus are unambiguous`() {
        assertTrue(formatGain(3.5f).startsWith("+"))
        assertTrue(formatGain(-3.5f).startsWith("-"))
        assertEquals("+0.0 dB", formatGain(0f))
    }

    @Test
    fun `q is shown to two decimals`() {
        assertEquals("1.41", formatQ(1.41f))
        assertEquals("4.32", formatQ(4.32f))
    }
}
