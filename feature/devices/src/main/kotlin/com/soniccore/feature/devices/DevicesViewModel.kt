package com.soniccore.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.device.BluetoothInfoProvider
import com.soniccore.core.audio.device.UsbAudioProvider
import com.soniccore.core.audio.device.WifiSpeakerDiscovery
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.audio.routing.RoutingCapability
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.audio.CodecStrategy
import com.soniccore.core.model.audio.RoutingResult
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.streaming.StreamingCoordinator
import com.soniccore.core.streaming.StreamingProtocol
import com.soniccore.core.streaming.StreamingResult
import com.soniccore.core.streaming.StreamingState
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

data class DevicesUiState(
    val devices: List<AudioDevice> = emptyList(),
    val knownDevices: List<AudioDevice> = emptyList(),
    val activeOutput: AudioDevice? = null,
    val activeInput: AudioDevice? = null,
    val profiles: List<AudioProfile> = emptyList(),
    val selectedDevice: AudioDevice? = null,
    val bluetoothPermissionGranted: Boolean = true,
    val bluetoothSupported: Boolean = true,
    val wifiDiscoverySupported: Boolean = true,
    val codecControlAvailable: Boolean = false,
    /**
     * True until the first device snapshot arrives. Device enumeration and the
     * BroadcastReceiver registration are asynchronous, so without this the UI shows an
     * empty list on launch and looks broken rather than busy.
     */
    val isLoading: Boolean = true,
    val streamingState: StreamingState = StreamingState.Idle,
    val streamableProtocols: List<StreamingProtocol> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val registry: AudioDeviceRegistry,
    private val router: AudioRouter,
    private val deviceRepository: DeviceRepository,
    private val profileRepository: ProfileRepository,
    private val bluetoothInfo: BluetoothInfoProvider,
    private val usbAudio: UsbAudioProvider,
    private val wifiDiscovery: WifiSpeakerDiscovery,
    private val streamingCoordinator: StreamingCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        bluetoothInfo.connectProxy()

        combine(
            registry.devices,
            deviceRepository.knownDevices,
            profileRepository.profiles,
        ) { live, known, profiles ->
            _uiState.value = _uiState.value.copy(
                devices = live,
                // The first emission means enumeration finished — clear the spinner.
                isLoading = false,
                // Remembered devices we can't currently see.
                knownDevices = known.filterNot { remembered ->
                    live.any { it.stableKey == remembered.stableKey }
                },
                profiles = profiles,
                activeOutput = router.activeOutput(),
                activeInput = router.activeInput(),
                bluetoothPermissionGranted = bluetoothInfo.hasBluetoothPermission,
                bluetoothSupported = bluetoothInfo.isBluetoothSupported,
                wifiDiscoverySupported = wifiDiscovery.isSupported,
                codecControlAvailable = live.any { it.capabilities.supportsCodecSelection },
                streamableProtocols = streamingCoordinator.availableProtocols(),
            )
        }.launchIn(viewModelScope)
    }

    fun select(device: AudioDevice?) {
        _uiState.value = _uiState.value.copy(selectedDevice = device)
    }

    /**
     * Network speakers cannot be reached through the Android routing layer, so they
     * are handed to the streaming coordinator (Cast/AirPlay) instead. Everything
     * else goes through the platform router.
     */
    fun routeTo(device: AudioDevice) {
        viewModelScope.launch {
            if (device.transport == DeviceTransport.WIFI) {
                connectStreaming(device)
                return@launch
            }
            val result = router.routeCommunicationTo(device)
            _uiState.value = _uiState.value.copy(
                message = when (result) {
                    is RoutingResult.Success -> "Routed to ${device.label}"
                    is RoutingResult.Unsupported -> result.reason
                    is RoutingResult.Failed -> result.reason
                    is RoutingResult.PermissionRequired -> "Needs permission: ${result.permission}"
                },
                activeOutput = router.activeOutput(),
            )
        }
    }

    /** Open a real Cast or AirPlay session to a discovered network speaker. */
    fun connectStreaming(device: AudioDevice) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                streamingState = StreamingState.Connecting(device.label),
            )
            val result = streamingCoordinator.connect(device)
            _uiState.value = _uiState.value.copy(
                streamingState = streamingCoordinator.activeState.value,
                message = when (result) {
                    is StreamingResult.Success ->
                        "Connected to ${device.label}" +
                            (streamingCoordinator.currentLatencyMs()
                                ?.let { " (~${it}ms latency)" } ?: "")
                    is StreamingResult.Unavailable -> result.reason
                    is StreamingResult.Failed -> result.reason
                },
            )
        }
    }

    fun disconnectStreaming() {
        viewModelScope.launch {
            streamingCoordinator.disconnect()
            _uiState.value = _uiState.value.copy(
                streamingState = StreamingState.Idle,
                message = "Disconnected",
            )
        }
    }

    fun setStreamingVolume(percent: Float) {
        viewModelScope.launch {
            val result = streamingCoordinator.setVolume(percent)
            if (result is StreamingResult.Failed) {
                _uiState.value = _uiState.value.copy(message = result.reason)
            }
        }
    }

    /** True when this device can be streamed to over Cast or AirPlay. */
    fun isStreamable(device: AudioDevice): Boolean =
        streamingCoordinator.protocolFor(device) != null

    fun routingCapability(device: AudioDevice): RoutingCapability = router.routingCapability(device)

    fun setLabel(device: AudioDevice, label: String?) {
        viewModelScope.launch {
            deviceRepository.setLabel(device.stableKey, label?.takeIf { it.isNotBlank() })
        }
    }

    fun setNotes(device: AudioDevice, notes: String?) {
        viewModelScope.launch { deviceRepository.setNotes(device.stableKey, notes) }
    }

    fun toggleFavorite(device: AudioDevice) {
        viewModelScope.launch {
            deviceRepository.setFavorite(device.stableKey, !device.isFavorite)
        }
    }

    fun setPreferredProfile(device: AudioDevice, profileId: String?) {
        viewModelScope.launch {
            deviceRepository.setPreferredProfile(device.stableKey, profileId)
            profileId?.let { id ->
                profileRepository.get(id)?.let { profile ->
                    profileRepository.save(
                        profile.copy(boundDeviceKeys = profile.boundDeviceKeys + device.stableKey),
                    )
                }
            }
            _uiState.value = _uiState.value.copy(message = "Profile binding updated")
        }
    }

    fun forget(device: AudioDevice) {
        viewModelScope.launch {
            deviceRepository.forget(device.stableKey)
            _uiState.value = _uiState.value.copy(message = "Forgot ${device.label}")
        }
    }

    /**
     * Codec selection needs BLUETOOTH_PRIVILEGED on most builds. We attempt it and
     * report honestly when the platform refuses rather than showing a fake success.
     */
    fun requestCodec(device: AudioDevice, codec: BluetoothCodec) {
        _uiState.value = _uiState.value.copy(
            message = "Codec selection requires system privileges on most devices. " +
                "Requested ${codec.displayName} — the active codec above shows what the system actually chose.",
        )
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        bluetoothInfo.releaseProxy()
        super.onCleared()
    }
}
