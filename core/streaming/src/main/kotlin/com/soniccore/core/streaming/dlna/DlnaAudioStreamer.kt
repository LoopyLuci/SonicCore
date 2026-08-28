package com.soniccore.core.streaming.dlna

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.soniccore.core.streaming.AudioStreamer
import com.soniccore.core.streaming.DiscoveredStreamingTarget
import com.soniccore.core.streaming.StreamingProtocol
import com.soniccore.core.streaming.StreamingResult
import com.soniccore.core.streaming.StreamingState
import com.soniccore.core.streaming.StreamingTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DlnaAudioStreamer @Inject constructor(
    private val context: Context,
) : AudioStreamer {

    override val protocol: StreamingProtocol = StreamingProtocol.DLNA

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    private var currentTarget: DiscoveredStreamingTarget? = null

    override fun isAvailable(): Boolean = true

    override suspend fun connect(target: StreamingTarget): StreamingResult {
        val dlnaTarget = target as? DiscoveredStreamingTarget
            ?: return StreamingResult.Failed("Invalid DLNA target")
        currentTarget = dlnaTarget
        _state.value = StreamingState.Connecting(dlnaTarget.displayName)
        return StreamingResult.Success
    }

    override suspend fun disconnect() {
        currentTarget = null
        _state.value = StreamingState.Idle
    }

    override suspend fun play(mediaUrl: String, title: String?, mimeType: String): StreamingResult {
        val target = currentTarget ?: return StreamingResult.Failed("No DLNA target")
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(mediaUrl), mimeType.ifBlank { "audio/*" })
                putExtra("title", title)
                putExtra("targetName", target.displayName)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.value = StreamingState.Playing(target.displayName, protocol, title)
            StreamingResult.Success
        } catch (e: android.content.ActivityNotFoundException) {
            _state.value = StreamingState.Failed("No app installed for DLNA playback")
            StreamingResult.Unavailable("Install a DLNA-capable app to play to this device")
        } catch (e: Exception) {
            _state.value = StreamingState.Failed("DLNA playback failed: ${e.message}")
            StreamingResult.Failed("Could not send media to DLNA target: ${e.message}")
        }
    }

    override suspend fun pause(): StreamingResult = stop()
    override suspend fun resume(): StreamingResult = play("", null)
    override suspend fun stop(): StreamingResult {
        currentTarget = null
        _state.value = StreamingState.Idle
        return StreamingResult.Success
    }

    override suspend fun seekTo(positionMs: Long): StreamingResult =
        StreamingResult.Unavailable("DLNA seeking not implemented yet")

    override suspend fun setVolume(percent: Float): StreamingResult {
        _state.value = (_state.value as? StreamingState.Playing)?.copy(volumePercent = percent) ?: _state.value
        return StreamingResult.Success
    }

    override suspend fun setMuted(muted: Boolean): StreamingResult {
        _state.value = (_state.value as? StreamingState.Playing)?.copy(isMuted = muted) ?: _state.value
        return StreamingResult.Success
    }

    override fun observeState(): Flow<StreamingState> = _state

    override fun estimatedLatencyMs(): Int = 800
}
