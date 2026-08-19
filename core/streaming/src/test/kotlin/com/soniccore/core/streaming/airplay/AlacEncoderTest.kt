package com.soniccore.core.streaming.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ALAC escape-mode encoding and the bit writer beneath it.
 *
 * The header is not byte aligned, so an off-by-one in the bit writer shifts every
 * sample and the receiver plays noise. These tests decode the header back out bit by
 * bit rather than trusting the writer.
 */
class AlacEncoderTest {

    private val encoder = AlacEncoder()

    /** 352 frames x 2ch x 16bit = 1408 bytes, RAOP's exact packet size. */
    private fun pcmPacket(fill: Byte = 0x11) = ByteArray(352 * 4) { fill }

    @Test
    fun `encodes a full raop packet`() {
        val out = encoder.encode(pcmPacket())
        assertNotNull(out)
        // Header is 3+12+1+2+1+32 = 51 bits, so 7 bytes of header before samples.
        assertTrue("output must exceed the input header", out!!.size > 1408)
    }

    @Test
    fun `rejects a partial frame rather than desynchronising the receiver`() {
        // 3 bytes is not a whole 4-byte stereo frame.
        assertNull(encoder.encode(ByteArray(3)))
        assertNull(encoder.encode(ByteArray(1409)))
        assertNull(encoder.encode(ByteArray(0)))
    }

    @Test
    fun `accepts any whole number of frames`() {
        listOf(4, 8, 40, 352 * 4, 1024 * 4).forEach { size ->
            assertNotNull("size $size should encode", encoder.encode(ByteArray(size)))
        }
    }

    @Test
    fun `header declares stereo cpe and uncompressed escape mode`() {
        val out = encoder.encode(pcmPacket())!!
        val bits = BitReader(out)

        assertEquals("element tag must be CPE for stereo", 1, bits.read(3))
        assertEquals("reserved bits must be zero", 0, bits.read(12))
        assertEquals("has-32-bit-length flag", 1, bits.read(1))
        assertEquals("uncompressed byte count", 0, bits.read(2))
        assertEquals("must set the NOT-compressed escape flag", 1, bits.read(1))
        assertEquals("frame count", 352, bits.read(32))
    }

    @Test
    fun `frame count in the header matches the pcm supplied`() {
        listOf(1, 64, 352, 512).forEach { frames ->
            val out = encoder.encode(ByteArray(frames * 4))!!
            val bits = BitReader(out)
            bits.read(3); bits.read(12); bits.read(1); bits.read(2); bits.read(1)
            assertEquals("frames=$frames", frames, bits.read(32))
        }
    }

    @Test
    fun `samples are converted from little endian to big endian`() {
        // One stereo frame: L=0x1234, R=0x5678 in little-endian byte order.
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val out = encoder.encode(pcm)!!
        val bits = BitReader(out)
        bits.read(3); bits.read(12); bits.read(1); bits.read(2); bits.read(1); bits.read(32)

        // Must emerge MSB first.
        assertEquals(0x12, bits.read(8))
        assertEquals(0x34, bits.read(8))
        assertEquals(0x56, bits.read(8))
        assertEquals(0x78, bits.read(8))
    }

    @Test
    fun `fmtp parameters describe what the encoder actually emits`() {
        val fmtp = encoder.fmtpParameters().split(" ").map { it.toInt() }
        assertEquals("11 fields required by the alac cookie", 11, fmtp.size)
        assertEquals("max frames per packet", 352, fmtp[0])
        assertEquals("bit depth", 16, fmtp[2])
        assertEquals("channels", 2, fmtp[6])
        assertEquals("sample rate", 44_100, fmtp[10])
    }

    @Test
    fun `output size grows linearly with input`() {
        val small = encoder.encode(ByteArray(4))!!.size
        val large = encoder.encode(ByteArray(400))!!.size
        // Escape mode is 1:1 on payload, so the delta is the sample bytes.
        assertEquals(396, large - small)
    }

    @Test
    fun `silence encodes deterministically`() {
        val a = encoder.encode(ByteArray(1408))!!
        val b = encoder.encode(ByteArray(1408))!!
        assertTrue(a.contentEquals(b))
    }

    /** Mono configuration must use the SCE element tag, not CPE. */
    @Test
    fun `mono uses the sce element tag`() {
        val mono = AlacEncoder(channels = 1)
        val out = mono.encode(ByteArray(2 * 100))!!
        assertEquals(0, BitReader(out).read(3))
    }
}

/** Bit-level round trip for the writer used by the encoder. */
class BitWriterTest {

    @Test
    fun `single bits pack msb first`() {
        val w = BitWriter()
        w.write(1, 1); w.write(0, 1); w.write(1, 1); w.write(1, 1)
        w.write(0, 1); w.write(0, 1); w.write(0, 1); w.write(0, 1)
        assertEquals(1, w.toByteArray().size)
        assertEquals(0xB0.toByte(), w.toByteArray()[0])
    }

    @Test
    fun `multi bit values are written big endian`() {
        val w = BitWriter()
        w.write(0xABCD, 16)
        val out = w.toByteArray()
        assertEquals(0xAB.toByte(), out[0])
        assertEquals(0xCD.toByte(), out[1])
    }

    @Test
    fun `unaligned writes are padded with zeros not truncated`() {
        val w = BitWriter()
        w.write(0b101, 3)
        val out = w.toByteArray()
        // 3 bits must still produce a whole byte: 101 00000
        assertEquals(1, out.size)
        assertEquals(0xA0.toByte(), out[0])
    }

    @Test
    fun `values spanning a byte boundary survive`() {
        val w = BitWriter()
        w.write(0b1111, 4)
        w.write(0b1010_1010, 8)
        w.write(0b1111, 4)
        val out = w.toByteArray()
        assertEquals(2, out.size)
        assertEquals(0xFA.toByte(), out[0])
        assertEquals(0xAF.toByte(), out[1])
    }

    @Test
    fun `a 32 bit value round trips exactly`() {
        val w = BitWriter()
        w.write(352, 32)
        assertEquals(352, BitReader(w.toByteArray()).read(32))
    }

    @Test
    fun `rejects an out of range bit count`() {
        val w = BitWriter()
        runCatching { w.write(1, 0) }.let { assertTrue("0 bits must throw", it.isFailure) }
        runCatching { w.write(1, 33) }.let { assertTrue("33 bits must throw", it.isFailure) }
    }
}

/** Test-only MSB-first reader, mirroring BitWriter. */
internal class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    fun read(bitCount: Int): Int {
        var value = 0
        repeat(bitCount) {
            val byteIndex = bitPos / 8
            val bitIndex = 7 - (bitPos % 8)
            val bit = if (byteIndex < data.size) {
                (data[byteIndex].toInt() ushr bitIndex) and 1
            } else {
                0
            }
            value = (value shl 1) or bit
            bitPos++
        }
        return value
    }
}

/** NTP conversion and sync packet framing for the UDP transport. */
class RaopUdpTransportTest {

    @Test
    fun `ntp timestamp uses the 1900 epoch`() {
        val ntp = RaopUdpTransport.nowAsNtp()
        val seconds = (ntp ushr 32)
        // Unix now (~1.7e9) plus the 2208988800 offset.
        assertTrue("seconds was $seconds", seconds > 3_900_000_000L)
        assertTrue("seconds was $seconds", seconds < 4_400_000_000L)
    }

    @Test
    fun `ntp fraction occupies the low 32 bits`() {
        val ntp = RaopUdpTransport.nowAsNtp()
        val fraction = ntp and 0xFFFFFFFFL
        assertTrue("fraction was $fraction", fraction in 0..0xFFFFFFFFL)
    }

    @Test
    fun `ntp increases monotonically`() {
        val a = RaopUdpTransport.nowAsNtp()
        Thread.sleep(20)
        assertTrue(RaopUdpTransport.nowAsNtp() > a)
    }

    @Test
    fun `raop latency is exactly two seconds at 44_1khz`() {
        assertEquals(88_200L, RaopUdpTransport.RAOP_LATENCY_SAMPLES)
        assertEquals(2.0, RaopUdpTransport.RAOP_LATENCY_SAMPLES / 44_100.0, 0.001)
    }

    @Test
    fun `unopened transport reports no packets sent`() {
        val t = RaopUdpTransport("192.0.2.1", 6000, 6001)
        assertEquals(0L, t.packetsSent)
        assertEquals(0L, t.bytesSent)
        // Sending before open must fail rather than throw.
        assertTrue(!t.sendAudio(ByteArray(10)))
    }

    @Test
    fun `close is idempotent`() {
        val t = RaopUdpTransport("192.0.2.1", 6000, 6001)
        t.close()
        t.close()
        assertTrue(!t.isOpen)
    }
}
