package com.soniccore.core.audio.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the app should be doing right now, per the system's focus decision. */
enum class FocusState {
    /** We own focus — play at full volume. */
    GRANTED,

    /** Another app wants focus briefly (navigation prompt): keep playing, quieter. */
    DUCKED,

    /** Focus lost transiently (phone call): pause, expect to resume. */
    PAUSED_TRANSIENT,

    /** Focus lost permanently (user started another player): stop and release. */
    LOST,

    /** We have not requested focus. */
    NONE,
}

/**
 * Audio focus coordination.
 *
 * Android requires every app that produces or captures audio to negotiate focus.
 * Without it the app talks over phone calls, alarms and navigation prompts, never
 * ducks, and never pauses — which is both hostile behaviour and a Play policy
 * violation.
 *
 * Ducking is handled manually rather than relying on the platform's automatic
 * ducking: SonicCore already owns a software gain stage, so it can duck smoothly and
 * by a known amount instead of letting the framework halve the volume.
 */
@Singleton
class AudioFocusManager @Inject constructor(
    private val context: Context,
) {
    private val audioManager: AudioManager?
        get() = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()

    private val _state = MutableStateFlow(FocusState.NONE)
    val state: StateFlow<FocusState> = _state.asStateFlow()

    /** Multiplier the audio path should apply — 1.0 normally, lower while ducked. */
    private val _gainMultiplier = MutableStateFlow(1f)
    val gainMultiplier: StateFlow<Float> = _gainMultiplier.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var listener: AudioManager.OnAudioFocusChangeListener? = null

    /** Invoked when the app must pause; the engine supplies this. */
    var onPause: (() -> Unit)? = null

    /** Invoked when focus returns after a transient loss. */
    var onResume: (() -> Unit)? = null

    /** Invoked on permanent loss — the engine should stop and release. */
    var onStop: (() -> Unit)? = null

    /**
     * Request focus for playback. Returns false when the system refuses (e.g. a call
     * is active), in which case the caller must NOT start playing.
     */
    fun requestPlaybackFocus(): Boolean = request(
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        durationHint = AudioManager.AUDIOFOCUS_GAIN,
    )

    /**
     * Request focus for capture/monitoring. Uses TRANSIENT_MAY_DUCK so a music app
     * can keep playing quietly while the user checks their mic.
     */
    fun requestCaptureFocus(): Boolean = request(
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
        durationHint = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
    )

    private fun request(usage: Int, contentType: Int, durationHint: Int): Boolean {
        val manager = audioManager ?: return false
        abandon()

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            handleFocusChange(change)
        }
        listener = focusListener

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(durationHint)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                // We duck ourselves, with a known gain, instead of the platform
                // applying its own attenuation on top of our DSP chain.
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .build()
            focusRequest = request
            runCatching { manager.requestAudioFocus(request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, durationHint)
            }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                _state.value = FocusState.GRANTED
                _gainMultiplier.value = 1f
                true
            }
            // API 26+: focus will arrive later (e.g. a call is ending). Do not play yet.
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                _state.value = FocusState.PAUSED_TRANSIENT
                false
            }
            else -> {
                _state.value = FocusState.NONE
                false
            }
        }
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                _state.value = FocusState.GRANTED
                _gainMultiplier.value = 1f
                onResume?.invoke()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Navigation prompt or notification: stay audible but get out of the way.
                _state.value = FocusState.DUCKED
                _gainMultiplier.value = DUCK_MULTIPLIER
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call or another transient owner: pause, keep the session.
                _state.value = FocusState.PAUSED_TRANSIENT
                _gainMultiplier.value = 1f
                onPause?.invoke()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent: another player took over. Stop and release focus.
                _state.value = FocusState.LOST
                _gainMultiplier.value = 1f
                onStop?.invoke()
                abandon()
            }
        }
    }

    /** Release focus. Safe to call when we do not hold it. */
    fun abandon() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            listener?.let { runCatching { manager.abandonAudioFocus(it) } }
        }
        focusRequest = null
        listener = null
        if (_state.value != FocusState.NONE) _state.value = FocusState.NONE
        _gainMultiplier.value = 1f
    }

    /** True when audio may be produced right now. */
    fun canPlay(): Boolean = when (_state.value) {
        FocusState.GRANTED, FocusState.DUCKED -> true
        FocusState.PAUSED_TRANSIENT, FocusState.LOST, FocusState.NONE -> false
    }

    /** True when another app currently holds focus for a call. */
    fun isInCall(): Boolean = runCatching {
        val manager = audioManager ?: return false
        manager.mode == AudioManager.MODE_IN_CALL ||
            manager.mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    companion object {
        /** -12 dB while ducked: audible but clearly subordinate. */
        const val DUCK_MULTIPLIER = 0.25f
    }
}
