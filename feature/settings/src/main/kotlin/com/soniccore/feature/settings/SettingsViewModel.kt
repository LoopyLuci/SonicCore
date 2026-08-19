package com.soniccore.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.data.repository.AutomationRepository
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.model.settings.AccentPalette
import com.soniccore.core.model.settings.AppSettings
import com.soniccore.core.model.settings.StartupScreen
import com.soniccore.core.model.settings.ThemeMode
import com.soniccore.core.model.settings.VisualizationStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import com.soniccore.core.common.diagnostics.DiagEvent
import com.soniccore.core.common.diagnostics.DiagLevel
import com.soniccore.core.common.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SettingsUiState(
    val backupJson: String? = null,
    val stats: LibraryStats = LibraryStats(),
    val message: String? = null,
)

data class LibraryStats(
    val profileCount: Int = 0,
    val presetCount: Int = 0,
    val ruleCount: Int = 0,
    val deviceCount: Int = 0,
)

/** Diagnostic counts for the Settings summary row. */
data class DiagnosticSummary(
    val total: Int = 0,
    val warnings: Int = 0,
    val errors: Int = 0,
) {
    val hasSomethingToReport: Boolean get() = warnings > 0 || errors > 0
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val profileRepository: ProfileRepository,
    private val presetRepository: EqPresetRepository,
    private val automationRepository: AutomationRepository,
    private val deviceRepository: DeviceRepository,
    private val diagnostics: DiagnosticLog,
) : ViewModel() {

    /**
     * Live diagnostic events, newest first.
     *
     * SonicCore wraps ~150 platform calls in runCatching because OEM audio stacks refuse
     * operations the public API advertises. Surfacing that record is what makes a report
     * like "codec switching doesn't work on my phone" actionable — the README and the
     * bug-report template both point users here.
     */
    val diagnosticEvents: StateFlow<List<DiagEvent>> = diagnostics.events
        .map { it.asReversed() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** Counts for the Settings summary row, so users see there is something to report. */
    val diagnosticSummary: StateFlow<DiagnosticSummary> = diagnostics.events
        .map {
            DiagnosticSummary(
                total = it.size,
                warnings = diagnostics.count(DiagLevel.WARN),
                errors = diagnostics.count(DiagLevel.ERROR),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DiagnosticSummary(),
        )

    /** Plain-text export for attaching to a bug report. */
    fun exportDiagnostics(): String = diagnostics.export()

    fun clearDiagnostics() {
        diagnostics.clear()
    }

    val settings: StateFlow<AppSettings> = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        refreshStats()
    }

    private fun refreshStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                stats = LibraryStats(
                    profileCount = profileRepository.count(),
                    presetCount = presetRepository.count(),
                    ruleCount = automationRepository.getAll().size,
                    deviceCount = deviceRepository.getAll().size,
                ),
            )
        }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }
    fun setAccent(palette: AccentPalette) = viewModelScope.launch { store.setAccentPalette(palette) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { store.setUseDynamicColor(enabled) }
    fun setStartupScreen(screen: StartupScreen) = viewModelScope.launch { store.setStartupScreen(screen) }
    fun setVisualizationStyle(style: VisualizationStyle) =
        viewModelScope.launch { store.setVisualizationStyle(style) }
    fun setVisualizationFps(fps: Int) = viewModelScope.launch { store.setVisualizationFps(fps) }
    fun setSpectrumSmoothing(value: Float) = viewModelScope.launch { store.setSpectrumSmoothing(value) }
    fun setShowPeakHold(enabled: Boolean) = viewModelScope.launch { store.setShowPeakHold(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { store.setHapticFeedback(enabled) }
    fun setHapticIntensity(value: Float) = viewModelScope.launch { store.setHapticIntensity(value) }
    fun setPersistentNotification(enabled: Boolean) =
        viewModelScope.launch { store.setPersistentNotification(enabled) }
    fun setNotificationShowVolume(enabled: Boolean) =
        viewModelScope.launch { store.setNotificationShowVolume(enabled) }
    fun setNotificationShowProfiles(enabled: Boolean) =
        viewModelScope.launch { store.setNotificationShowProfiles(enabled) }
    fun setKeepServiceAlive(enabled: Boolean) = viewModelScope.launch { store.setKeepServiceAlive(enabled) }
    fun setAutoApplyProfiles(enabled: Boolean) = viewModelScope.launch { store.setAutoApplyProfiles(enabled) }
    fun setGlobalEqSession(enabled: Boolean) = viewModelScope.launch { store.setGlobalEqSession(enabled) }
    fun setSafeListening(enabled: Boolean) = viewModelScope.launch { store.setSafeListeningEnabled(enabled) }
    fun setSafeVolumeWarning(db: Float) = viewModelScope.launch { store.setSafeVolumeWarningDb(db) }
    fun setSafeListeningBudget(minutes: Int) = viewModelScope.launch { store.setSafeListeningBudget(minutes) }
    fun setAnalytics(enabled: Boolean) = viewModelScope.launch { store.setAnalyticsEnabled(enabled) }
    fun setCrashReporting(enabled: Boolean) = viewModelScope.launch { store.setCrashReportingEnabled(enabled) }
    fun setExperimentalBitPerfect(enabled: Boolean) =
        viewModelScope.launch { store.setExperimentalBitPerfect(enabled) }
    fun setExperimentalCodecControl(enabled: Boolean) =
        viewModelScope.launch { store.setExperimentalCodecControl(enabled) }
    fun setDeveloperMode(enabled: Boolean) = viewModelScope.launch { store.setDeveloperMode(enabled) }
    fun setAutoBackup(enabled: Boolean) = viewModelScope.launch { store.setAutoBackupEnabled(enabled) }

    /** Export everything the user has created as portable JSON. */
    fun exportBackup() {
        viewModelScope.launch {
            val backup = BackupPayload(
                version = BACKUP_VERSION,
                exportedAtEpochMs = System.currentTimeMillis(),
                profiles = profileRepository.getAll(),
                presets = presetRepository.getAll(),
                rules = automationRepository.getAll(),
                devices = deviceRepository.getAll(),
            )
            val encoded = runCatching { json.encodeToString(BackupPayload.serializer(), backup) }
            _uiState.value = _uiState.value.copy(
                backupJson = encoded.getOrNull(),
                message = encoded.fold(
                    onSuccess = {
                        "Exported ${backup.profiles.size} profiles, ${backup.presets.size} presets, " +
                            "${backup.rules.size} rules"
                    },
                    onFailure = { "Export failed: ${it.message}" },
                ),
            )
            store.setLastBackup(System.currentTimeMillis())
        }
    }

    fun importBackup(raw: String, replaceExisting: Boolean) {
        viewModelScope.launch {
            val decoded = runCatching { json.decodeFromString(BackupPayload.serializer(), raw) }
            val payload = decoded.getOrNull()
            if (payload == null) {
                _uiState.value = _uiState.value.copy(
                    message = "Import failed — the file is not a valid SonicCore backup",
                )
                return@launch
            }
            if (payload.version > BACKUP_VERSION) {
                _uiState.value = _uiState.value.copy(
                    message = "This backup was made by a newer version of SonicCore",
                )
                return@launch
            }

            payload.profiles.forEach { profile ->
                val exists = profileRepository.get(profile.id) != null
                if (!exists || replaceExisting) profileRepository.save(profile)
            }
            payload.presets.forEach { presetRepository.save(it) }
            payload.rules.forEach { automationRepository.save(it) }
            payload.devices.forEach { deviceRepository.remember(it) }

            refreshStats()
            _uiState.value = _uiState.value.copy(
                message = "Imported ${payload.profiles.size} profiles and ${payload.presets.size} presets",
            )
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    companion object {
        const val BACKUP_VERSION = 1
    }
}

@kotlinx.serialization.Serializable
data class BackupPayload(
    val version: Int,
    val exportedAtEpochMs: Long,
    val profiles: List<com.soniccore.core.model.profile.AudioProfile>,
    val presets: List<com.soniccore.core.model.eq.EqPreset>,
    val rules: List<com.soniccore.core.model.automation.AutomationRule>,
    val devices: List<com.soniccore.core.model.device.AudioDevice>,
)
