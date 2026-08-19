package com.soniccore.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.engine.PlaybackEngine
import com.soniccore.core.audio.engine.TestSignal
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.audio.routing.RoutingCapability
import com.soniccore.core.audio.volume.VolumeController
import com.soniccore.core.data.engine.ProfileApplyReport
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.RoutingResult
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val outputDevices: List<AudioDevice> = emptyList(),
    val inputDevices: List<AudioDevice> = emptyList(),
    val activeOutput: AudioDevice? = null,
    val activeInput: AudioDevice? = null,
    val volumes: Map<AudioStream, StreamVolume> = emptyMap(),
    val activeProfile: AudioProfile? = null,
    val profiles: List<AudioProfile> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val spectrum: FloatArray = FloatArray(0),
    val isTestPlaying: Boolean = false,
    val testSignal: TestSignal = TestSignal.SILENCE,
    val lastReport: ProfileApplyReport? = null,
    val message: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashboardUiState) return false
        return outputDevices == other.outputDevices &&
            inputDevices == other.inputDevices &&
            activeOutput == other.activeOutput &&
            activeInput == other.activeInput &&
            volumes == other.volumes &&
            activeProfile == other.activeProfile &&
            profiles == other.profiles &&
            settings == other.settings &&
            isTestPlaying == other.isTestPlaying &&
            testSignal == other.testSignal &&
            lastReport == other.lastReport &&
            message == other.message &&
            spectrum.contentEquals(other.spectrum)
    }

    override fun hashCode(): Int {
        var result = outputDevices.hashCode()
        result = 31 * result + inputDevices.hashCode()
        result = 31 * result + (activeOutput?.hashCode() ?: 0)
        result = 31 * result + (activeInput?.hashCode() ?: 0)
        result = 31 * result + volumes.hashCode()
        result = 31 * result + (activeProfile?.hashCode() ?: 0)
        result = 31 * result + profiles.hashCode()
        result = 31 * result + settings.hashCode()
        result = 31 * result + isTestPlaying.hashCode()
        result = 31 * result + testSignal.hashCode()
        result = 31 * result + (lastReport?.hashCode() ?: 0)
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + spectrum.contentHashCode()
        return result
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val registry: AudioDeviceRegistry,
    private val router: AudioRouter,
    private val volumeController: VolumeController,
    private val profileEngine: ProfileEngine,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val playbackEngine: PlaybackEngine,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Devices + volumes + profiles + settings, merged into one UI state.
        combine(
            registry.devices,
            volumeController.observeAll(),
            profileRepository.profiles,
            settingsStore.settings,
        ) { devices, volumes, profiles, settings ->
            val outputs = devices.filter { it.canOutput }
            val inputs = devices.filter { it.canInput }
            _uiState.value = _uiState.value.copy(
                outputDevices = outputs,
                inputDevices = inputs,
                activeOutput = router.activeOutput() ?: outputs.firstOrNull(),
                activeInput = router.activeInput() ?: inputs.firstOrNull(),
                volumes = volumes,
                profiles = profiles,
                activeProfile = profiles.firstOrNull { it.isActive },
                settings = settings,
            )
            // Remember every device we see so profiles can bind to it later.
            viewModelScope.launch { deviceRepository.rememberAll(devices) }
        }.launchIn(viewModelScope)

        playbackEngine.spectrum
            .onEach { frame ->
                _uiState.value = _uiState.value.copy(spectrum = frame.magnitudesDb)
            }
            .launchIn(viewModelScope)

        playbackEngine.state
            .onEach { engineState ->
                _uiState.value = _uiState.value.copy(
                    isTestPlaying = engineState.isRunning,
                    testSignal = engineState.signal,
                )
            }
            .launchIn(viewModelScope)

        profileEngine.lastReport
            .onEach { report ->
                _uiState.value = _uiState.value.copy(lastReport = report)
            }
            .launchIn(viewModelScope)

        // Seed built-in content on first launch.
        viewModelScope.launch {
            if (profileRepository.count() == 0) {
                profileRepository.replaceAll(com.soniccore.core.data.presets.BuiltInProfiles.all)
            }
        }
    }

    fun setVolumePercent(stream: AudioStream, percent: Float) {
        viewModelScope.launch {
            val ok = volumeController.setPercent(stream, percent)
            if (!ok) {
                val reason = if (volumeController.requiresNotificationPolicyAccess(stream)) {
                    "Do Not Disturb is blocking ${stream.displayName}. Grant notification policy access in Settings."
                } else {
                    "The system rejected that volume change."
                }
                _uiState.value = _uiState.value.copy(message = reason)
            }
        }
    }

    fun toggleMute(stream: AudioStream) {
        viewModelScope.launch { volumeController.toggleMute(stream) }
    }

    fun routeTo(device: AudioDevice) {
        viewModelScope.launch {
            when (val result = router.routeCommunicationTo(device)) {
                is RoutingResult.Success ->
                    _uiState.value = _uiState.value.copy(
                        activeOutput = device,
                        message = "Routed to ${device.label}",
                    )
                is RoutingResult.Unsupported ->
                    _uiState.value = _uiState.value.copy(message = result.reason)
                is RoutingResult.Failed ->
                    _uiState.value = _uiState.value.copy(message = result.reason)
                is RoutingResult.PermissionRequired ->
                    _uiState.value = _uiState.value.copy(
                        message = "Permission required: ${result.permission}",
                    )
            }
            deviceRepository.recordConnection(device.stableKey)
        }
    }

    fun routingCapability(device: AudioDevice): RoutingCapability = router.routingCapability(device)

    fun activateProfile(profile: AudioProfile) {
        viewModelScope.launch {
            val report = profileEngine.apply(profile, _uiState.value.activeOutput)
            _uiState.value = _uiState.value.copy(
                message = if (report.fullySucceeded) {
                    "“${profile.name}” applied"
                } else {
                    "“${profile.name}” partially applied — see details"
                },
            )
        }
    }

    fun playTestSignal(signal: TestSignal) {
        val profile = _uiState.value.activeProfile
        playbackEngine.configureDsp(
            eq = profile?.eq ?: com.soniccore.core.model.eq.EqSettings(),
            effects = profile?.effects ?: com.soniccore.core.model.effects.EffectsSettings(),
        )
        playbackEngine.start(viewModelScope, signal)
    }

    fun stopTestSignal() = playbackEngine.stop()

    fun toggleFavorite(device: AudioDevice) {
        viewModelScope.launch {
            deviceRepository.setFavorite(device.stableKey, !device.isFavorite)
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        playbackEngine.stop()
        super.onCleared()
    }
}
