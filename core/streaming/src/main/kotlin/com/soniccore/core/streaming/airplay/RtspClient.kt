package com.soniccore.core.streaming.airplay

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** One parsed RTSP response. */
internal data class RtspResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    /** 453 = "Not Enough Bandwidth" is RAOP's "another sender owns me". */
    val isBusy: Boolean get() = statusCode == 453

    /** AirPlay 2 receivers answer 470/403 to a v1 ANNOUNCE. */
    val requiresPairing: Boolean get() = statusCode == 470 || statusCode == 403
}

/**
 * Minimal RTSP/1.0 client for RAOP.
 *
 * RAOP is RTSP with Apple extensions: the sender ANNOUNCEs an SDP blob carrying the
 * encrypted AES key, SETUPs to negotiate the audio/control/timing ports, then
 * RECORDs to begin streaming. Volume is a SET_PARAMETER with a dB value.
 *
 * This is a blocking socket client by design — callers run it on Dispatchers.IO.
 */
internal class RtspClient(
    private val host: String,
    private val port: Int,
    private val clientInstance: String,
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var sequence = 0
    private var sessionId: String? = null

    val activeSessionId: String? get() = sessionId

    fun connect(timeoutMs: Int = 5_000): Boolean = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream()))
        true
    }.getOrDefault(false)

    fun close() {
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        socket = null
        reader = null
        sessionId = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** ANNOUNCE the SDP session description, including the RSA-wrapped AES key. */
    fun announce(sdp: String): RtspResponse? = request(
        method = "ANNOUNCE",
        uri = rtspUri(),
        headers = mapOf("Content-Type" to "application/sdp"),
        body = sdp,
    )

    /** SETUP negotiates the RTP server/control/timing ports. */
    fun setup(controlPort: Int, timingPort: Int): RtspResponse? {
        val response = request(
            method = "SETUP",
            uri = rtspUri(),
            headers = mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=$controlPort;timing_port=$timingPort",
            ),
        )
        response?.headers?.get("session")?.let { sessionId = it }
        return response
    }

    /** RECORD starts the stream; RTP-Info seeds the sequence number. */
    fun record(startSeq: Int, startTimestamp: Long): RtspResponse? = request(
        method = "RECORD",
        uri = rtspUri(),
        headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$startSeq;rtptime=$startTimestamp",
        ),
    )

    /**
     * RAOP volume is in dB: -144 means muted, and the useful range is -30..0.
     * A linear percentage would sound wrong, so map perceptually.
     */
    fun setVolume(percent: Float): RtspResponse? {
        val db = if (percent <= 0.001f) {
            MUTED_DB
        } else {
            (MIN_DB + (MAX_DB - MIN_DB) * percent.coerceIn(0f, 1f))
        }
        return request(
            method = "SET_PARAMETER",
            uri = rtspUri(),
            headers = mapOf("Content-Type" to "text/parameters"),
            body = "volume: ${"%.6f".format(db)}\r\n",
        )
    }

    fun setProgress(startMs: Long, currentMs: Long, endMs: Long): RtspResponse? = request(
        method = "SET_PARAMETER",
        uri = rtspUri(),
        headers = mapOf("Content-Type" to "text/parameters"),
        body = "progress: $startMs/$currentMs/$endMs\r\n",
    )

    fun flush(seq: Int, timestamp: Long): RtspResponse? = request(
        method = "FLUSH",
        uri = rtspUri(),
        headers = mapOf("RTP-Info" to "seq=$seq;rtptime=$timestamp"),
    )

    fun teardown(): RtspResponse? = request(method = "TEARDOWN", uri = rtspUri())

    fun options(): RtspResponse? = request(method = "OPTIONS", uri = "*")

    private fun rtspUri(): String = "rtsp://$host/$clientInstance"

    private fun request(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): RtspResponse? = runCatching {
        val out = socket?.getOutputStream() ?: return null
        sequence++

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $uri RTSP/1.0\r\n")
            append("CSeq: $sequence\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            append("Client-Instance: $clientInstance\r\n")
            sessionId?.let { append("Session: $it\r\n") }
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            bodyBytes?.let { append("Content-Length: ${it.size}\r\n") }
            append("\r\n")
        }

        out.write(request.toByteArray(Charsets.UTF_8))
        bodyBytes?.let { out.write(it) }
        out.flush()

        readResponse()
    }.getOrNull()

    private fun readResponse(): RtspResponse? {
        val r = reader ?: return null

        val statusLine = r.readLine() ?: return null
        val parts = statusLine.split(" ", limit = 3)
        if (parts.size < 2) return null
        val code = parts[1].toIntOrNull() ?: return null
        val text = parts.getOrElse(2) { "" }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = r.read(buffer, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read)
        } else {
            ""
        }

        return RtspResponse(code, text, headers, body)
    }

    companion object {
        const val DEFAULT_RAOP_PORT = 5000
        private const val USER_AGENT = "SonicCore/1.0 (iTunes/11.0)"
        private const val MUTED_DB = -144.0f
        private const val MIN_DB = -30.0f
        private const val MAX_DB = 0.0f
    }
}
