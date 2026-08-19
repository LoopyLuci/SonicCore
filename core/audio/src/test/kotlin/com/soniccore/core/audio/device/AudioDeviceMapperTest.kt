package com.soniccore.core.audio.device

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import com.soniccore.core.model.device.AudioEncoding
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transport matrix is the foundation of the whole app: a mis-mapped
 * `AudioDeviceInfo.type` puts a device in the wrong section, gives it the wrong
 * capabilities, and breaks profile binding.
 *
 * These test the pure mapping functions directly — no Android instance needed for
 * the type lookups, which is why they run fast on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDeviceMapperTest {

    @Test
    fun `analog types map to the 3_5mm transport`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_AUX_LINE,
        ).forEach { type ->
            assertEquals(
                "type $type should be analog",
                DeviceTransport.ANALOG_35MM,
                AudioDeviceMapper.transportFor(type),
            )
        }
    }

    @Test
    fun `usb types map to the usb transport`() {
        listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        ).forEach { type ->
            assertEquals(DeviceTransport.USB, AudioDeviceMapper.transportFor(type))
        }
    }

    @Test
    fun `bluetooth a2dp and sco both map to classic bluetooth`() {
        assertEquals(
            DeviceTransport.BLUETOOTH_CLASSIC,
            AudioDeviceMapper.transportFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertEquals(
            DeviceTransport.BLUETOOTH_CLASSIC,
            AudioDeviceMapper.transportFor(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
    }

    @Test
    fun `ble audio types map to the le transport on supported api levels`() {
        // BLE_HEADSET/BLE_SPEAKER exist from API 31; the mapper guards by SDK_INT.
        assertEquals(
            DeviceTransport.BLUETOOTH_LE,
            AudioDeviceMapper.transportFor(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        assertEquals(
            DeviceTransport.BLUETOOTH_LE,
            AudioDeviceMapper.transportFor(AudioDeviceInfo.TYPE_BLE_SPEAKER),
        )
    }

    @Test
    fun `hdmi and digital line map to hdmi`() {
        listOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        ).forEach { type ->
            assertEquals(DeviceTransport.HDMI, AudioDeviceMapper.transportFor(type))
        }
    }

    @Test
    fun `built in types map to builtin`() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_TELEPHONY,
        ).forEach { type ->
            assertEquals(DeviceTransport.BUILTIN, AudioDeviceMapper.transportFor(type))
        }
    }

    @Test
    fun `remote submix is treated as a network sink`() {
        // Cast/virtual sinks surface as REMOTE_SUBMIX.
        assertEquals(
            DeviceTransport.WIFI,
            AudioDeviceMapper.transportFor(AudioDeviceInfo.TYPE_REMOTE_SUBMIX),
        )
    }

    @Test
    fun `unrecognised type degrades to unknown instead of crashing`() {
        assertEquals(DeviceTransport.UNKNOWN, AudioDeviceMapper.transportFor(9999))
        assertEquals(DeviceTransport.UNKNOWN, AudioDeviceMapper.transportFor(-1))
    }

    @Test
    fun `device kinds are distinguished for iconography`() {
        assertEquals(DeviceKind.HEADSET, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(DeviceKind.HEADPHONES, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(DeviceKind.DAC, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(DeviceKind.HEADSET, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(DeviceKind.PHONE_SPEAKER, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(DeviceKind.PHONE_MIC, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(DeviceKind.EARPIECE, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(DeviceKind.TV, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(DeviceKind.EARBUDS, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(DeviceKind.UNKNOWN, AudioDeviceMapper.kindFor(9999))
    }

    @Test
    fun `wired headset is a headset but headphones are not`() {
        // HEADSET implies a microphone; HEADPHONES does not. This drives whether we
        // offer mic settings for the device at all.
        assertEquals(DeviceKind.HEADSET, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(DeviceKind.HEADPHONES, AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
    }

    @Test
    fun `pcm encodings map to their bit depths`() {
        assertEquals(AudioEncoding.PCM_8BIT, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_PCM_8BIT))
        assertEquals(AudioEncoding.PCM_16BIT, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_PCM_16BIT))
        assertEquals(AudioEncoding.PCM_FLOAT, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_PCM_FLOAT))
        assertEquals(16, AudioEncoding.PCM_16BIT.bitDepth)
        assertEquals(32, AudioEncoding.PCM_FLOAT.bitDepth)
    }

    @Test
    fun `surround encodings are recognised`() {
        assertEquals(AudioEncoding.AC3, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_AC3))
        assertEquals(AudioEncoding.E_AC3, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_E_AC3))
        assertEquals(AudioEncoding.DTS, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_DTS))
        assertEquals(AudioEncoding.DTS_HD, AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_DTS_HD))
    }

    @Test
    fun `high resolution pcm encodings map on api 31 plus`() {
        assertEquals(
            AudioEncoding.PCM_24BIT,
            AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_PCM_24BIT_PACKED),
        )
        assertEquals(
            AudioEncoding.PCM_32BIT,
            AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_PCM_32BIT),
        )
    }

    @Test
    fun `atmos joc encoding is detected`() {
        assertEquals(
            AudioEncoding.DOLBY_ATMOS,
            AudioDeviceMapper.encodingFor(AudioFormat.ENCODING_E_AC3_JOC),
        )
    }

    @Test
    fun `unknown encoding returns null so it can be filtered out`() {
        // Returning null rather than UNKNOWN keeps invented formats out of the UI.
        assertNull(AudioDeviceMapper.encodingFor(999_999))
    }

    @Test
    fun `every audio device type in the platform maps to some transport`() {
        // Guard against a future SDK adding a type we silently mishandle: every
        // known constant must resolve to something other than a crash.
        val allTypes = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER,
            AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_IP, AudioDeviceInfo.TYPE_BUS,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        allTypes.forEach { type ->
            val transport = AudioDeviceMapper.transportFor(type)
            val kind = AudioDeviceMapper.kindFor(type)
            assertTrue("type $type produced no transport", transport in DeviceTransport.entries)
            assertTrue("type $type produced no kind", kind in DeviceKind.entries)
        }
    }

    @Test
    fun `hearing aid is recognised as its own kind`() {
        assertEquals(
            DeviceKind.HEARING_AID,
            AudioDeviceMapper.kindFor(AudioDeviceInfo.TYPE_HEARING_AID),
        )
    }
}
