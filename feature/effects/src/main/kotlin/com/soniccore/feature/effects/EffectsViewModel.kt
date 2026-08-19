package com.soniccore.feature.effects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.effects.PlatformEffectsController
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.ReplayGainMode
import com.soniccore.core.model.effects.BassBoostSettings
import com.soniccore.core.model.effects.CrossfeedSettings
import com.soniccore.core.model.effects.DitheringMode
import com.soniccore.core.model.effects.DynamicsSettings
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.effects.HrtfProfile
import com.soniccore.core.model.effects.LoudnessSettings
import com.soniccore.core.model.effects.ReverbPreset
import com.soniccore.core.model.effects.ReverbSettings
import com.soniccore.core.model.effects.SpatialAudioSettings
import com.soniccore.core.model.effects.VirtualizerMode
import com.soniccore.core.model.effects.VirtualizerSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EffectsUiState(
    val settings: EffectsSettings = EffectsSettings(),
    val platformEffectsAvailable: Set<String> = emptySet(),
    val platformAttached: Boolean = false,
    val platformNote: String? = null,
    val message: String? = null,
)

@HiltViewModel
class EffectsViewModel @Inject constructor(
    private val platformEffects: PlatformEffectsController,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EffectsUiState())
    val uiState: StateFlow<EffectsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (!platformEffects.isAttached) {
                platformEffects.attach(PlatformEffectsController.GLOBAL_SESSION)
            }
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            _uiState.value = EffectsUiState(
                settings = active?.effects ?: EffectsSettings(),
                platformEffectsAvailable = platformEffects.availableEffects(),
                platformAttached = platformEffects.isAttached,
                platformNote = platformEffects.lastError,
            )
        }
    }

    private fun update(transform: (EffectsSettings) -> EffectsSettings) {
        val next = transform(_uiState.value.settings)
        _uiState.value = _uiState.value.copy(settings = next)
        viewModelScope.launch {
            val applied = platformEffects.applyEffects(next)
            if (!applied) {
                _uiState.value = _uiState.value.copy(
                    platformNote = platformEffects.lastError
                        ?: "Some effects were rejected by this device",
                )
            }
        }
    }

    // Bass boost
    fun setBassBoost(enabled: Boolean) = update { it.copy(bassBoost = it.bassBoost.copy(enabled = enabled)) }
    fun setBassStrength(strength: Float) =
        update { it.copy(bassBoost = it.bassBoost.copy(strength = strength.coerceIn(0f, 1f))) }
    fun setBassCutoff(hz: Float) =
        update { it.copy(bassBoost = it.bassBoost.copy(cutoffHz = hz.coerceIn(40f, 300f))) }

    // Virtualizer
    fun setVirtualizer(enabled: Boolean) =
        update { it.copy(virtualizer = it.virtualizer.copy(enabled = enabled)) }
    fun setVirtualizerStrength(strength: Float) =
        update { it.copy(virtualizer = it.virtualizer.copy(strength = strength.coerceIn(0f, 1f))) }
    fun setVirtualizerMode(mode: VirtualizerMode) =
        update { it.copy(virtualizer = it.virtualizer.copy(mode = mode)) }

    // Spatial
    fun setSpatial(enabled: Boolean) = update { it.copy(spatial = it.spatial.copy(enabled = enabled)) }
    fun setHeadTracking(enabled: Boolean) =
        update { it.copy(spatial = it.spatial.copy(headTracking = enabled)) }
    fun setRoomSize(size: Float) =
        update { it.copy(spatial = it.spatial.copy(roomSize = size.coerceIn(0f, 1f))) }
    fun setHrtf(profile: HrtfProfile) = update { it.copy(spatial = it.spatial.copy(hrtfProfile = profile)) }
    fun setAtmosPassthrough(enabled: Boolean) =
        update { it.copy(spatial = it.spatial.copy(passthroughAtmos = enabled)) }

    // Reverb
    fun setReverb(enabled: Boolean) = update { it.copy(reverb = it.reverb.copy(enabled = enabled)) }
    fun setReverbPreset(preset: ReverbPreset) = update { it.copy(reverb = it.reverb.copy(preset = preset)) }
    fun setReverbWet(mix: Float) = update { it.copy(reverb = it.reverb.copy(wetMix = mix.coerceIn(0f, 1f))) }
    fun setReverbDecay(seconds: Float) =
        update { it.copy(reverb = it.reverb.copy(decaySeconds = seconds.coerceIn(0.1f, 10f))) }

    // Crossfeed
    fun setCrossfeed(enabled: Boolean) = update { it.copy(crossfeed = it.crossfeed.copy(enabled = enabled)) }
    fun setCrossfeedAmount(amount: Float) =
        update { it.copy(crossfeed = it.crossfeed.copy(amount = amount.coerceIn(0f, 1f))) }
    fun setCrossfeedCutoff(hz: Float) =
        update { it.copy(crossfeed = it.crossfeed.copy(cutoffHz = hz.coerceIn(200f, 2000f))) }
    fun setCrossfeedDelay(micros: Float) =
        update { it.copy(crossfeed = it.crossfeed.copy(delayMicros = micros.coerceIn(0f, 1000f))) }

    // Dynamics
    fun setCompressor(enabled: Boolean) =
        update { it.copy(dynamics = it.dynamics.copy(compressorEnabled = enabled)) }
    fun setThreshold(db: Float) =
        update { it.copy(dynamics = it.dynamics.copy(thresholdDb = db.coerceIn(-60f, 0f))) }
    fun setRatio(ratio: Float) =
        update { it.copy(dynamics = it.dynamics.copy(ratio = ratio.coerceIn(1f, 20f))) }
    fun setAttack(ms: Float) =
        update { it.copy(dynamics = it.dynamics.copy(attackMs = ms.coerceIn(0.1f, 200f))) }
    fun setRelease(ms: Float) =
        update { it.copy(dynamics = it.dynamics.copy(releaseMs = ms.coerceIn(10f, 2000f))) }
    fun setMakeupGain(db: Float) =
        update { it.copy(dynamics = it.dynamics.copy(makeupGainDb = db.coerceIn(-12f, 24f))) }
    fun setLimiter(enabled: Boolean) =
        update { it.copy(dynamics = it.dynamics.copy(limiterEnabled = enabled)) }
    fun setLimiterCeiling(db: Float) =
        update { it.copy(dynamics = it.dynamics.copy(limiterCeilingDb = db.coerceIn(-12f, 0f))) }
    fun setNightMode(enabled: Boolean) =
        update { it.copy(dynamics = it.dynamics.copy(nightMode = enabled)) }
    fun setSpeechEnhancement(enabled: Boolean) =
        update { it.copy(dynamics = it.dynamics.copy(speechEnhancement = enabled)) }

    // Loudness
    fun setLoudness(enabled: Boolean) = update { it.copy(loudness = it.loudness.copy(enabled = enabled)) }
    fun setLoudnessGain(millibels: Int) =
        update { it.copy(loudness = it.loudness.copy(targetGainMb = millibels.coerceIn(0, 2000))) }
    fun setReplayGain(mode: ReplayGainMode) =
        update { it.copy(loudness = it.loudness.copy(replayGainMode = mode)) }

    // Channel / stereo
    fun setChannelMode(mode: ChannelMode) = update { it.copy(channelMode = mode) }
    fun setBalance(balance: Float) = update { it.copy(balance = balance.coerceIn(-1f, 1f)) }
    fun setStereoWidth(width: Float) = update { it.copy(stereoWidth = width.coerceIn(0f, 2f)) }
    fun setDithering(mode: DitheringMode) = update { it.copy(dithering = mode) }
    fun setPhaseInvertLeft(enabled: Boolean) = update { it.copy(phaseInvertLeft = enabled) }
    fun setPhaseInvertRight(enabled: Boolean) = update { it.copy(phaseInvertRight = enabled) }

    fun resetAll() = update { EffectsSettings() }

    fun saveToActiveProfile() {
        viewModelScope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            if (active == null) {
                _uiState.value = _uiState.value.copy(message = "No active profile to save into")
                return@launch
            }
            profileRepository.save(active.copy(effects = _uiState.value.settings))
            _uiState.value = _uiState.value.copy(message = "Saved to “${active.name}”")
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
