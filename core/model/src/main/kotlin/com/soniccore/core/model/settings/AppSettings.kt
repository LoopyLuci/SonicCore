package com.soniccore.core.model.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED black"),
}

@Serializable
enum class AccentPalette(val displayName: String) {
    DYNAMIC("Material You"),
    SONIC_BLUE("Sonic blue"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    CRIMSON("Crimson"),
    MONOCHROME("Monochrome"),
}

@Serializable
enum class VisualizationStyle(val displayName: String) {
    BARS("Spectrum bars"),
    LINE("Spectrum line"),
    FILLED("Filled spectrum"),
    MIRROR("Mirrored"),
    WAVEFORM("Waveform"),
    SPECTROGRAM("Spectrogram"),
    CIRCULAR("Circular"),
    OFF("Off"),
}

@Serializable
enum class StartupScreen(val displayName: String) {
    DASHBOARD("Dashboard"),
    EQUALIZER("Equalizer"),
    DEVICES("Devices"),
    PROFILES("Profiles"),
}

@Serializable
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentPalette: AccentPalette = AccentPalette.DYNAMIC,
    val useDynamicColor: Boolean = true,
    val startupScreen: StartupScreen = StartupScreen.DASHBOARD,
    val visualizationStyle: VisualizationStyle = VisualizationStyle.BARS,
    val visualizationFps: Int = 60,
    val spectrumSmoothing: Float = 0.6f,
    val showPeakHold: Boolean = true,
    val hapticFeedback: Boolean = true,
    val hapticIntensity: Float = 0.7f,
    val persistentNotification: Boolean = true,
    val notificationShowVolume: Boolean = true,
    val notificationShowProfiles: Boolean = true,
    val keepServiceAlive: Boolean = true,
    val autoApplyProfiles: Boolean = true,
    val globalEqSession: Boolean = true,
    val volumeStepOverride: Int? = null,
    val safeVolumeWarningDb: Float = 85f,
    val safeListeningEnabled: Boolean = true,
    val safeListeningDailyBudgetMinutes: Int = 480,
    val analyticsEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val experimentalBitPerfect: Boolean = false,
    val experimentalCodecControl: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val lastBackupEpochMs: Long? = null,
    val activeProfileId: String? = null,
    val developerMode: Boolean = false,
)
