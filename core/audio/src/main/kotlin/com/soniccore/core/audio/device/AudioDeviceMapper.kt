package com.soniccore.core.audio.device

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Build
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.AudioEncoding
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport

/**
 * Translates platform [AudioDeviceInfo] into the domain model.
 *
 * `AudioManager.getDevices()` is the single source of truth for every wired, USB,
 * Bluetooth and HDMI endpoint — per-transport enumeration is unnecessary and
 * inconsistent. WiFi/Cast endpoints are *not* reported here and are discovered
 * separately over mDNS.
 */
object AudioDeviceMapper {

    fun map(info: AudioDeviceInfo, isActiveOutput: Boolean = false, isActiveInput: Boolean = false): AudioDevice {
        val transport = transportFor(info.type)
        val direction = when {
            info.isSink && info.isSource -> DeviceDirection.BIDIRECTIONAL
            info.isSource -> DeviceDirection.INPUT
            else -> DeviceDirection.OUTPUT
        }
        val productName = info.productName?.toString()?.takeIf { it.isNotBlank() }
        val address = addressOf(info)

        val capabilities = DeviceCapabilities(
            supportsOutput = info.isSink,
            supportsInput = info.isSource,
            channelCounts = info.channelCounts.toList().filter { it > 0 }.ifEmpty { listOf(2) },
            sampleRates = info.sampleRates.toList().filter { it > 0 }.ifEmpty { listOf(48_000) },
            encodings = info.encodings.toList().mapNotNull { encodingFor(it) },
            hasHardwareVolume = transport != DeviceTransport.WIFI,
            supportsSpatialAudio = supportsSpatial(info.type),
            supportsCodecSelection = transport == DeviceTransport.BLUETOOTH_CLASSIC,
            supportsAnc = isEarworn(info.type),
            supportsTransparency = isEarworn(info.type),
            supportsBatteryReporting = transport == DeviceTransport.BLUETOOTH_CLASSIC ||
                transport == DeviceTransport.BLUETOOTH_LE,
            supportsLowLatencyMode = transport != DeviceTransport.WIFI,
        )

        return AudioDevice(
            stableKey = AudioDevice.buildStableKey(transport, address, productName, direction),
            systemId = info.id,
            displayName = displayNameFor(info, productName),
            productName = productName,
            address = address,
            transport = transport,
            kind = kindFor(info.type),
            direction = direction,
            capabilities = capabilities,
            connectionState = when {
                isActiveOutput || isActiveInput -> ConnectionState.ACTIVE
                else -> ConnectionState.CONNECTED
            },
            lastSeenEpochMs = System.currentTimeMillis(),
        )
    }

    private fun addressOf(info: AudioDeviceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.address.takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun displayNameFor(info: AudioDeviceInfo, productName: String?): String {
        val fallback = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_FM -> "FM radio"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
            else -> "Audio device"
        }
        // productName is often just the phone model for built-ins; prefer our label then.
        val isBuiltIn = info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            info.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
            info.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        return if (isBuiltIn || productName == null) fallback else productName
    }

    fun transportFor(type: Int): DeviceTransport = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> DeviceTransport.ANALOG_35MM

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> DeviceTransport.USB

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> DeviceTransport.BLUETOOTH_CLASSIC

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> DeviceTransport.HDMI

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_TELEPHONY,
        -> DeviceTransport.BUILTIN

        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> DeviceTransport.WIFI

        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isBleType(type) -> DeviceTransport.BLUETOOTH_LE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_HDMI_EARC -> DeviceTransport.HDMI
            else -> DeviceTransport.UNKNOWN
        }
    }

    private fun isBleType(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
    }

    fun kindFor(type: Int): DeviceKind = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> DeviceKind.HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> DeviceKind.HEADPHONES
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> DeviceKind.HEADPHONES
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> DeviceKind.HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> DeviceKind.DAC
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DeviceKind.PHONE_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> DeviceKind.PHONE_MIC
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> DeviceKind.EARPIECE
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> DeviceKind.TV
        AudioDeviceInfo.TYPE_DOCK -> DeviceKind.SPEAKER
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> DeviceKind.SPEAKER
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> DeviceKind.EARBUDS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> DeviceKind.SPEAKER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID -> DeviceKind.HEARING_AID
            else -> DeviceKind.UNKNOWN
        }
    }

    private fun isEarworn(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun supportsSpatial(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    fun encodingFor(encoding: Int): AudioEncoding? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> AudioEncoding.PCM_8BIT
        AudioFormat.ENCODING_PCM_16BIT -> AudioEncoding.PCM_16BIT
        AudioFormat.ENCODING_PCM_FLOAT -> AudioEncoding.PCM_FLOAT
        AudioFormat.ENCODING_AC3 -> AudioEncoding.AC3
        AudioFormat.ENCODING_E_AC3 -> AudioEncoding.E_AC3
        AudioFormat.ENCODING_DTS -> AudioEncoding.DTS
        AudioFormat.ENCODING_DTS_HD -> AudioEncoding.DTS_HD
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED -> AudioEncoding.PCM_24BIT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                encoding == AudioFormat.ENCODING_PCM_32BIT -> AudioEncoding.PCM_32BIT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                encoding == AudioFormat.ENCODING_E_AC3_JOC -> AudioEncoding.DOLBY_ATMOS
            else -> null
        }
    }
}
