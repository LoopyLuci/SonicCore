package com.soniccore.core.streaming

import com.soniccore.core.model.device.AudioDevice

/**
 * A network audio target we can actually stream to, as opposed to one the
 * Android routing layer can reach.
 *
 * Cast and AirPlay are *not* in `AudioManager.getDevices()`; they are reached over
 * their own protocols. This interface is the seam between discovery (mDNS) and
 * playback (vendor protocol).
 */
interface StreamingTarget {
    val id: String
    val displayName: String
    val protocol: StreamingProtocol
    val isConnected: Boolean
}

enum class StreamingProtocol(val displayName: String) {
    CHROMECAST("Chromecast"),
    AIRPLAY("AirPlay"),
    DLNA("DLNA"),
    SPOTIFY_CONNECT("Spotify Connect"),
    GENERIC("Network Speaker"),
}

/** Where a streaming session currently is. */
sealed interface StreamingState {
    data object Idle : StreamingState
    data class Connecting(val targetName: String) : StreamingState
    data class Connected(val targetName: String, val protocol: StreamingProtocol) : StreamingState
    data class Playing(
        val targetName: String,
        val protocol: StreamingProtocol,
        val title: String? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val volumePercent: Float = 0f,
        val isMuted: Boolean = false,
    ) : StreamingState
    data class Failed(val reason: String) : StreamingState
}

/** Outcome of a streaming request — these genuinely fail, so callers must handle it. */
sealed interface StreamingResult {
    data object Success : StreamingResult
    data class Unavailable(val reason: String) : StreamingResult
    data class Failed(val reason: String) : StreamingResult
}

/**
 * Common surface over Cast and AirPlay so the UI does not branch per protocol.
 *
 * Deliberately narrow: connect, play a media URL, control volume, disconnect.
 * Neither protocol lets us capture and re-stream another app's audio — that would
 * require `CAPTURE_AUDIO_OUTPUT` (signature-level) — so the contract is honest
 * about being URL/queue based.
 */
interface AudioStreamer {
    val protocol: StreamingProtocol

    /** False when the backing SDK or hardware support is absent on this device. */
    fun isAvailable(): Boolean

    suspend fun connect(target: StreamingTarget): StreamingResult
    suspend fun disconnect()

    suspend fun play(mediaUrl: String, title: String? = null, mimeType: String = "audio/mpeg"): StreamingResult
    suspend fun pause(): StreamingResult
    suspend fun resume(): StreamingResult
    suspend fun stop(): StreamingResult
    suspend fun seekTo(positionMs: Long): StreamingResult

    suspend fun setVolume(percent: Float): StreamingResult
    suspend fun setMuted(muted: Boolean): StreamingResult

    fun observeState(): kotlinx.coroutines.flow.Flow<StreamingState>

    /** Measured or protocol-typical latency, for lip-sync compensation hints. */
    fun estimatedLatencyMs(): Int
}

/** Maps an mDNS-discovered [AudioDevice] to a streamable target. */
data class DiscoveredStreamingTarget(
    override val id: String,
    override val displayName: String,
    override val protocol: StreamingProtocol,
    override val isConnected: Boolean = false,
    val ipAddress: String? = null,
    val port: Int? = null,
) : StreamingTarget
