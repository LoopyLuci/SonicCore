package com.soniccore.feature.settings

import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.settings.AccentPalette
import com.soniccore.core.model.settings.StartupScreen
import com.soniccore.core.model.settings.ThemeMode
import com.soniccore.core.model.settings.VisualizationStyle
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.theme.MonoNumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onShareBackup: (String) -> Unit = {},
    onOpenNotificationAccess: () -> Unit = {},
    onOpenDndAccess: () -> Unit = {},
    onOpenOverlayAccess: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_settings)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            item { SectionHeader(title = stringResource(R.string.settings_appearance)) }
            item {
                ChipRow(
                    options = ThemeMode.entries.map { it to it.displayName },
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }
            item {
                ChipRow(
                    options = AccentPalette.entries.map { it to it.displayName },
                    selected = settings.accentPalette,
                    onSelect = viewModel::setAccent,
                )
            }
            item {
                Toggle(
                    "Material You colours",
                    settings.useDynamicColor,
                    viewModel::setDynamicColor,
                    "Follows your wallpaper on Android 12+",
                )
            }
            item {
                ChipRow(
                    options = StartupScreen.entries.map { it to it.displayName },
                    selected = settings.startupScreen,
                    onSelect = viewModel::setStartupScreen,
                )
            }

            item { SectionHeader(title = stringResource(R.string.settings_visualization)) }
            item {
                ChipRow(
                    options = VisualizationStyle.entries.map { it to it.displayName },
                    selected = settings.visualizationStyle,
                    onSelect = viewModel::setVisualizationStyle,
                )
            }
            item {
                SliderRow(
                    "Frame rate",
                    "${settings.visualizationFps} fps",
                    (settings.visualizationFps - 15) / 105f,
                ) { viewModel.setVisualizationFps((15 + it * 105).toInt()) }
            }
            item {
                SliderRow(
                    "Smoothing",
                    "${(settings.spectrumSmoothing * 100).toInt()}%",
                    settings.spectrumSmoothing,
                ) { viewModel.setSpectrumSmoothing(it) }
            }
            item { Toggle("Peak hold", settings.showPeakHold, viewModel::setShowPeakHold) }

            item { SectionHeader(title = stringResource(R.string.settings_feedback)) }
            item { Toggle("Haptic feedback", settings.hapticFeedback, viewModel::setHaptics) }
            if (settings.hapticFeedback) {
                item {
                    SliderRow(
                        "Intensity",
                        "${(settings.hapticIntensity * 100).toInt()}%",
                        settings.hapticIntensity,
                    ) { viewModel.setHapticIntensity(it) }
                }
            }

            item { SectionHeader(title = stringResource(R.string.settings_service_notification)) }
            item {
                Toggle(
                    "Persistent notification",
                    settings.persistentNotification,
                    viewModel::setPersistentNotification,
                    "Required for background profile switching on Android 8+",
                )
            }
            item {
                Toggle(
                    "Show volume in notification",
                    settings.notificationShowVolume,
                    viewModel::setNotificationShowVolume,
                )
            }
            item {
                Toggle(
                    "Show profile switcher in notification",
                    settings.notificationShowProfiles,
                    viewModel::setNotificationShowProfiles,
                )
            }
            item {
                Toggle(
                    "Keep service alive",
                    settings.keepServiceAlive,
                    viewModel::setKeepServiceAlive,
                    "Restarts the service after boot so automation keeps working",
                )
            }

            item { SectionHeader(title = stringResource(R.string.settings_audio_behaviour)) }
            item {
                Toggle(
                    "Auto-apply profiles",
                    settings.autoApplyProfiles,
                    viewModel::setAutoApplyProfiles,
                    "Activate a bound profile when its device connects",
                )
            }
            item {
                Toggle(
                    "System-wide equalizer",
                    settings.globalEqSession,
                    viewModel::setGlobalEqSession,
                    "Attaches to the global mix so other apps are affected where the device allows it",
                )
            }

            item { SectionHeader(title = stringResource(R.string.settings_hearing_safety)) }
            item {
                Toggle(
                    "Safe listening",
                    settings.safeListeningEnabled,
                    viewModel::setSafeListening,
                    "Warns when sustained level risks hearing damage",
                )
            }
            if (settings.safeListeningEnabled) {
                item {
                    SliderRow(
                        "Warning level",
                        "%.0f dB".format(settings.safeVolumeWarningDb),
                        (settings.safeVolumeWarningDb - 70f) / 30f,
                    ) { viewModel.setSafeVolumeWarning(70f + it * 30f) }
                }
                item {
                    SliderRow(
                        "Daily budget",
                        "${settings.safeListeningDailyBudgetMinutes / 60} h",
                        settings.safeListeningDailyBudgetMinutes / 960f,
                    ) { viewModel.setSafeListeningBudget((it * 960).toInt().coerceAtLeast(30)) }
                }
            }

            item { SectionHeader(title = stringResource(R.string.settings_permissions)) }
            item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(onClick = onOpenNotificationAccess) { Text(stringResource(R.string.settings_notification_access)) }
                                OutlinedButton(onClick = onOpenDndAccess) { Text(stringResource(R.string.settings_do_not_disturb)) }
                            }
                        }
                        item {
                            LimitationNotice(
                                text = stringResource(R.string.settings_notification_access_powers_the_per_app_m),
                            )
                        }
                        item {
                            val overlayGranted = Settings.canDrawOverlays(LocalContext.current.applicationContext)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(onClick = onOpenOverlayAccess) {
                                    Text(stringResource(R.string.settings_overlay_access_open))
                                }
                                InfoChip(
                                    text = stringResource(
                                        if (overlayGranted) R.string.settings_overlay_access_granted
                                        else R.string.settings_overlay_access_not_granted,
                                    ),
                                )
                            }
                        }
                        item {
                            LimitationNotice(
                                text = stringResource(R.string.settings_overlay_access_description),
                            )
                        }

            item { SectionHeader(title = stringResource(R.string.settings_backup), subtitle = stringResource(R.string.settings_everything_you_ve_created_as_portable_js)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            InfoChip(text = pluralStringResource(R.plurals.count_profiles, state.stats.profileCount, state.stats.profileCount), mono = true)
                            InfoChip(text = pluralStringResource(R.plurals.count_presets, state.stats.presetCount, state.stats.presetCount), mono = true)
                            InfoChip(text = pluralStringResource(R.plurals.count_rules, state.stats.ruleCount, state.stats.ruleCount), mono = true)
                            InfoChip(text = pluralStringResource(R.plurals.count_devices, state.stats.deviceCount, state.stats.deviceCount), mono = true)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::exportBackup) { Text(stringResource(R.string.settings_export)) }
                            state.backupJson?.let { json ->
                                OutlinedButton(onClick = { onShareBackup(json) }) { Text(stringResource(R.string.settings_share)) }
                            }
                        }
                    }
                }
            }
            item {
                Toggle("Automatic backup", settings.autoBackupEnabled, viewModel::setAutoBackup)
            }

            item { SectionHeader(title = stringResource(R.string.settings_privacy)) }
            item {
                Toggle(
                    "Anonymous usage analytics",
                    settings.analyticsEnabled,
                    viewModel::setAnalytics,
                    "Off by default. Nothing leaves the device unless you turn this on.",
                )
            }
            item {
                Toggle("Crash reporting", settings.crashReportingEnabled, viewModel::setCrashReporting)
            }

            item { SectionHeader(title = stringResource(R.string.settings_experimental), subtitle = stringResource(R.string.settings_may_not_work_on_every_device)) }
            item {
                Toggle(
                    "Bit-perfect USB output",
                    settings.experimentalBitPerfect,
                    viewModel::setExperimentalBitPerfect,
                    "Attempts to bypass the platform mixer for USB DACs. Requires exclusive USB access " +
                        "and does not work on all devices.",
                )
            }
            item {
                Toggle(
                    "Bluetooth codec control",
                    settings.experimentalCodecControl,
                    viewModel::setExperimentalCodecControl,
                    "Codec selection is a privileged API. SonicCore attempts it and reports what the " +
                        "system actually chose.",
                )
            }
            item { Toggle("Developer mode", settings.developerMode, viewModel::setDeveloperMode) }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(0.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MonoNumericStyle)
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
