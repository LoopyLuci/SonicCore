package com.soniccore.core.data.environment

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.soniccore.core.model.environment.EnvironmentProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnvironmentProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _profile = MutableStateFlow(buildProfile())
    val profile: StateFlow<EnvironmentProfile> = _profile.asStateFlow()

    fun refresh() {
        _profile.value = buildProfile()
    }

    private fun buildProfile(): EnvironmentProfile {
        val pm = context.packageManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sdk = Build.VERSION.SDK_INT
        val versionName = Build.VERSION.RELEASE ?: "Unknown"

        val isHyperOsmIUI = run {
            val miuiVersion = try {
                pm.getPackageInfo("com.miui", 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
            miuiVersion.contains("MIUI", ignoreCase = true)
        }

        val manufacturer = Build.MANUFACTURER ?: "Unknown"
        val isSamsung = manufacturer.equals("Samsung", ignoreCase = true)
        val isXiaomi = manufacturer.equals("Xiaomi", ignoreCase = true) || isHyperOsmIUI
        val isOnePlus = manufacturer.equals("OnePlus", ignoreCase = true)

        val model = Build.MODEL ?: "Unknown"
        val product = Build.PRODUCT ?: "Unknown"

        val metrics = DisplayMetrics().also(wm.defaultDisplay::getMetrics)

        val hasNotificationAccess = runCatching {
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return@runCatching false
            enabledListeners.contains(context.packageName)
        }.getOrDefault(false)

        return EnvironmentProfile(
            androidSdkInt = sdk,
            androidVersionName = versionName,
            isAndroid13OrAbove = sdk >= Build.VERSION_CODES.TIRAMISU,
            isAndroid12OrAbove = sdk >= Build.VERSION_CODES.S,
            isAndroid11OrAbove = sdk >= Build.VERSION_CODES.R,
            isAndroid10OrAbove = sdk >= Build.VERSION_CODES.Q,

            manufacturer = manufacturer,
            model = model,
            deviceName = Build.DEVICE ?: model,
            product = product,
            isSamsung = isSamsung,
            isXiaomi = isXiaomi,
            isHyperOsmIUI = isHyperOsmIUI,
            isOnePlus = isOnePlus,
            isGooglePixel = manufacturer.equals("Google", ignoreCase = true),
            isStockAndroid = !isHyperOsmIUI && !isSamsung && !isOnePlus,

            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            screenDensityDpi = metrics.densityDpi,
            smallestScreenWidthDp = (metrics.widthPixels / metrics.density).toInt(),
            isSmallViewport = (metrics.widthPixels / metrics.density).toInt() < 600,
            screenLayoutSize = context.resources.configuration.screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK,

            hasBluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            hasBluetoothLe = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            hasUsbAudio = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
            hasMicrophone = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),

            hasNotificationAccess = hasNotificationAccess,
            hasOverlayPermission = runCatching {
                android.provider.Settings.canDrawOverlays(context)
            }.getOrDefault(false),
            hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            hasBluetoothConnectPermission = if (sdk >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true,
            hasBluetoothScanPermission = if (sdk >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else true,
            hasPostNotificationsPermission = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true,

            connectedAudioDeviceCount = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_ALL).count {
                    it.type in AUDIO_DEVICE_TYPES
                }
            }.getOrDefault(0),
            hasBluetoothAudioConnected = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_ALL).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(false),
            hasUsbAudioConnected = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_ALL).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                }
            }.getOrDefault(false),
            hasWiredHeadsetConnected = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_ALL).any {
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
            }.getOrDefault(false),
            featureFlags = computeFeatureFlags(sdk, hasNotificationAccess, isHyperOsmIUI),
        )

        Log.d("EnvironmentProvider", "profile: ${_profile.value}")
    }

    private fun computeFeatureFlags(
        sdk: Int,
        hasNotificationAccess: Boolean,
        isHyperOsmIUI: Boolean,
    ): Set<EnvironmentProfile.FeatureFlag> {
        val flags = mutableSetOf<EnvironmentProfile.FeatureFlag>()
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            flags += EnvironmentProfile.FeatureFlag.NotificationsRequired
        }
        if (sdk >= Build.VERSION_CODES.S) {
            flags += EnvironmentProfile.FeatureFlag.BluetoothRuntimePermissions
        }
        if (sdk >= Build.VERSION_CODES.O) {
            flags += EnvironmentProfile.FeatureFlag.SetCommunicationDevice
        }
        if (sdk >= Build.VERSION_CODES.M) {
            flags += EnvironmentProfile.FeatureFlag.PreferredDeviceRouting
        }
        if (sdk >= Build.VERSION_CODES.O) {
            flags += EnvironmentProfile.FeatureFlag.BleAudio
        }
        if (sdk < Build.VERSION_CODES.S) {
            flags += EnvironmentProfile.FeatureFlag.LegacyScoRouting
        }
        if (hasNotificationAccess) {
            flags += EnvironmentProfile.FeatureFlag.PerAppMixer
        }
        if (isHyperOsmIUI) {
            flags += EnvironmentProfile.FeatureFlag.AutomationOverlayOptional
        }
        return flags
    }

    companion object {
        private val AUDIO_DEVICE_TYPES = setOf(
            android.media.AudioDeviceInfo.TYPE_AUX_LINE,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG,
            android.media.AudioDeviceInfo.TYPE_HDMI,
            android.media.AudioDeviceInfo.TYPE_HDMI_ARC,
            android.media.AudioDeviceInfo.TYPE_HEARING_AID,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
