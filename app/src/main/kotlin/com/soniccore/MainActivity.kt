package com.soniccore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.ui.theme.SonicCoreTheme
import com.soniccore.navigation.SonicCoreApp
import com.soniccore.permission.PermissionGrantScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.all { it.value }
        hasPermissions.value = allGranted
    }

    private val hasPermissions = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsStore.settings
                .collectAsStateWithLifecycle(
                    initialValue = com.soniccore.core.model.settings.AppSettings(),
                )

            PermissionGateScaffold(
                hasPermissions = hasPermissions.value,
                onPermissionsChanged = { hasPermissions.value = it },
                settings = settings,
                onRequestRuntimePermissions = ::requestRuntimePermissions,
                onOpenNotificationListenerSettings = ::openNotificationListenerSettings,
                onOpenDndAccessSettings = ::openDndAccessSettings,
                onOpenOverlaySettings = ::openOverlaySettings,
                onShareText = ::shareText,
            )
        }

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        hasPermissions.value = checkAllPermissionsGranted()
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    /** Per-app mixer needs notification-listener access; only the user can grant it. */
    private fun openNotificationListenerSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** Ring/notification volume while DND is active needs policy access. */
    private fun openDndAccessSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    /**
     * Deep-links to the app's "Display over other apps" screen. This is the permission that
     * lets the automation "Open app" action run on phones (HyperOS/MIUI, some Samsung/OnePlus)
     * whose battery managers block background activity starts.
     */
    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun shareText(text: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, null))
        }
    }
}

@Composable
private fun PermissionGateScaffold(
    hasPermissions: Boolean,
    onPermissionsChanged: (Boolean) -> Unit = {},
    settings: com.soniccore.core.model.settings.AppSettings,
    onRequestRuntimePermissions: () -> Unit = {},
    onOpenNotificationListenerSettings: () -> Unit = {},
    onOpenDndAccessSettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    onShareText: (String) -> Unit = {},
) {
    SonicCoreTheme(
        themeMode = settings.themeMode,
        accentPalette = settings.accentPalette,
        useDynamicColor = settings.useDynamicColor,
    ) {
        if (hasPermissions) {
            SonicCoreApp(
                startupScreen = settings.startupScreen,
                onRequestNotificationAccess = onOpenNotificationListenerSettings,
                onRequestDndAccess = onOpenDndAccessSettings,
                onOpenOverlayAccess = onOpenOverlaySettings,
                onRequestMicPermission = onRequestRuntimePermissions,
                onShareText = onShareText,
            )
        } else {
            PermissionGrantScreen(
                onGrantClicked = onRequestRuntimePermissions,
                onContinueAnyway = { onPermissionsChanged(true) },
            )
        }
    }
}
