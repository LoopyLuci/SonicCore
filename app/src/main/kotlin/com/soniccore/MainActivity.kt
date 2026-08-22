package com.soniccore

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.ui.theme.SonicCoreTheme
import com.soniccore.navigation.SonicCoreApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Results are reflected in each screen's capability checks. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by settingsStore.settings
                .collectAsStateWithLifecycle(
                    initialValue = com.soniccore.core.model.settings.AppSettings(),
                )

            SonicCoreTheme(
                themeMode = settings.themeMode,
                accentPalette = settings.accentPalette,
                useDynamicColor = settings.useDynamicColor,
            ) {
                SonicCoreApp(
                    startupScreen = settings.startupScreen,
                    onRequestNotificationAccess = ::openNotificationListenerSettings,
                    onRequestDndAccess = ::openDndAccessSettings,
                    onOpenOverlayAccess = ::openOverlaySettings,
                    onRequestMicPermission = ::requestMicrophonePermission,
                    onShareText = ::shareText,
                )
            }
        }

        /*
         * Request permissions AFTER setContent so the first frame is drawn before the
         * system dialog appears. Requesting first leaves users looking at a blank
         * window behind the dialog on first launch.
         */
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
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

    private fun requestMicrophonePermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
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
