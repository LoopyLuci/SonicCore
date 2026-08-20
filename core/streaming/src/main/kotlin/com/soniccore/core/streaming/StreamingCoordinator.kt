package com.soniccore.core.streaming

import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.WifiProtocol
import com.soniccore.core.streaming.airplay.AirPlayAudioStreamer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a network device to the streamer that can actually reach it, so callers
 * never branch on protocol.
 */
@Singleton
class StreamingCoordinator @Inject constructor(
    // CastStreamer (interface), not CastAudioStreamer: the FOSS flavor binds a no-op so
    // the F-Droid build carries no Play Services dependency.
    private val castStreamer: CastStreamer,
    private val airPlayStreamer: AirPlayAudioStreamer,
) {
    private val _activeState = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val activeState: StateFlow<StreamingState> = _activeState.asStateFlow()

    private var activeStreamer: AudioStreamer? = null

    fun streamerFor(protocol: StreamingProtocol): AudioStreamer = when (protocol) {
        StreamingProtocol.CHROMECAST -> castStreamer
        StreamingProtocol.AIRPLAY -> airPlayStreamer
    }

    /** Null when the device is not a streamable network target. */
    fun protocolFor(device: AudioDevice): StreamingProtocol? = when (device.wifiProtocol) {
        WifiProtocol.CHROMECAST -> StreamingProtocol.CHROMECAST
        WifiProtocol.AIRPLAY -> StreamingProtocol.AIRPLAY
        // Sonos speaks AirPlay 2 on modern models; DLNA/Spotify need their own stacks.
        WifiProtocol.SONOS -> StreamingProtocol.AIRPLAY
        WifiProtocol.DLNA, WifiProtocol.SPOTIFY_CONNECT, WifiProtocol.GENERIC, null -> null
    }

    fun toTarget(device: AudioDevice): DiscoveredStreamingTarget? {
        val protocol = protocolFor(device) ?: return null
        return DiscoveredStreamingTarget(
            id = device.stableKey,
            displayName = device.label,
            protocol = protocol,
            ipAddress = device.ipAddress,
            port = null,
        )
    }

    suspend fun connect(device: AudioDevice): StreamingResult {
        val target = toTarget(device)
            ?: return StreamingResult.Unavailable(
                "“${device.label}” uses a protocol SonicCore cannot stream to yet " +
                    "(${device.wifiProtocol?.displayName ?: "unknown"}). " +
                    "Service: ${device.wifiProtocol?.serviceType ?: "none"}.",
            )

        val streamer = streamerFor(target.protocol)
        if (!streamer.isAvailable()) {
            return StreamingResult.Unavailable(
                when (target.protocol) {
                    StreamingProtocol.CHROMECAST ->
                        "Chromecast needs Google Play Services, which this device does not have."
                    StreamingProtocol.AIRPLAY ->
                        "AirPlay is unavailable on this device."
                },
            )
        }

        activeStreamer?.let { if (it !== streamer) it.disconnect() }
        activeStreamer = streamer

        val result = streamer.connect(target)
        _activeState.value = when (result) {
            is StreamingResult.Success -> StreamingState.Connected(target.displayName, target.protocol)
            is StreamingResult.Unavailable -> StreamingState.Failed(result.reason)
            is StreamingResult.Failed -> StreamingState.Failed(result.reason)
        }
        return result
    }

    suspend fun disconnect() {
        activeStreamer?.disconnect()
        activeStreamer = null
        _activeState.value = StreamingState.Idle
    }

    suspend fun setVolume(percent: Float): StreamingResult =
        activeStreamer?.setVolume(percent)
            ?: StreamingResult.Failed("No active streaming session")

    suspend fun setMuted(muted: Boolean): StreamingResult =
        activeStreamer?.setMuted(muted)
            ?: StreamingResult.Failed("No active streaming session")

    suspend fun play(mediaUrl: String, title: String? = null): StreamingResult =
        activeStreamer?.play(mediaUrl, title)
            ?: StreamingResult.Failed("No active streaming session")

    suspend fun pause(): StreamingResult =
        activeStreamer?.pause() ?: StreamingResult.Failed("No active streaming session")

    suspend fun stop(): StreamingResult =
        activeStreamer?.stop() ?: StreamingResult.Failed("No active streaming session")

    fun observeActive(): Flow<StreamingState>? = activeStreamer?.observeState()

    /** Latency hint for lip-sync compensation on the active session. */
    fun currentLatencyMs(): Int? = activeStreamer?.estimatedLatencyMs()

    fun availableProtocols(): List<StreamingProtocol> =
        StreamingProtocol.entries.filter { streamerFor(it).isAvailable() }
}
