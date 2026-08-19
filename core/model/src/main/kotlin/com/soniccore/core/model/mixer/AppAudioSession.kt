package com.soniccore.core.model.mixer

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackState { PLAYING, PAUSED, STOPPED, BUFFERING, NONE }

/**
 * A live audio session belonging to another app, as surfaced by
 * MediaSessionManager. Volume control here goes through the session's own
 * volume provider — we cannot forcibly re-route another app's stream.
 */
data class AppAudioSession(
    val packageName: String,
    val appLabel: String,
    val sessionTag: String?,
    val playbackState: PlaybackState,
    val trackTitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val volumePercent: Float? = null,
    val volumeControlIsRelative: Boolean = false,
    val canControlVolume: Boolean = false,
    val isMuted: Boolean = false,
    val hasArtwork: Boolean = false,
) {
    val isActive: Boolean get() = playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
}

/** An installed app the user may configure an override for. */
data class InstalledAudioApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val hasAudioPermission: Boolean,
)
