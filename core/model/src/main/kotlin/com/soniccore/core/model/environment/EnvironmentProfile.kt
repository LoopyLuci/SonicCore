package com.soniccore.core.model.environment

data class EnvironmentProfile(
    val androidSdkInt: Int,
    val androidVersionName: String,
    val isAndroid13OrAbove: Boolean,
    val isAndroid12OrAbove: Boolean,
    val isAndroid11OrAbove: Boolean,
    val isAndroid10OrAbove: Boolean,

    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val product: String,
    val isSamsung: Boolean,
    val isXiaomi: Boolean,
    val isHyperOsmIUI: Boolean,
    val isOnePlus: Boolean,
    val isGooglePixel: Boolean,
    val isStockAndroid: Boolean,

    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val screenDensityDpi: Int,
    val smallestScreenWidthDp: Int,
    val screenLayoutSize: Int,
    val isSmallViewport: Boolean = false,

    val hasBluetooth: Boolean,
    val hasBluetoothLe: Boolean,
    val hasUsbAudio: Boolean,
    val hasWifi: Boolean,
    val hasMicrophone: Boolean,

    val hasNotificationAccess: Boolean,
    val hasOverlayPermission: Boolean,
    val hasRecordAudioPermission: Boolean,
    val hasBluetoothConnectPermission: Boolean,
    val hasBluetoothScanPermission: Boolean,
    val hasPostNotificationsPermission: Boolean,

    val connectedAudioDeviceCount: Int,
    val hasBluetoothAudioConnected: Boolean,
    val hasUsbAudioConnected: Boolean,
    val hasWiredHeadsetConnected: Boolean,

    val featureFlags: Set<FeatureFlag> = emptySet(),
) {

    enum class FeatureFlag {
        /** Runtime notifications are required (API 33+) */
        NotificationsRequired,
        /** BLUETOOTH_CONNECT/SCAN are runtime permissions (API 31+) */
        BluetoothRuntimePermissions,
        /** setCommunicationDevice is available (API 26+) */
        SetCommunicationDevice,
        /** AudioTrack preferred device routing (API 23+) */
        PreferredDeviceRouting,
        /** BLE audio profiles available (API 26+) */
        BleAudio,
        /** Voice call SCO routing (pre-API 31) */
        LegacyScoRouting,
        /** Per-app audio mixing via notification listener */
        PerAppMixer,
        /** Automation requires overlay permission (cross-OEM) */
        AutomationOverlayOptional,
    }

    companion object {
        val Empty = EnvironmentProfile(
            androidSdkInt = 0,
            androidVersionName = "Unknown",
            isAndroid13OrAbove = false,
            isAndroid12OrAbove = false,
            isAndroid11OrAbove = false,
            isAndroid10OrAbove = false,
            manufacturer = "Unknown",
            model = "Unknown",
            deviceName = "Unknown",
            product = "Unknown",
            isSamsung = false,
            isXiaomi = false,
            isHyperOsmIUI = false,
            isOnePlus = false,
            isGooglePixel = false,
            isStockAndroid = false,
            screenWidthPx = 0,
            screenHeightPx = 0,
            screenDensityDpi = 0,
            smallestScreenWidthDp = 0,
            screenLayoutSize = 0,
            hasBluetooth = false,
            hasBluetoothLe = false,
            hasUsbAudio = false,
            hasWifi = false,
            hasMicrophone = false,
            hasNotificationAccess = false,
            hasOverlayPermission = false,
            hasRecordAudioPermission = false,
            hasBluetoothConnectPermission = false,
            hasBluetoothScanPermission = false,
            hasPostNotificationsPermission = false,
            connectedAudioDeviceCount = 0,
            hasBluetoothAudioConnected = false,
            hasUsbAudioConnected = false,
            hasWiredHeadsetConnected = false,
        )
    }
}
