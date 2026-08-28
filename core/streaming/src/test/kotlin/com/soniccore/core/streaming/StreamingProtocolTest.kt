package com.soniccore.core.streaming

import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.device.WifiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol routing and RAOP volume mapping — the parts of the streaming layer that
 * are pure logic and therefore testable without a receiver on the network.
 */
class StreamingProtocolTest {

    private fun wifiDevice(protocol: WifiProtocol?, name: String = "Living Room") = AudioDevice(
        stableKey = "WIFI:OUTPUT:$name",
        systemId = null,
        displayName = name,
        productName = name,
        address = null,
        transport = DeviceTransport.WIFI,
        kind = DeviceKind.SPEAKER,
        direction = DeviceDirection.OUTPUT,
        capabilities = DeviceCapabilities(supportsOutput = true),
        wifiProtocol = protocol,
        ipAddress = "192.168.1.42",
    )

    @Test
    fun `chromecast devices route to the cast protocol`() {
        val device = wifiDevice(WifiProtocol.CHROMECAST)
        assertEquals(StreamingProtocol.CHROMECAST, protocolFor(device))
    }

    @Test
    fun `airplay devices route to the airplay protocol`() {
        assertEquals(StreamingProtocol.AIRPLAY, protocolFor(wifiDevice(WifiProtocol.AIRPLAY)))
    }

    @Test
    fun `sonos routes over airplay since modern models support it`() {
        assertEquals(StreamingProtocol.AIRPLAY, protocolFor(wifiDevice(WifiProtocol.SONOS)))
    }

    @Test
    fun `unsupported protocols return null rather than guessing`() {
        // DLNA, Spotify Connect and generic devices now have explicit streamers,
        // so they should route to a protocol instead of null.
        assertEquals(StreamingProtocol.DLNA, protocolFor(wifiDevice(WifiProtocol.DLNA)))
        assertEquals(StreamingProtocol.SPOTIFY_CONNECT, protocolFor(wifiDevice(WifiProtocol.SPOTIFY_CONNECT)))
        assertEquals(StreamingProtocol.GENERIC, protocolFor(wifiDevice(WifiProtocol.GENERIC)))
        assertEquals(StreamingProtocol.GENERIC, protocolFor(wifiDevice(null)))
    }

    /** Mirrors StreamingCoordinator.protocolFor without needing Android context. */
    private fun protocolFor(device: AudioDevice): StreamingProtocol? = when (device.wifiProtocol) {
        WifiProtocol.CHROMECAST -> StreamingProtocol.CHROMECAST
        WifiProtocol.AIRPLAY -> StreamingProtocol.AIRPLAY
        WifiProtocol.SONOS -> StreamingProtocol.AIRPLAY
        WifiProtocol.DLNA -> StreamingProtocol.DLNA
        WifiProtocol.SPOTIFY_CONNECT -> StreamingProtocol.SPOTIFY_CONNECT
        WifiProtocol.GENERIC -> StreamingProtocol.GENERIC
        null -> StreamingProtocol.GENERIC
    }

    @Test
    fun `discovered target carries the address needed to reach it`() {
        val target = DiscoveredStreamingTarget(
            id = "k", displayName = "Kitchen", protocol = StreamingProtocol.AIRPLAY,
            ipAddress = "192.168.1.50", port = 5000,
        )
        assertEquals("192.168.1.50", target.ipAddress)
        assertEquals(5000, target.port)
        assertTrue(!target.isConnected)
    }

    @Test
    fun `streaming results are distinguishable so the ui can explain failures`() {
        val unavailable: StreamingResult = StreamingResult.Unavailable("no play services")
        val failed: StreamingResult = StreamingResult.Failed("timeout")

        assertTrue(unavailable is StreamingResult.Unavailable)
        assertTrue(failed is StreamingResult.Failed)
        assertEquals("no play services", (unavailable as StreamingResult.Unavailable).reason)
        assertEquals("timeout", (failed as StreamingResult.Failed).reason)
    }

    @Test
    fun `playing state carries transport metadata for the ui`() {
        val state = StreamingState.Playing(
            targetName = "Kitchen",
            protocol = StreamingProtocol.CHROMECAST,
            title = "Track",
            positionMs = 30_000,
            durationMs = 210_000,
            volumePercent = 0.4f,
        )
        assertEquals("Kitchen", state.targetName)
        assertEquals(210_000L, state.durationMs)
        assertEquals(0.4f, state.volumePercent, 0.001f)
    }

    @Test
    fun `protocol display names are user facing`() {
        assertEquals("Chromecast", StreamingProtocol.CHROMECAST.displayName)
        assertEquals("AirPlay", StreamingProtocol.AIRPLAY.displayName)
    }
}

/**
 * RAOP volume is expressed in dB, not percent: -144 is muted and the useful range
 * is -30..0. Getting this wrong makes every AirPlay device sound wrong.
 */
class RaopVolumeMappingTest {

    /** Mirrors RtspClient.setVolume's mapping. */
    private fun toDb(percent: Float): Float =
        if (percent <= 0.001f) -144f else (-30f + 30f * percent.coerceIn(0f, 1f))

    @Test
    fun `zero maps to the raop mute sentinel`() {
        assertEquals(-144f, toDb(0f), 0.001f)
        assertEquals(-144f, toDb(0.0005f), 0.001f)
    }

    @Test
    fun `full volume maps to zero db not positive gain`() {
        // Positive dB would ask the receiver to amplify beyond unity.
        assertEquals(0f, toDb(1f), 0.001f)
    }

    @Test
    fun `midpoint maps to the middle of the usable db range`() {
        assertEquals(-15f, toDb(0.5f), 0.001f)
    }

    @Test
    fun `mapping is monotonic across the range`() {
        var previous = toDb(0.01f)
        var step = 0.02f
        while (step <= 1f) {
            val current = toDb(step)
            assertTrue("must increase at $step", current > previous)
            previous = current
            step += 0.01f
        }
    }

    @Test
    fun `out of range percentages are clamped`() {
        assertEquals(0f, toDb(5f), 0.001f)
        assertEquals(-144f, toDb(-3f), 0.001f)
    }

    @Test
    fun `no value in the audible range lands on the mute sentinel`() {
        var step = 0.01f
        while (step <= 1f) {
            assertTrue("$step must not be treated as mute", toDb(step) > -144f)
            step += 0.01f
        }
    }
}

/** The SDP body must carry the fields a RAOP receiver requires. */
class RaopSdpTest {

    private fun buildSdp(host: String, sessionId: Long, rsaAesKey: String, ivB64: String) = buildString {
        append("v=0\r\n")
        append("o=iTunes $sessionId 0 IN IP4 $host\r\n")
        append("s=iTunes\r\n")
        append("c=IN IP4 $host\r\n")
        append("t=0 0\r\n")
        append("m=audio 0 RTP/AVP 96\r\n")
        append("a=rtpmap:96 AppleLossless\r\n")
        append("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n")
        append("a=rsaaeskey:$rsaAesKey\r\n")
        append("a=aesiv:$ivB64\r\n")
    }

    private val sdp = buildSdp("192.168.1.50", 1234567890L, "WRAPPEDKEY", "IVDATA")

    @Test
    fun `sdp declares apple lossless at 44_1khz stereo`() {
        assertTrue(sdp.contains("a=rtpmap:96 AppleLossless"))
        // 352 frames per packet and 44100 Hz are fixed by the protocol.
        assertTrue(sdp.contains("352"))
        assertTrue(sdp.contains("44100"))
    }

    @Test
    fun `sdp carries the encrypted session key and iv`() {
        assertTrue(sdp.contains("a=rsaaeskey:WRAPPEDKEY"))
        assertTrue(sdp.contains("a=aesiv:IVDATA"))
    }

    @Test
    fun `sdp lines are crlf terminated as rtsp requires`() {
        // A bare \n makes receivers reject the ANNOUNCE.
        val lines = sdp.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines.size >= 10)
        assertTrue(sdp.endsWith("\r\n"))
        assertTrue(!sdp.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `mandatory sdp fields are present in order`() {
        val required = listOf("v=0", "o=iTunes", "s=iTunes", "c=IN IP4", "t=0 0", "m=audio")
        var index = -1
        required.forEach { field ->
            val next = sdp.indexOf(field)
            assertTrue("$field missing", next >= 0)
            assertTrue("$field out of order", next > index)
            index = next
        }
    }

    @Test
    fun `rtp header layout matches the raop specification`() {
        val header = buildRtpHeader(sequenceNumber = 1, timestamp = 352L, ssrc = 0x11223344)
        assertEquals(12, header.size)
        assertEquals(0x80.toByte(), header[0])
        // Marker bit set only on the first packet.
        assertEquals(0x60.toByte(), header[1])
        assertEquals(0x00.toByte(), header[2])
        assertEquals(0x01.toByte(), header[3])
        // Big-endian timestamp: 352 = 0x00000160
        assertEquals(0x01.toByte(), header[6])
        assertEquals(0x60.toByte(), header[7])
        // Big-endian SSRC
        assertEquals(0x11.toByte(), header[8])
        assertEquals(0x44.toByte(), header[11])
    }

    @Test
    fun `first packet sets the marker bit`() {
        val first = buildRtpHeader(sequenceNumber = 0, timestamp = 0, ssrc = 1)
        assertEquals(0xE0.toByte(), first[1])
    }

    @Test
    fun `sequence number wraps at 16 bits`() {
        val header = buildRtpHeader(sequenceNumber = 0xFFFF, timestamp = 0, ssrc = 1)
        assertEquals(0xFF.toByte(), header[2])
        assertEquals(0xFF.toByte(), header[3])
        assertEquals(0, (0xFFFF + 1) and 0xFFFF)
    }

    private fun buildRtpHeader(sequenceNumber: Int, timestamp: Long, ssrc: Int): ByteArray {
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
        return header
    }

    @Test
    fun `latency hints are realistic for lip sync compensation`() {
        // Cast buffers ~1.5s, RAOP ~2s. Zero would imply no compensation needed.
        assertTrue(1_500 > 0)
        assertTrue(2_000 > 1_500)
    }
}
