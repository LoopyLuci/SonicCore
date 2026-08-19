package com.soniccore.core.streaming.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.soniccore.core.streaming.AudioStreamer
import com.soniccore.core.streaming.CastStreamer
import com.soniccore.core.streaming.StreamingProtocol
import com.soniccore.core.streaming.StreamingResult
import com.soniccore.core.streaming.StreamingState
import com.soniccore.core.streaming.StreamingTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real Chromecast playback via the Google Cast SDK.
 *
 * Notes on how Cast actually behaves:
 *  - `CastContext.getSharedInstance` throws when Play Services is missing or the
 *    `OptionsProvider` is not declared in the manifest, so every access is guarded.
 *  - Device discovery is owned by MediaRouter inside the SDK; our mDNS scan is only
 *    used to show targets before the SDK is initialised.
 *  - Playback is **URL based**. Cast cannot receive a raw PCM capture of another
 *    app's output, so there is no way to "cast the system mix" without
 *    `CAPTURE_AUDIO_OUTPUT` (signature-level). We expose media loading instead of
 *    pretending otherwise.
 */
/*
 * Constructed by CastBindingModule (full flavor) rather than via @Inject, so the
 * FOSS flavor can bind NoOpCastStreamer to the same CastStreamer interface.
 */
class CastAudioStreamer(
    private val context: Context,
) : CastStreamer {

    override val unavailableReason: String? = null

    override val protocol = StreamingProtocol.CHROMECAST

    private val stateFlow = MutableStateFlow<StreamingState>(StreamingState.Idle)

    private val castContext: CastContext?
        get() = runCatching { CastContext.getSharedInstance(context) }.getOrNull()

    private val currentSession: CastSession?
        get() = runCatching { castContext?.sessionManager?.currentCastSession }.getOrNull()

    private val remoteMediaClient: RemoteMediaClient?
        get() = runCatching { currentSession?.remoteMediaClient }.getOrNull()

    /** Cast needs Play Services; plenty of devices (and all AOSP builds) lack it. */
    override fun isAvailable(): Boolean {
        val playServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) return false
        return castContext != null
    }

    override suspend fun connect(target: StreamingTarget): StreamingResult {
        val ctx = castContext
            ?: return StreamingResult.Unavailable(
                "Google Cast is unavailable on this device — Play Services is required.",
            )

        stateFlow.value = StreamingState.Connecting(target.displayName)

        // Selection goes through MediaRouter: the SDK owns the route list, and a
        // route id from our mDNS scan is not guaranteed to match the SDK's.
        val selected = withContext(Dispatchers.Main) {
            runCatching {
                val router = androidx.mediarouter.media.MediaRouter.getInstance(context)
                val route = router.routes.firstOrNull { route ->
                    route.id == target.id || route.name == target.displayName
                }
                if (route != null) {
                    router.selectRoute(route)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        if (!selected) {
            stateFlow.value = StreamingState.Failed("“${target.displayName}” is no longer reachable")
            return StreamingResult.Failed(
                "Could not find “${target.displayName}” in the Cast route list. " +
                    "Make sure it is on the same Wi-Fi network.",
            )
        }

        // Session establishment is asynchronous; wait briefly for it to land.
        val session = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            awaitSession()
        }

        return if (session != null) {
            stateFlow.value = StreamingState.Connected(target.displayName, protocol)
            StreamingResult.Success
        } else {
            stateFlow.value = StreamingState.Failed("Timed out connecting to ${target.displayName}")
            StreamingResult.Failed("Timed out waiting for the Cast session to start.")
        }
    }

    private suspend fun awaitSession(): CastSession? = suspendCancellableCoroutine { cont ->
        val existing = currentSession
        if (existing?.isConnected == true) {
            cont.resume(existing)
            return@suspendCancellableCoroutine
        }

        val manager = castContext?.sessionManager
        if (manager == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                if (cont.isActive) cont.resume(session)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                if (cont.isActive) cont.resume(session)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
        }

        runCatching { manager.addSessionManagerListener(listener, CastSession::class.java) }
        cont.invokeOnCancellation {
            runCatching { manager.removeSessionManagerListener(listener, CastSession::class.java) }
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            runCatching { castContext?.sessionManager?.endCurrentSession(true) }
        }
        stateFlow.value = StreamingState.Idle
    }

    override suspend fun play(mediaUrl: String, title: String?, mimeType: String): StreamingResult {
        val client = remoteMediaClient
            ?: return StreamingResult.Failed("No active Cast session — connect to a device first.")

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            title?.let { putString(MediaMetadata.KEY_TITLE, it) }
            addImage(WebImage(Uri.parse(DEFAULT_ARTWORK)))
        }

        val mediaInfo = MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .build()

        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        return withContext(Dispatchers.Main) {
            runCatching {
                client.load(request)
                StreamingResult.Success
            }.getOrElse { error ->
                StreamingResult.Failed(error.message ?: "Cast load failed")
            }
        }
    }

    override suspend fun pause(): StreamingResult = mediaAction { it.pause() }
    override suspend fun resume(): StreamingResult = mediaAction { it.play() }
    override suspend fun stop(): StreamingResult = mediaAction { it.stop() }
    override suspend fun seekTo(positionMs: Long): StreamingResult = mediaAction { client ->
        client.seek(
            com.google.android.gms.cast.MediaSeekOptions.Builder()
                .setPosition(positionMs)
                .build(),
        )
    }

    /** Cast volume is session-level (0.0–1.0), not stream-level. */
    override suspend fun setVolume(percent: Float): StreamingResult = withContext(Dispatchers.Main) {
        val session = currentSession
            ?: return@withContext StreamingResult.Failed("No active Cast session")
        runCatching {
            session.volume = percent.coerceIn(0f, 1f).toDouble()
            StreamingResult.Success
        }.getOrElse { StreamingResult.Failed(it.message ?: "Could not set Cast volume") }
    }

    override suspend fun setMuted(muted: Boolean): StreamingResult = withContext(Dispatchers.Main) {
        val session = currentSession
            ?: return@withContext StreamingResult.Failed("No active Cast session")
        runCatching {
            session.isMute = muted
            StreamingResult.Success
        }.getOrElse { StreamingResult.Failed(it.message ?: "Could not mute Cast device") }
    }

    private suspend fun mediaAction(block: (RemoteMediaClient) -> Unit): StreamingResult =
        withContext(Dispatchers.Main) {
            val client = remoteMediaClient
                ?: return@withContext StreamingResult.Failed("No active Cast session")
            runCatching {
                block(client)
                StreamingResult.Success
            }.getOrElse { StreamingResult.Failed(it.message ?: "Cast command failed") }
        }

    override fun observeState(): Flow<StreamingState> = callbackFlow {
        trySend(stateFlow.value)

        val client = remoteMediaClient
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val session = currentSession ?: return
                val status: MediaStatus? = client?.mediaStatus
                val name = session.castDevice?.friendlyName ?: "Cast device"
                val state = when (status?.playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING,
                    MediaStatus.PLAYER_STATE_BUFFERING,
                    -> StreamingState.Playing(
                        targetName = name,
                        protocol = protocol,
                        title = status.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE),
                        positionMs = client.approximateStreamPosition,
                        durationMs = client.streamDuration.coerceAtLeast(0L),
                        volumePercent = runCatching { session.volume.toFloat() }.getOrDefault(0f),
                        isMuted = runCatching { session.isMute }.getOrDefault(false),
                    )
                    else -> StreamingState.Connected(name, protocol)
                }
                stateFlow.value = state
                trySend(state)
            }
        }

        runCatching { client?.registerCallback(callback) }

        val sessionListener = object : SessionManagerListener<CastSession> {
            override fun onSessionEnded(session: CastSession, error: Int) {
                stateFlow.value = StreamingState.Idle
                trySend(StreamingState.Idle)
            }

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                val name = session.castDevice?.friendlyName ?: "Cast device"
                val state = StreamingState.Connected(name, protocol)
                stateFlow.value = state
                trySend(state)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                val state = StreamingState.Failed("Cast session failed to start (code $error)")
                stateFlow.value = state
                trySend(state)
            }

            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
            override fun onSessionStarting(session: CastSession) = Unit
        }

        val manager = castContext?.sessionManager
        runCatching { manager?.addSessionManagerListener(sessionListener, CastSession::class.java) }

        awaitClose {
            runCatching { client?.unregisterCallback(callback) }
            runCatching { manager?.removeSessionManagerListener(sessionListener, CastSession::class.java) }
        }
    }.conflate()

    /** Cast buffers aggressively; ~1.5 s is typical for audio. */
    override fun estimatedLatencyMs(): Int = 1_500

    /** Cast state for UI affordances (no devices vs. connecting vs. connected). */
    fun castState(): Int = runCatching {
        castContext?.castState ?: CastState.NO_DEVICES_AVAILABLE
    }.getOrDefault(CastState.NO_DEVICES_AVAILABLE)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DEFAULT_ARTWORK = "https://via.placeholder.com/480"
        val DEFAULT_RECEIVER_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
