package com.soniccore.core.audio.device

import android.media.AudioDeviceInfo

object DeviceNamingPolicy {

    fun displayNameFor(
        info: AudioDeviceInfo,
        productName: String?,
        wifiProtocol: com.soniccore.core.model.device.WifiProtocol?,
    ): String {
        val sanitized = sanitize(productName)
        val fallback = when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_FM -> "FM radio"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Network speaker"
            else -> "Audio device"
        }
        val isBuiltIn = info.type in setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
        return if (isBuiltIn || sanitized == null) fallback else sanitized
    }

    fun friendlyNameForBluetooth(name: String?, address: String?): String {
        val sanitized = sanitize(name)
        return when {
            sanitized != null && !sanitized.equals(address, ignoreCase = true) -> sanitized
            else -> "Bluetooth speaker"
        }
    }

    fun friendlyNameForWifi(
        rawName: String?,
        ipAddress: String?,
        protocol: com.soniccore.core.model.device.WifiProtocol?,
    ): String {
        val sanitized = sanitize(rawName)
        val looksLikeIdentifier = sanitized == null ||
            sanitized.matches(Regex("^[0-9A-Fa-f]{8,}$")) ||
            sanitized.matches(Regex("^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$")) ||
            sanitized.equals(ipAddress, ignoreCase = true) ||
            sanitized.matches(Regex("^[A-Z0-9]{6,}$")) ||
            sanitized.matches(Regex("^[0-9]{6,}$"))

        return when {
            sanitized != null && !looksLikeIdentifier -> sanitized
            protocol != null -> protocol.displayName
            else -> "Network speaker"
        }
    }

    private fun sanitize(name: String?): String? {
        val raw = name
            ?.replace(Regex("""\b([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\b"""), "")
            ?.replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "")
            ?.replace(Regex("""^[A-Z0-9]{6,}$"""), "")
            ?.replace(Regex("""^[0-9]{6,}$"""), "")
            ?.replace('-', ' ')
            ?.replace('_', ' ')
            ?.trim()
        return raw?.takeIf { it.isNotBlank() }
    }
}
