package com.soniccore.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.profile.AppOverride
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.InputSettings
import com.soniccore.core.model.profile.OutputSettings
import com.soniccore.core.model.profile.ProfileIcon
import com.soniccore.core.model.profile.VolumeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfilesUiState(
    val editing: AudioProfile? = null,
    val message: String? = null,
    val availableDevices: List<AudioDevice> = emptyList(),
    /** True until the first profile list arrives from Room. */
    val isLoading: Boolean = true,
    /**
     * Profile awaiting delete confirmation. Deleting a hand-tuned profile is real data
     * loss, so the UI asks first rather than acting on the tap.
     */
    val pendingDelete: AudioProfile? = null,
    /**
     * Last deleted profile, retained in memory so the Snackbar can offer Undo. Cleared
     * when the Snackbar is dismissed or another delete happens.
     */
    val recentlyDeleted: AudioProfile? = null,
)

/**
 * Profile CRUD. There is deliberately no cap on profile count — the user may
 * create as many per-device or per-scenario profiles as they want.
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val presetRepository: EqPresetRepository,
    private val profileEngine: ProfileEngine,
    private val registry: AudioDeviceRegistry,
) : ViewModel() {

    // NOTE: _uiState is declared below, after the flows. `profiles.onEach` therefore
    // must NOT touch it — a property referenced before its initialiser runs is null at
    // construction. Loading state is cleared from the init block instead.
    val profiles: StateFlow<List<AudioProfile>> = repository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val knownDevices: StateFlow<List<AudioDevice>> = deviceRepository.knownDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        // Seed built-ins defensively: the Profiles tab may be the first screen shown,
        // and HiltTestApplication skips Application.onCreate seeding entirely.
        viewModelScope.launch {
            if (repository.count() == 0) {
                repository.replaceAll(com.soniccore.core.data.presets.BuiltInProfiles.all)
            }
            // Seeding done (or already present) — the list is now trustworthy, so stop
            // showing the spinner. Doing it here avoids touching _uiState from a flow
            // initialiser that runs before _uiState exists.
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            val created = repository.create(name.ifBlank { "New profile" })
            _uiState.value = _uiState.value.copy(
                editing = created,
                message = "Created “${created.name}”",
            )
        }
    }

    fun duplicate(profile: AudioProfile) {
        viewModelScope.launch {
            val copy = repository.duplicate(profile.id)
            _uiState.value = _uiState.value.copy(
                message = copy?.let { "Duplicated as “${it.name}”" } ?: "Could not duplicate",
            )
        }
    }

    /** Ask before deleting — this is destructive and cannot be undone once confirmed. */
    fun requestDelete(profile: AudioProfile) {
        _uiState.value = _uiState.value.copy(pendingDelete = profile)
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val profile = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            val deleted = repository.delete(profile.id)
            _uiState.value = _uiState.value.copy(
                pendingDelete = null,
                // Retain the full object so Undo can restore it, including its EQ,
                // effects and device bindings — an id alone would not be enough.
                recentlyDeleted = if (deleted) profile else null,
                message = if (deleted) {
                    "Deleted “${profile.name}”"
                } else {
                    "Built-in profiles can't be deleted — duplicate it instead"
                },
            )
        }
    }

    /** Restore the last deleted profile. */
    fun undoDelete() {
        val profile = _uiState.value.recentlyDeleted ?: return
        viewModelScope.launch {
            repository.save(profile)
            _uiState.value = _uiState.value.copy(
                recentlyDeleted = null,
                message = "Restored “${profile.name}”",
            )
        }
    }

    fun clearRecentlyDeleted() {
        _uiState.value = _uiState.value.copy(recentlyDeleted = null)
    }

    fun activate(profile: AudioProfile) {
        viewModelScope.launch {
            val report = profileEngine.apply(profile)
            _uiState.value = _uiState.value.copy(
                message = if (report.fullySucceeded) {
                    "“${profile.name}” applied"
                } else {
                    report.warnings.firstOrNull() ?: "Applied with limitations"
                },
            )
        }
    }

    fun startEditing(profile: AudioProfile) {
        _uiState.value = _uiState.value.copy(editing = profile)
    }

    fun stopEditing() {
        _uiState.value = _uiState.value.copy(editing = null)
    }

    fun updateEditing(transform: (AudioProfile) -> AudioProfile) {
        val current = _uiState.value.editing ?: return
        _uiState.value = _uiState.value.copy(editing = transform(current))
    }

    fun rename(name: String) = updateEditing { it.copy(name = name) }
    fun setIcon(icon: ProfileIcon) = updateEditing { it.copy(icon = icon) }
    fun setColor(argb: Int) = updateEditing { it.copy(colorArgb = argb) }
    fun setDescription(text: String?) = updateEditing { it.copy(description = text) }
    fun setAutoActivate(enabled: Boolean) = updateEditing { it.copy(autoActivate = enabled) }
    fun setPriority(priority: Int) = updateEditing { it.copy(priority = priority) }
    fun setEq(eq: EqSettings) = updateEditing { it.copy(eq = eq) }
    fun setEffects(effects: EffectsSettings) = updateEditing { it.copy(effects = effects) }
    fun setOutput(output: OutputSettings) = updateEditing { it.copy(output = output) }
    fun setInput(input: InputSettings) = updateEditing { it.copy(input = input) }
    fun setVolume(volume: VolumeSettings) = updateEditing { it.copy(volume = volume) }

    fun setStreamVolume(stream: AudioStream, percent: Float) = updateEditing { profile ->
        profile.copy(
            volume = profile.volume.copy(
                streamPercents = profile.volume.streamPercents + (stream to percent.coerceIn(0f, 1f)),
            ),
        )
    }

    fun toggleDeviceBinding(deviceKey: String) = updateEditing { profile ->
        val bound = profile.boundDeviceKeys
        profile.copy(
            boundDeviceKeys = if (deviceKey in bound) bound - deviceKey else bound + deviceKey,
        )
    }

    fun addAppOverride(override: AppOverride) = updateEditing { profile ->
        profile.copy(
            appOverrides = profile.appOverrides.filterNot { it.packageName == override.packageName } + override,
        )
    }

    fun removeAppOverride(packageName: String) = updateEditing { profile ->
        profile.copy(appOverrides = profile.appOverrides.filterNot { it.packageName == packageName })
    }

    fun saveEditing() {
        val profile = _uiState.value.editing ?: return
        viewModelScope.launch {
            repository.save(profile)
            _uiState.value = _uiState.value.copy(
                editing = null,
                message = "Saved “${profile.name}”",
            )
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
