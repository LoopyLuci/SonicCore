package com.soniccore.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.soniccore.core.model.settings.AccentPalette
import com.soniccore.core.model.settings.AppSettings
import com.soniccore.core.model.settings.StartupScreen
import com.soniccore.core.model.settings.ThemeMode
import com.soniccore.core.model.settings.VisualizationStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "soniccore_settings")

/** App-level preferences, typed and observable. */
@Singleton
class SettingsStore @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentPalette = stringPreferencesKey("accent_palette")
        val useDynamicColor = booleanPreferencesKey("use_dynamic_color")
        val startupScreen = stringPreferencesKey("startup_screen")
        val visualizationStyle = stringPreferencesKey("visualization_style")
        val visualizationFps = intPreferencesKey("visualization_fps")
        val spectrumSmoothing = floatPreferencesKey("spectrum_smoothing")
        val showPeakHold = booleanPreferencesKey("show_peak_hold")
        val hapticFeedback = booleanPreferencesKey("haptic_feedback")
        val hapticIntensity = floatPreferencesKey("haptic_intensity")
        val persistentNotification = booleanPreferencesKey("persistent_notification")
        val notificationShowVolume = booleanPreferencesKey("notification_show_volume")
        val notificationShowProfiles = booleanPreferencesKey("notification_show_profiles")
        val keepServiceAlive = booleanPreferencesKey("keep_service_alive")
        val autoApplyProfiles = booleanPreferencesKey("auto_apply_profiles")
        val globalEqSession = booleanPreferencesKey("global_eq_session")
        val volumeStepOverride = intPreferencesKey("volume_step_override")
        val safeVolumeWarningDb = floatPreferencesKey("safe_volume_warning_db")
        val safeListeningEnabled = booleanPreferencesKey("safe_listening_enabled")
        val safeListeningBudget = intPreferencesKey("safe_listening_budget")
        val analyticsEnabled = booleanPreferencesKey("analytics_enabled")
        val crashReportingEnabled = booleanPreferencesKey("crash_reporting_enabled")
        val experimentalBitPerfect = booleanPreferencesKey("experimental_bit_perfect")
        val experimentalCodecControl = booleanPreferencesKey("experimental_codec_control")
        val autoBackupEnabled = booleanPreferencesKey("auto_backup_enabled")
        val lastBackupEpochMs = longPreferencesKey("last_backup_epoch_ms")
        val activeProfileId = stringPreferencesKey("active_profile_id")
        val developerMode = booleanPreferencesKey("developer_mode")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            onboardingComplete = prefs[Keys.onboardingComplete] ?: defaults.onboardingComplete,
            themeMode = prefs[Keys.themeMode]?.toEnum(defaults.themeMode) ?: defaults.themeMode,
            accentPalette = prefs[Keys.accentPalette]?.toEnum(defaults.accentPalette) ?: defaults.accentPalette,
            useDynamicColor = prefs[Keys.useDynamicColor] ?: defaults.useDynamicColor,
            startupScreen = prefs[Keys.startupScreen]?.toEnum(defaults.startupScreen) ?: defaults.startupScreen,
            visualizationStyle = prefs[Keys.visualizationStyle]?.toEnum(defaults.visualizationStyle)
                ?: defaults.visualizationStyle,
            visualizationFps = prefs[Keys.visualizationFps] ?: defaults.visualizationFps,
            spectrumSmoothing = prefs[Keys.spectrumSmoothing] ?: defaults.spectrumSmoothing,
            showPeakHold = prefs[Keys.showPeakHold] ?: defaults.showPeakHold,
            hapticFeedback = prefs[Keys.hapticFeedback] ?: defaults.hapticFeedback,
            hapticIntensity = prefs[Keys.hapticIntensity] ?: defaults.hapticIntensity,
            persistentNotification = prefs[Keys.persistentNotification] ?: defaults.persistentNotification,
            notificationShowVolume = prefs[Keys.notificationShowVolume] ?: defaults.notificationShowVolume,
            notificationShowProfiles = prefs[Keys.notificationShowProfiles] ?: defaults.notificationShowProfiles,
            keepServiceAlive = prefs[Keys.keepServiceAlive] ?: defaults.keepServiceAlive,
            autoApplyProfiles = prefs[Keys.autoApplyProfiles] ?: defaults.autoApplyProfiles,
            globalEqSession = prefs[Keys.globalEqSession] ?: defaults.globalEqSession,
            volumeStepOverride = prefs[Keys.volumeStepOverride],
            safeVolumeWarningDb = prefs[Keys.safeVolumeWarningDb] ?: defaults.safeVolumeWarningDb,
            safeListeningEnabled = prefs[Keys.safeListeningEnabled] ?: defaults.safeListeningEnabled,
            safeListeningDailyBudgetMinutes = prefs[Keys.safeListeningBudget]
                ?: defaults.safeListeningDailyBudgetMinutes,
            analyticsEnabled = prefs[Keys.analyticsEnabled] ?: defaults.analyticsEnabled,
            crashReportingEnabled = prefs[Keys.crashReportingEnabled] ?: defaults.crashReportingEnabled,
            experimentalBitPerfect = prefs[Keys.experimentalBitPerfect] ?: defaults.experimentalBitPerfect,
            experimentalCodecControl = prefs[Keys.experimentalCodecControl] ?: defaults.experimentalCodecControl,
            autoBackupEnabled = prefs[Keys.autoBackupEnabled] ?: defaults.autoBackupEnabled,
            lastBackupEpochMs = prefs[Keys.lastBackupEpochMs],
            activeProfileId = prefs[Keys.activeProfileId],
            developerMode = prefs[Keys.developerMode] ?: defaults.developerMode,
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnum(fallback: T): T =
        runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.onboardingComplete] = value }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.themeMode] = value.name }
    suspend fun setAccentPalette(value: AccentPalette) = edit { it[Keys.accentPalette] = value.name }
    suspend fun setUseDynamicColor(value: Boolean) = edit { it[Keys.useDynamicColor] = value }
    suspend fun setStartupScreen(value: StartupScreen) = edit { it[Keys.startupScreen] = value.name }
    suspend fun setVisualizationStyle(value: VisualizationStyle) = edit { it[Keys.visualizationStyle] = value.name }
    suspend fun setVisualizationFps(value: Int) = edit { it[Keys.visualizationFps] = value.coerceIn(15, 120) }
    suspend fun setSpectrumSmoothing(value: Float) = edit { it[Keys.spectrumSmoothing] = value.coerceIn(0f, 1f) }
    suspend fun setShowPeakHold(value: Boolean) = edit { it[Keys.showPeakHold] = value }
    suspend fun setHapticFeedback(value: Boolean) = edit { it[Keys.hapticFeedback] = value }
    suspend fun setHapticIntensity(value: Float) = edit { it[Keys.hapticIntensity] = value.coerceIn(0f, 1f) }
    suspend fun setPersistentNotification(value: Boolean) = edit { it[Keys.persistentNotification] = value }
    suspend fun setNotificationShowVolume(value: Boolean) = edit { it[Keys.notificationShowVolume] = value }
    suspend fun setNotificationShowProfiles(value: Boolean) = edit { it[Keys.notificationShowProfiles] = value }
    suspend fun setKeepServiceAlive(value: Boolean) = edit { it[Keys.keepServiceAlive] = value }
    suspend fun setAutoApplyProfiles(value: Boolean) = edit { it[Keys.autoApplyProfiles] = value }
    suspend fun setGlobalEqSession(value: Boolean) = edit { it[Keys.globalEqSession] = value }
    suspend fun setSafeListeningEnabled(value: Boolean) = edit { it[Keys.safeListeningEnabled] = value }
    suspend fun setSafeVolumeWarningDb(value: Float) = edit { it[Keys.safeVolumeWarningDb] = value }
    suspend fun setSafeListeningBudget(minutes: Int) = edit { it[Keys.safeListeningBudget] = minutes }
    suspend fun setAnalyticsEnabled(value: Boolean) = edit { it[Keys.analyticsEnabled] = value }
    suspend fun setCrashReportingEnabled(value: Boolean) = edit { it[Keys.crashReportingEnabled] = value }
    suspend fun setExperimentalBitPerfect(value: Boolean) = edit { it[Keys.experimentalBitPerfect] = value }
    suspend fun setExperimentalCodecControl(value: Boolean) = edit { it[Keys.experimentalCodecControl] = value }
    suspend fun setAutoBackupEnabled(value: Boolean) = edit { it[Keys.autoBackupEnabled] = value }
    suspend fun setLastBackup(epochMs: Long) = edit { it[Keys.lastBackupEpochMs] = epochMs }
    suspend fun setActiveProfileId(id: String?) = edit { prefs ->
        if (id == null) prefs.remove(Keys.activeProfileId) else prefs[Keys.activeProfileId] = id
    }
    suspend fun setDeveloperMode(value: Boolean) = edit { it[Keys.developerMode] = value }
    suspend fun setVolumeStepOverride(value: Int?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.volumeStepOverride) else prefs[Keys.volumeStepOverride] = value
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
