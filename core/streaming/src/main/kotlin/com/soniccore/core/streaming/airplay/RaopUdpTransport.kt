package com.soniccore.core.streaming.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP transport for RAOP audio, control and timing.
 *
 * RAOP negotiates three UDP ports in SETUP. Audio flows continuously; the control
 * channel carries retransmit requests and sync packets. Without this class the
 * session connects and accepts volume commands but no audio ever moves — which is
 * exactly the gap this fills.
 */
internal class RaopUdpTransport(
    private val host: String,
    private val audioPort: Int,
    private val controlPort: Int,
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null

    /** Packets sent since open — lets callers verify audio is actually flowing. */
    var packetsSent: Long = 0
        private set

    var bytesSent: Long = 0
        private set

    fun open(): Boolean = runCatching {
        address = InetAddress.getByName(host)
        socket = DatagramSocket().apply {
            // A large send buffer prevents drops during GC pauses; RAOP is unforgiving
            // about gaps because the receiver's jitter buffer is small.
            sendBufferSize = 1 shl 18
            soTimeout = 2_000
        }
        true
    }.getOrDefault(false)

    val isOpen: Boolean get() = socket?.isClosed == false

    fun sendAudio(packet: ByteArray): Boolean = runCatching {
        val sock = socket ?: return false
        val addr = address ?: return false
        sock.send(DatagramPacket(packet, packet.size, addr, audioPort))
        packetsSent++
        bytesSent += packet.size
        true
    }.getOrDefault(false)

    /**
     * RAOP sync packet (control channel, payload type 84).
     *
     * Receivers drift without periodic sync and eventually mute or stutter. The
     * timestamp pair tells the receiver where "now" is relative to the RTP clock.
     */
    fun sendSync(rtpTimestamp: Long, ntpTime: Long, isFirst: Boolean): Boolean = runCatching {
        val sock = socket ?: return false
        val addr = address ?: return false

        val buffer = ByteArray(20)
        buffer[0] = if (isFirst) 0x90.toByte() else 0x80.toByte()
        buffer[1] = (0x80 or 84).toByte()   // marker + payload type 84 = sync
        buffer[2] = 0x00
        buffer[3] = 0x07                     // length in 32-bit words minus one

        // RTP timestamp the receiver should be playing "now", minus latency.
        val nowTs = (rtpTimestamp - RAOP_LATENCY_SAMPLES).coerceAtLeast(0L)
        writeInt(buffer, 4, nowTs)
        writeLong(buffer, 8, ntpTime)
        writeInt(buffer, 16, rtpTimestamp)

        sock.send(DatagramPacket(buffer, buffer.size, addr, controlPort))
        true
    }.getOrDefault(false)

    private fun writeInt(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeLong(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buf[offset + i] = ((value shr (56 - i * 8)) and 0xFF).toByte()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        address = null
    }

    companion object {
        /** RAOP's fixed 2-second buffer, expressed in 44.1 kHz samples. */
        const val RAOP_LATENCY_SAMPLES = 88_200L

        /** NTP epoch is 1900; Unix epoch is 1970. */
        private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

        /** Current time as a 64-bit NTP timestamp (32.32 fixed point). */
        fun nowAsNtp(): Long {
            val millis = System.currentTimeMillis()
            val seconds = millis / 1000 + NTP_EPOCH_OFFSET_SECONDS
            val fraction = ((millis % 1000) * (1L shl 32)) / 1000
            return (seconds shl 32) or fraction
        }
    }
}
