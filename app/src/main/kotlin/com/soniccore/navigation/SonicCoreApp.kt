package com.soniccore.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.soniccore.core.model.settings.StartupScreen
import com.soniccore.feature.automation.AutomationScreen
import com.soniccore.feature.dashboard.DashboardScreen
import com.soniccore.feature.devices.DevicesScreen
import com.soniccore.feature.effects.EffectsScreen
import com.soniccore.feature.equalizer.EqualizerScreen
import com.soniccore.feature.microphone.MicrophoneScreen
import com.soniccore.feature.mixer.MixerScreen
import com.soniccore.feature.profiles.ProfilesScreen
import com.soniccore.feature.settings.DiagnosticsScreen
import com.soniccore.feature.settings.SettingsScreen

/** Top-level destinations. The first five appear in the bottom bar. */
enum class SonicDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = false,
) {
    DASHBOARD("dashboard", "Home", Icons.Filled.Home, inBottomBar = true),
    EQUALIZER("equalizer", "EQ", Icons.Filled.GraphicEq, inBottomBar = true),
    DEVICES("devices", "Devices", Icons.Filled.Headphones, inBottomBar = true),
    PROFILES("profiles", "Profiles", Icons.Filled.Person, inBottomBar = true),
    SETTINGS("settings", "More", Icons.Filled.Settings, inBottomBar = true),
    MIXER("mixer", "Mixer", Icons.Filled.VolumeUp),
    EFFECTS("effects", "Effects", Icons.Filled.Tune),
    MICROPHONE("microphone", "Mic", Icons.Filled.Mic),
    AUTOMATION("automation", "Automation", Icons.Filled.Bolt),
    ;

    companion object {
        fun forStartupScreen(screen: StartupScreen): SonicDestination = when (screen) {
            StartupScreen.DASHBOARD -> DASHBOARD
            StartupScreen.EQUALIZER -> EQUALIZER
            StartupScreen.DEVICES -> DEVICES
            StartupScreen.PROFILES -> PROFILES
        }
    }
}

@Composable
fun SonicCoreApp(
    startupScreen: StartupScreen = StartupScreen.DASHBOARD,
    onRequestNotificationAccess: () -> Unit = {},
    onRequestDndAccess: () -> Unit = {},
    onRequestMicPermission: () -> Unit = {},
    onShareText: (String) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                SonicDestination.entries.filter { it.inBottomBar }.forEach { destination ->
                    val selected = currentRoute?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SonicDestination.forStartupScreen(startupScreen).route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(SonicDestination.DASHBOARD.route) {
                DashboardScreen(
                    onOpenEqualizer = { navController.navigate(SonicDestination.EQUALIZER.route) },
                    onOpenProfiles = { navController.navigate(SonicDestination.PROFILES.route) },
                )
            }
            composable(SonicDestination.EQUALIZER.route) { EqualizerScreen() }
            composable(SonicDestination.DEVICES.route) { DevicesScreen() }
            composable(SonicDestination.PROFILES.route) { ProfilesScreen() }
            composable(SonicDestination.MIXER.route) {
                MixerScreen(onRequestNotificationAccess = onRequestNotificationAccess)
            }
            composable(SonicDestination.EFFECTS.route) { EffectsScreen() }
            composable(SonicDestination.MICROPHONE.route) {
                MicrophoneScreen(onRequestPermission = onRequestMicPermission)
            }
            composable(SonicDestination.AUTOMATION.route) { AutomationScreen() }
            composable(SonicDestination.SETTINGS.route) {
                MoreScreen(
                    onNavigate = { navController.navigate(it.route) },
                    onRequestNotificationAccess = onRequestNotificationAccess,
                    onRequestDndAccess = onRequestDndAccess,
                    onShareText = onShareText,
                    onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                )
            }

            // Diagnostics is a sub-screen of More rather than a bottom-bar tab: it is a
            // troubleshooting tool, but it MUST be reachable because the README,
            // CONTRIBUTING guide and bug-report template all direct users to it.
            composable(ROUTE_DIAGNOSTICS) {
                DiagnosticsScreen(onShareText = onShareText)
            }
        }
    }
}

/** Route for the diagnostic log viewer, reached from More. */
const val ROUTE_DIAGNOSTICS = "diagnostics"
