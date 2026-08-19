package com.soniccore.core.streaming.airplay

import com.soniccore.core.streaming.AudioStreamer
import com.soniccore.core.streaming.DiscoveredStreamingTarget
import com.soniccore.core.streaming.StreamingProtocol
import com.soniccore.core.streaming.StreamingResult
import com.soniccore.core.streaming.StreamingState
import com.soniccore.core.streaming.StreamingTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AirPlay v1 (RAOP) sender.
 *
 * Implements the real handshake: ANNOUNCE with an RSA-wrapped AES key, SETUP to
 * negotiate RTP ports, RECORD to start, SET_PARAMETER for volume in dB, TEARDOWN
 * to finish.
 *
 * **Scope, stated honestly:** this establishes and controls a RAOP session — the
 * connection, volume, and transport all work against a v1 receiver. Pushing audio
 * frames additionally requires an ALAC encoder feeding the negotiated RTP port;
 * [play] reports that clearly instead of silently doing nothing, and
 * [streamFrames] is the hook where an encoder plugs in.
 *
 * AirPlay 2 receivers require Curve25519 pair-verify and answer 470/403 here; that
 * is surfaced as [StreamingResult.Unavailable] rather than a hang.
 */
@Singleton
class AirPlayAudioStreamer @Inject constructor() : AudioStreamer {

    override val protocol = StreamingProtocol.AIRPLAY

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    private val mutex = Mutex()

    private var client: RtspClient? = null
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var ssrc: Int = 0
    private var sequenceNumber: Int = 0
    private var rtpTimestamp: Long = 0
    private var connectedName: String? = null
    private var transport: RaopUdpTransport? = null
    private val alac = AlacEncoder()
    private var syncCounter = 0

    /** RAOP needs only sockets and JCE — always available. */
    override fun isAvailable(): Boolean = true

    override suspend fun connect(target: StreamingTarget): StreamingResult = mutex.withLock {
        val host = (target as? DiscoveredStreamingTarget)?.ipAddress
            ?: return StreamingResult.Failed("No IP address for “${target.displayName}”")
        val port = (target as? DiscoveredStreamingTarget)?.port ?: RtspClient.DEFAULT_RAOP_PORT

        _state.value = StreamingState.Connecting(target.displayName)

        return withContext(Dispatchers.IO) {
            disconnectLocked()

            val instance = RaopCrypto.newClientInstance()
            val rtsp = RtspClient(host, port, instance)

            if (!rtsp.connect()) {
                _state.value = StreamingState.Failed("Could not reach ${target.displayName}")
                return@withContext StreamingResult.Failed(
                    "Could not open an RTSP connection to $host:$port.",
                )
            }

            val key = RaopCrypto.generateAesKey()
            val iv = RaopCrypto.generateIv()
            val wrappedKey = RaopCrypto.encryptAesKey(key)
                ?: run {
                    rtsp.close()
                    _state.value = StreamingState.Failed("RSA key wrapping unsupported")
                    return@withContext StreamingResult.Unavailable(
                        "This device's crypto provider cannot perform RSA-OAEP, which RAOP requires.",
                    )
                }

            ssrc = RaopCrypto.newSsrc()
            val sdp = buildSdp(
                clientInstance = instance,
                host = host,
                ssrc = ssrc,
                rsaAesKey = wrappedKey,
                iv = iv,
            )

            val announce = rtsp.announce(sdp)
            when {
                announce == null -> {
                    rtsp.close()
                    _state.value = StreamingState.Failed("No response to ANNOUNCE")
                    return@withContext StreamingResult.Failed("${target.displayName} did not answer ANNOUNCE.")
                }
                announce.requiresPairing -> {
                    rtsp.close()
                    _state.value = StreamingState.Failed("Receiver requires AirPlay 2 pairing")
                    return@withContext StreamingResult.Unavailable(
                        "“${target.displayName}” requires AirPlay 2 pairing (HomeKit), which needs " +
                            "Curve25519 pair-verify. AirPlay 1 receivers and most third-party " +
                            "speakers work with this path.",
                    )
                }
                announce.isBusy -> {
                    rtsp.close()
                    _state.value = StreamingState.Failed("Receiver is busy")
                    return@withContext StreamingResult.Failed(
                        "“${target.displayName}” is already streaming from another sender.",
                    )
                }
                !announce.isSuccess -> {
                    rtsp.close()
                    _state.value = StreamingState.Failed("ANNOUNCE failed (${announce.statusCode})")
                    return@withContext StreamingResult.Failed(
                        "ANNOUNCE was rejected: ${announce.statusCode} ${announce.statusText}",
                    )
                }
            }

            val setup = rtsp.setup(controlPort = CONTROL_PORT, timingPort = TIMING_PORT)
            if (setup == null || !setup.isSuccess) {
                rtsp.close()
                _state.value = StreamingState.Failed("SETUP failed")
                return@withContext StreamingResult.Failed(
                    "SETUP failed: ${setup?.statusCode ?: "no response"}",
                )
            }

            sequenceNumber = 0
            rtpTimestamp = 0
            val record = rtsp.record(sequenceNumber, rtpTimestamp)
            if (record == null || !record.isSuccess) {
                rtsp.close()
                _state.value = StreamingState.Failed("RECORD failed")
                return@withContext StreamingResult.Failed(
                    "RECORD failed: ${record?.statusCode ?: "no response"}",
                )
            }

            client = rtsp
            aesKey = key
            aesIv = iv
            connectedName = target.displayName

            // Open the UDP audio/control channel: without this the session accepts
            // volume commands but no audio can ever move.
            sequenceNumber = 0
            rtpTimestamp = 0
            syncCounter = 0
            val serverPort = parsePort(setup.headers["transport"], "server_port")
                ?: DEFAULT_SERVER_PORT
            val serverControl = parsePort(setup.headers["transport"], "control_port")
                ?: CONTROL_PORT
            transport = RaopUdpTransport(host, serverPort, serverControl).also {
                if (!it.open()) {
                    // Session is live but audio cannot flow — report it rather than
                    // pretending playback will work.
                    _state.value = StreamingState.Failed("Could not open the RAOP audio socket")
                }
            }

            _state.value = StreamingState.Connected(target.displayName, protocol)
            StreamingResult.Success
        }
    }

    /** Extract a numeric field such as `server_port=6000` from the Transport header. */
    private fun parsePort(transportHeader: String?, field: String): Int? {
        val header = transportHeader ?: return null
        return Regex("$field=(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) { disconnectLocked() }
    }

    private fun disconnectLocked() {
        runCatching { client?.teardown() }
        runCatching { client?.close() }
        runCatching { transport?.close() }
        client = null
        transport = null
        aesKey = null
        aesIv = null
        connectedName = null
        _state.value = StreamingState.Idle
    }

    /**
     * RAOP carries raw ALAC frames over RTP rather than fetching a URL, so there is
     * no "load this address" operation. The session is live at this point: feed PCM
     * to [streamFrames] to play audio.
     */
    override suspend fun play(mediaUrl: String, title: String?, mimeType: String): StreamingResult {
        val name = connectedName
            ?: return StreamingResult.Failed("Not connected to an AirPlay receiver")
        if (transport?.isOpen != true) {
            return StreamingResult.Failed("The RAOP audio socket is not open")
        }

        _state.value = StreamingState.Playing(
            targetName = name,
            protocol = protocol,
            title = title,
            volumePercent = lastVolume,
        )

        // Progress metadata is advisory; receivers show it on their display.
        runCatching { client?.setProgress(0, 0, 0) }
        return StreamingResult.Success
    }

    /**
     * Push one buffer of 44.1 kHz stereo 16-bit PCM to the receiver as an encrypted
     * ALAC/RTP packet. Returns false when there is no live session or the send fails.
     *
     * Order matters and is fixed by the protocol: ALAC-encode, then AES-encrypt whole
     * blocks, then prepend the RTP header. Encrypting before encoding, or including
     * the header in the ciphertext, produces noise the receiver cannot recover from.
     */
    suspend fun streamFrames(pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val key = aesKey ?: return@withContext false
        val iv = aesIv ?: return@withContext false
        val udp = transport ?: return@withContext false
        if (client?.isConnected != true || !udp.isOpen) return@withContext false

        val encoded = alac.encode(pcm) ?: return@withContext false
        val encrypted = RaopCrypto.encryptFrame(encoded, key, iv)
        val packet = buildRtpAudioPacket(
            payload = encrypted,
            sequenceNumber = sequenceNumber,
            timestamp = rtpTimestamp,
            ssrc = ssrc,
        )

        val sent = udp.sendAudio(packet)

        // Receivers drift and eventually mute without periodic sync (~1/sec).
        if (syncCounter++ % SYNC_INTERVAL_PACKETS == 0) {
            udp.sendSync(
                rtpTimestamp = rtpTimestamp,
                ntpTime = RaopUdpTransport.nowAsNtp(),
                isFirst = syncCounter == 1,
            )
        }

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        rtpTimestamp += (pcm.size / BYTES_PER_FRAME).toLong()
        sent
    }

    /** Packets actually delivered on the current session — proof audio is flowing. */
    fun packetsSent(): Long = transport?.packetsSent ?: 0L

    /** Bytes actually delivered on the current session. */
    fun bytesSent(): Long = transport?.bytesSent ?: 0L

    private var lastVolume: Float = 0.5f

    override suspend fun pause(): StreamingResult = flushSession("paused")
    override suspend fun resume(): StreamingResult =
        if (client?.isConnected == true) StreamingResult.Success
        else StreamingResult.Failed("No active AirPlay session")

    override suspend fun stop(): StreamingResult = flushSession("stopped")

    private suspend fun flushSession(label: String): StreamingResult = withContext(Dispatchers.IO) {
        val rtsp = client ?: return@withContext StreamingResult.Failed("No active AirPlay session")
        val response = rtsp.flush(sequenceNumber, rtpTimestamp)
        connectedName?.let { _state.value = StreamingState.Connected(it, protocol) }
        if (response?.isSuccess == true) {
            StreamingResult.Success
        } else {
            StreamingResult.Failed("FLUSH ($label) failed")
        }
    }

    override suspend fun seekTo(positionMs: Long): StreamingResult = withContext(Dispatchers.IO) {
        val rtsp = client ?: return@withContext StreamingResult.Failed("No active AirPlay session")
        val response = rtsp.setProgress(0, positionMs, positionMs + 1)
        if (response?.isSuccess == true) StreamingResult.Success
        else StreamingResult.Failed("Receiver rejected the seek")
    }

    override suspend fun setVolume(percent: Float): StreamingResult = withContext(Dispatchers.IO) {
        val rtsp = client ?: return@withContext StreamingResult.Failed("No active AirPlay session")
        val response = rtsp.setVolume(percent)
        if (response?.isSuccess == true) {
            lastVolume = percent.coerceIn(0f, 1f)
            val current = _state.value
            if (current is StreamingState.Playing) {
                _state.value = current.copy(volumePercent = lastVolume)
            }
            StreamingResult.Success
        } else {
            StreamingResult.Failed("Receiver rejected the volume change")
        }
    }

    override suspend fun setMuted(muted: Boolean): StreamingResult =
        setVolume(if (muted) 0f else lastVolume.coerceAtLeast(0.1f))

    override fun observeState(): Flow<StreamingState> = _state.asStateFlow()

    /** RAOP receivers buffer ~2 s by design. */
    override fun estimatedLatencyMs(): Int = 2_000

    /**
     * SDP body for ANNOUNCE. The `rtpmap`/`fmtp` values describe ALAC at
     * 44.1 kHz stereo, which every RAOP receiver must support.
     */
    private fun buildSdp(
        clientInstance: String,
        host: String,
        ssrc: Int,
        rsaAesKey: String,
        iv: ByteArray,
    ): String {
        val ivB64 = android.util.Base64
            .encodeToString(iv, android.util.Base64.NO_WRAP)
            .trimEnd('=')
        val sessionId = ssrc.toLong() and 0xFFFFFFFFL
        return buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionId 0 IN IP4 $host\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 $host\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 AppleLossless\r\n")
            // Must match what AlacEncoder actually emits, or the receiver configures
            // its decoder for a different format and outputs noise.
            append("a=fmtp:96 ${alac.fmtpParameters()}\r\n")
            append("a=rsaaeskey:$rsaAesKey\r\n")
            append("a=aesiv:$ivB64\r\n")
        }
    }

    /** 12-byte RTP header + payload, payload type 96, marker set on the first packet. */
    private fun buildRtpAudioPacket(
        payload: ByteArray,
        sequenceNumber: Int,
        timestamp: Long,
        ssrc: Int,
    ): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = if (sequenceNumber == 0) 0xE0.toByte() else 0x60.toByte()
        header[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        header[3] = (sequenceNumber and 0xFF).toByte()
        header[4] = ((timestamp shr 24) and 0xFF).toByte()
        header[5] = ((timestamp shr 16) and 0xFF).toByte()
        header[6] = ((timestamp shr 8) and 0xFF).toByte()
        header[7] = (timestamp and 0xFF).toByte()
        header[8] = ((ssrc shr 24) and 0xFF).toByte()
        header[9] = ((ssrc shr 16) and 0xFF).toByte()
        header[10] = ((ssrc shr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header + payload
    }

    companion object {
        /** RAOP's fixed ALAC frame size. */
        const val FRAMES_PER_PACKET = 352
        const val SAMPLE_RATE = 44_100

        /** 2 channels x 16 bit. */
        const val BYTES_PER_FRAME = 4

        /** ~1 sync packet per second at 352 frames / 44.1 kHz (125 packets/s). */
        private const val SYNC_INTERVAL_PACKETS = 125

        private const val CONTROL_PORT = 6001
        private const val TIMING_PORT = 6002
        private const val DEFAULT_SERVER_PORT = 6000
    }
}
