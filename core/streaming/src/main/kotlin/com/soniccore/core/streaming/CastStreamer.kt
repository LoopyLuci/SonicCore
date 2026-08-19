package com.soniccore.core.streaming

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Cast capability behind an interface so the F-Droid build can ship without the
 * proprietary Google Play Services Cast SDK.
 *
 * F-Droid's inclusion policy forbids non-free dependencies, and
 * `play-services-cast-framework` is closed source. Rather than fork the codebase, the
 * `foss` flavor binds [NoOpCastStreamer] and the `full` flavor binds the real
 * SDK-backed implementation. Everything above this interface is flavor-agnostic.
 *
 * AirPlay/RAOP is unaffected — it is implemented from scratch over RTSP with no
 * proprietary dependency, so the FOSS build keeps full AirPlay support.
 */
interface CastStreamer : AudioStreamer {
    /** Reason shown to the user when [isAvailable] returns false. */
    val unavailableReason: String?
}

/**
 * Cast stand-in for the FOSS build.
 *
 * Deliberately honest: every call fails with a reason rather than silently doing
 * nothing, so a user who taps a Chromecast learns why it did not work.
 */
class NoOpCastStreamer : CastStreamer {

    override val protocol: StreamingProtocol = StreamingProtocol.CHROMECAST

    override val unavailableReason: String =
        "Chromecast support needs Google Play Services, which this build omits. " +
            "Use the GitHub release for Cast, or stream to an AirPlay speaker."

    private val unavailable = StreamingResult.Unavailable(unavailableReason)

    override fun isAvailable(): Boolean = false

    override suspend fun connect(target: StreamingTarget): StreamingResult = unavailable
    override suspend fun disconnect() = Unit

    override suspend fun play(mediaUrl: String, title: String?, mimeType: String): StreamingResult =
        unavailable

    override suspend fun pause(): StreamingResult = unavailable
    override suspend fun resume(): StreamingResult = unavailable
    override suspend fun stop(): StreamingResult = unavailable
    override suspend fun seekTo(positionMs: Long): StreamingResult = unavailable
    override suspend fun setVolume(percent: Float): StreamingResult = unavailable
    override suspend fun setMuted(muted: Boolean): StreamingResult = unavailable

    override fun observeState(): Flow<StreamingState> = flowOf(StreamingState.Idle)

    override fun estimatedLatencyMs(): Int = 0
}
