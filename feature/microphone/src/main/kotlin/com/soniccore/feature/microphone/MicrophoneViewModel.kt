package com.soniccore.feature.microphone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.engine.MicLevelState
import com.soniccore.core.audio.engine.MicProcessingSupport
import com.soniccore.core.audio.engine.MicrophoneEngine
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.audio.NoiseSuppressionMode
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.profile.InputSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MicrophoneUiState(
    val settings: InputSettings = InputSettings(),
    val inputDevices: List<AudioDevice> = emptyList(),
    val levels: MicLevelState = MicLevelState(),
    val spectrum: FloatArray = FloatArray(0),
    val support: MicProcessingSupport = MicProcessingSupport(false, false, false, false),
    val hasPermission: Boolean = false,
    val isMonitoring: Boolean = false,
    val message: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MicrophoneUiState) return false
        return settings == other.settings &&
            inputDevices == other.inputDevices &&
            levels == other.levels &&
            support == other.support &&
            hasPermission == other.hasPermission &&
            isMonitoring == other.isMonitoring &&
            message == other.message &&
            spectrum.contentEquals(other.spectrum)
    }

    override fun hashCode(): Int {
        var result = settings.hashCode()
        result = 31 * result + inputDevices.hashCode()
        result = 31 * result + levels.hashCode()
        result = 31 * result + support.hashCode()
        result = 31 * result + hasPermission.hashCode()
        result = 31 * result + isMonitoring.hashCode()
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + spectrum.contentHashCode()
        return result
    }
}

/**
 * Microphone settings and live monitoring.
 *
 * Android exposes no public mic *gain* setter, so level is a software gain stage in
 * our capture chain. The [MicSource] selector is the real control for platform
 * pre-processing, and each platform effect is availability-gated.
 */
@HiltViewModel
class MicrophoneViewModel @Inject constructor(
    private val engine: MicrophoneEngine,
    private val registry: AudioDeviceRegistry,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MicrophoneUiState(
            support = engine.availableProcessing(),
            hasPermission = engine.hasRecordPermission,
        ),
    )
    val uiState: StateFlow<MicrophoneUiState> = _uiState.asStateFlow()

    init {
        registry.inputDevices
            .onEach { devices ->
                _uiState.value = _uiState.value.copy(inputDevices = devices)
            }
            .launchIn(viewModelScope)

        engine.levels
            .onEach { levels ->
                _uiState.value = _uiState.value.copy(levels = levels, isMonitoring = levels.isCapturing)
            }
            .launchIn(viewModelScope)

        engine.spectrum
            .onEach { frame ->
                _uiState.value = _uiState.value.copy(spectrum = frame.magnitudesDb)
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            profileRepository.getAll().firstOrNull { it.isActive }?.let { profile ->
                _uiState.value = _uiState.value.copy(settings = profile.input)
            }
        }
    }

    fun startMonitoring() {
        val ok = engine.start(viewModelScope, _uiState.value.settings)
        _uiState.value = _uiState.value.copy(
            isMonitoring = ok,
            hasPermission = engine.hasRecordPermission,
            message = if (!ok) {
                if (!engine.hasRecordPermission) {
                    "Microphone permission is required to monitor input."
                } else {
                    "Could not open the microphone — another app may be using it."
                }
            } else {
                null
            },
        )
    }

    fun stopMonitoring() {
        engine.stop(viewModelScope)
        _uiState.value = _uiState.value.copy(isMonitoring = false)
    }

    private fun update(transform: (InputSettings) -> InputSettings) {
        val next = transform(_uiState.value.settings)
        _uiState.value = _uiState.value.copy(settings = next)
        // Restart capture so the new chain takes effect immediately.
        if (_uiState.value.isMonitoring) {
            engine.stop(viewModelScope)
            engine.start(viewModelScope, next)
        }
    }

    fun setSource(source: MicSource) = update { it.copy(micSource = source) }
    fun setGainDb(db: Float) = update { it.copy(gainDb = db.coerceIn(-24f, 36f)) }
    fun setAutoGainControl(enabled: Boolean) = update { it.copy(autoGainControl = enabled) }
    fun setNoiseSuppression(mode: NoiseSuppressionMode) = update { it.copy(noiseSuppression = mode) }
    fun setEchoCancellation(enabled: Boolean) = update { it.copy(echoCancellation = enabled) }
    fun setWindNoiseReduction(enabled: Boolean) = update { it.copy(windNoiseReduction = enabled) }
    fun setNoiseGate(enabled: Boolean) = update { it.copy(noiseGateEnabled = enabled) }
    fun setNoiseGateThreshold(db: Float) = update { it.copy(noiseGateThresholdDb = db.coerceIn(-80f, -10f)) }
    fun setNoiseGateAttack(ms: Float) = update { it.copy(noiseGateAttackMs = ms.coerceIn(0.5f, 100f)) }
    fun setNoiseGateRelease(ms: Float) = update { it.copy(noiseGateReleaseMs = ms.coerceIn(10f, 1000f)) }
    fun setSidetone(enabled: Boolean) = update { it.copy(sidetoneEnabled = enabled) }
    fun setSidetoneLevel(level: Float) = update { it.copy(sidetoneLevel = level.coerceIn(0f, 1f)) }
    fun setCompressor(enabled: Boolean) = update { it.copy(compressorEnabled = enabled) }
    fun setDeEsser(enabled: Boolean) = update { it.copy(deEsserEnabled = enabled) }
    fun setBeamforming(enabled: Boolean) = update { it.copy(beamforming = enabled) }
    fun setChannelCount(count: Int) = update { it.copy(channelCount = count.coerceIn(1, 2)) }
    fun setSampleRate(rate: Int?) = update { it.copy(preferredSampleRate = rate) }
    fun setMicEq(eq: EqSettings) = update { it.copy(micEq = eq) }
    fun setPushToTalk(enabled: Boolean) = update { it.copy(pushToTalk = enabled) }
    fun setTargetDevice(key: String?) = update { it.copy(targetDeviceKey = key) }

    fun saveToActiveProfile() {
        viewModelScope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            if (active == null) {
                _uiState.value = _uiState.value.copy(message = "No active profile to save into")
                return@launch
            }
            profileRepository.save(active.copy(input = _uiState.value.settings))
            _uiState.value = _uiState.value.copy(message = "Saved to “${active.name}”")
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    override fun onCleared() {
        engine.stop(viewModelScope)
        super.onCleared()
    }
}
