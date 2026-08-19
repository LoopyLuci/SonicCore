package com.soniccore.feature.mixer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.session.MediaSessionBridge
import com.soniccore.core.audio.session.TransportAction
import com.soniccore.core.audio.volume.VolumeController
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.model.mixer.AppAudioSession
import com.soniccore.core.model.profile.AppOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MixerUiState(
    val sessions: List<AppAudioSession> = emptyList(),
    val streamVolumes: Map<AudioStream, StreamVolume> = emptyMap(),
    val appOverrides: List<AppOverride> = emptyList(),
    val hasNotificationAccess: Boolean = false,
    val message: String? = null,
)

/**
 * Per-app mixer.
 *
 * Android has no public API to force another app's stream to a different volume or
 * device. What *is* possible is driving each app's own MediaSession volume provider,
 * which many apps implement — [AppAudioSession.canControlVolume] tells the UI when a
 * slider will actually do something, so we never render a dead control.
 */
@HiltViewModel
class MixerViewModel @Inject constructor(
    private val sessionBridge: MediaSessionBridge,
    private val volumeController: VolumeController,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MixerUiState())
    val uiState: StateFlow<MixerUiState> = _uiState.asStateFlow()

    init {
        combine(
            sessionBridge.observeSessions(),
            volumeController.observeAll(),
            profileRepository.activeProfile,
        ) { sessions, volumes, activeProfile ->
            _uiState.value = _uiState.value.copy(
                sessions = sessions,
                streamVolumes = volumes,
                appOverrides = activeProfile?.appOverrides ?: emptyList(),
                hasNotificationAccess = sessionBridge.hasNotificationAccess,
            )
        }.launchIn(viewModelScope)
    }

    fun setSessionVolume(session: AppAudioSession, percent: Float) {
        viewModelScope.launch {
            val ok = sessionBridge.setSessionVolume(session.packageName, percent)
            if (!ok) {
                _uiState.value = _uiState.value.copy(
                    message = "${session.appLabel} doesn't expose its own volume — " +
                        "use the Media stream slider instead.",
                )
            }
        }
    }

    fun setStreamVolume(stream: AudioStream, percent: Float) {
        viewModelScope.launch { volumeController.setPercent(stream, percent) }
    }

    fun toggleStreamMute(stream: AudioStream) {
        viewModelScope.launch { volumeController.toggleMute(stream) }
    }

    fun transport(session: AppAudioSession, action: TransportAction) {
        viewModelScope.launch {
            val ok = sessionBridge.transportControl(session.packageName, action)
            if (!ok) {
                _uiState.value = _uiState.value.copy(message = "Transport control was rejected")
            }
        }
    }

    /** Save a per-app override into the active profile. */
    fun saveOverride(override: AppOverride) {
        viewModelScope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            if (active == null) {
                _uiState.value = _uiState.value.copy(message = "Activate a profile first")
                return@launch
            }
            profileRepository.save(
                active.copy(
                    appOverrides = active.appOverrides
                        .filterNot { it.packageName == override.packageName } + override,
                ),
            )
            _uiState.value = _uiState.value.copy(
                message = "Saved override for ${override.appLabel ?: override.packageName}",
            )
        }
    }

    fun removeOverride(packageName: String) {
        viewModelScope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive } ?: return@launch
            profileRepository.save(
                active.copy(appOverrides = active.appOverrides.filterNot { it.packageName == packageName }),
            )
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
