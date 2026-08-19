package com.soniccore.core.model.device

import kotlinx.serialization.Serializable

/**
 * Physical/logical transport an audio endpoint speaks over.
 * Maps onto AudioDeviceInfo.TYPE_* plus network transports the platform
 * does not enumerate (Cast/AirPlay/DLNA/Sonos are discovered by us).
 */
@Serializable
enum class DeviceTransport {
    ANALOG_35MM,
    USB,
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    WIFI,
    HDMI,
    BUILTIN,
    UNKNOWN,
}

/** Direction(s) an endpoint supports. */
@Serializable
enum class DeviceDirection { OUTPUT, INPUT, BIDIRECTIONAL }

/** Fine-grained device class used for iconography and capability defaults. */
@Serializable
enum class DeviceKind {
    HEADPHONES,
    HEADSET,
    EARBUDS,
    SPEAKER,
    SOUNDBAR,
    DAC,
    AUDIO_INTERFACE,
    MICROPHONE,
    HEARING_AID,
    CAR_AUDIO,
    TV,
    PHONE_SPEAKER,
    PHONE_MIC,
    EARPIECE,
    UNKNOWN,
}

@Serializable
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ACTIVE, ERROR }

/** Bluetooth A2DP/LE codec identifiers. */
@Serializable
enum class BluetoothCodec(val displayName: String, val maxBitrateKbps: Int?) {
    SBC("SBC", 328),
    AAC("AAC", 320),
    APTX("aptX", 384),
    APTX_HD("aptX HD", 576),
    APTX_ADAPTIVE("aptX Adaptive", 420),
    APTX_LOSSLESS("aptX Lossless", 1200),
    LDAC("LDAC", 990),
    LC3("LC3", 345),
    LHDC("LHDC", 900),
    SSC("Samsung Scalable", 512),
    OPUS("Opus", 510),
    UNKNOWN("Unknown", null),
}

/** USB Audio Class revision. */
@Serializable
enum class UsbAudioClass(val displayName: String) {
    UAC1("UAC 1.0"),
    UAC2("UAC 2.0"),
    UAC3("UAC 3.0"),
    UNKNOWN("Unknown"),
}

/** Network protocol for WiFi endpoints. */
@Serializable
enum class WifiProtocol(val displayName: String, val serviceType: String) {
    CHROMECAST("Chromecast", "_googlecast._tcp"),
    AIRPLAY("AirPlay", "_raop._tcp"),
    SONOS("Sonos", "_sonos._tcp"),
    DLNA("DLNA/UPnP", "_upnp._tcp"),
    SPOTIFY_CONNECT("Spotify Connect", "_spotify-connect._tcp"),
    GENERIC("Network Speaker", "_http._tcp"),
}

/**
 * What an endpoint can actually do. Populated from AudioDeviceInfo where
 * available; unknown fields stay null rather than being guessed.
 */
@Serializable
data class DeviceCapabilities(
    val supportsOutput: Boolean = false,
    val supportsInput: Boolean = false,
    val channelCounts: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
    val encodings: List<AudioEncoding> = emptyList(),
    val hasHardwareVolume: Boolean = false,
    val volumeIsFixed: Boolean = false,
    val supportsSpatialAudio: Boolean = false,
    val supportsCodecSelection: Boolean = false,
    val supportsAnc: Boolean = false,
    val supportsTransparency: Boolean = false,
    val supportsBatteryReporting: Boolean = false,
    val supportsLowLatencyMode: Boolean = false,
    val maxChannelCount: Int = channelCounts.maxOrNull() ?: 2,
) {
    val isBidirectional: Boolean get() = supportsOutput && supportsInput
}

@Serializable
enum class AudioEncoding(val displayName: String, val bitDepth: Int?) {
    PCM_8BIT("PCM 8-bit", 8),
    PCM_16BIT("PCM 16-bit", 16),
    PCM_24BIT("PCM 24-bit", 24),
    PCM_32BIT("PCM 32-bit", 32),
    PCM_FLOAT("PCM Float", 32),
    AC3("Dolby Digital", null),
    E_AC3("Dolby Digital Plus", null),
    DTS("DTS", null),
    DTS_HD("DTS-HD", null),
    DOLBY_ATMOS("Dolby Atmos", null),
    DSD("DSD", null),
    UNKNOWN("Unknown", null),
}

/**
 * A single audio endpoint. [stableKey] is what profiles bind to — the platform
 * `systemId` churns across reconnects, so it must never be persisted as identity.
 */
@Serializable
data class AudioDevice(
    val stableKey: String,
    val systemId: Int?,
    val displayName: String,
    val productName: String?,
    val address: String?,
    val transport: DeviceTransport,
    val kind: DeviceKind,
    val direction: DeviceDirection,
    val capabilities: DeviceCapabilities,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryPercent: Int? = null,
    val secondaryBatteryPercent: Int? = null,
    val caseBatteryPercent: Int? = null,
    val activeCodec: BluetoothCodec? = null,
    val availableCodecs: List<BluetoothCodec> = emptyList(),
    val usbAudioClass: UsbAudioClass? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val wifiProtocol: WifiProtocol? = null,
    val ipAddress: String? = null,
    val measuredLatencyMs: Int? = null,
    val currentSampleRate: Int? = null,
    val currentEncoding: AudioEncoding? = null,
    val userLabel: String? = null,
    val isFavorite: Boolean = false,
    val lastSeenEpochMs: Long = 0L,
) {
    val label: String get() = userLabel ?: displayName

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.ACTIVE

    val isWireless: Boolean
        get() = transport == DeviceTransport.BLUETOOTH_CLASSIC ||
            transport == DeviceTransport.BLUETOOTH_LE ||
            transport == DeviceTransport.WIFI

    val canOutput: Boolean get() = capabilities.supportsOutput
    val canInput: Boolean get() = capabilities.supportsInput

    companion object {
        /**
         * Identity that survives reconnects: transport + hardware address when we
         * have one, else transport + product name.
         */
        fun buildStableKey(
            transport: DeviceTransport,
            address: String?,
            productName: String?,
            direction: DeviceDirection,
        ): String {
            val discriminator = address?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }
                ?: productName?.takeIf { it.isNotBlank() }
                ?: "unknown"
            return "${transport.name}:${direction.name}:$discriminator"
        }
    }
}
