package com.soniccore.core.streaming.airplay

import java.io.ByteArrayOutputStream

/**
 * Minimal ALAC encoder for RAOP.
 *
 * RAOP receivers accept ALAC frames, and the format permits an **uncompressed
 * escape mode**: a frame whose header sets the "uncompressed" flag carries raw
 * big-endian PCM. Every conformant AirPlay receiver must decode it, and it needs no
 * entropy coder — which is why it is the correct first implementation rather than a
 * half-finished Rice coder that produces subtly corrupt audio.
 *
 * Bit layout of a frame (from the ALAC magic-cookie specification):
 *
 *   3 bits   element instance tag (0 = SCE, 1 = CPE for stereo)
 *   12 bits  unused / reserved (zero)
 *   1 bit    has-32-bit-frame-length flag
 *   2 bits   uncompressed bytes count (0)
 *   1 bit    is-not-compressed flag  <- set to 1 for escape mode
 *   [32 bits frame length, only when the flag above is set]
 *   then interleaved raw samples, big-endian
 *
 * Trade-off stated plainly: escape mode is ~1.4 Mbit/s for 44.1 kHz stereo 16-bit,
 * versus roughly half that for real ALAC compression. On a LAN that is fine, and it
 * is honest: the audio is bit-exact, not approximated.
 */
internal class AlacEncoder(
    private val framesPerPacket: Int = AirPlayAudioStreamer.FRAMES_PER_PACKET,
    private val channels: Int = 2,
    private val bitDepth: Int = 16,
) {

    /**
     * Encode one packet of interleaved 16-bit little-endian PCM (as produced by
     * AudioRecord/AudioTrack) into an ALAC escape-mode frame.
     *
     * Returns null when the input is not a whole number of frames — a partial frame
     * would desynchronise the receiver's channel interleaving.
     */
    fun encode(pcmLittleEndian: ByteArray): ByteArray? {
        val bytesPerFrame = channels * (bitDepth / 8)
        if (pcmLittleEndian.isEmpty() || pcmLittleEndian.size % bytesPerFrame != 0) return null

        val frameCount = pcmLittleEndian.size / bytesPerFrame
        val writer = BitWriter()

        // Element tag: 1 (CPE) for stereo, 0 (SCE) for mono.
        writer.write(if (channels >= 2) 1 else 0, 3)
        writer.write(0, 12)          // reserved
        writer.write(1, 1)           // has 32-bit frame length
        writer.write(0, 2)           // uncompressed byte count
        writer.write(1, 1)           // NOT compressed -> escape mode
        writer.write(frameCount, 32) // explicit frame length

        // Samples go out big-endian; AudioRecord gives little-endian.
        var i = 0
        while (i + 1 < pcmLittleEndian.size) {
            val lo = pcmLittleEndian[i].toInt() and 0xFF
            val hi = pcmLittleEndian[i + 1].toInt() and 0xFF
            writer.write(hi, 8)
            writer.write(lo, 8)
            i += 2
        }

        return writer.toByteArray()
    }

    /**
     * The ALAC magic cookie advertised in the SDP `fmtp` line. Receivers use it to
     * configure their decoder, so it must match what [encode] actually produces.
     */
    fun fmtpParameters(): String = listOf(
        framesPerPacket,  // max frames per packet
        0,                // compatible version
        bitDepth,         // bit depth
        40,               // pb (rice history mult) — spec default
        10,               // mb (rice initial history)
        14,               // kb (rice k modifier)
        channels,         // channels
        255,              // max run
        0,                // max coded frame size
        0,                // average bit rate (0 = unknown)
        SAMPLE_RATE,
    ).joinToString(" ")

    companion object {
        const val SAMPLE_RATE = 44_100
    }
}

/**
 * Big-endian MSB-first bit writer.
 *
 * ALAC headers are not byte aligned, so the samples that follow start mid-byte.
 * Writing bytes directly would corrupt every frame.
 */
internal class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var bitsFilled = 0

    /** Write the low [bitCount] bits of [value], most significant bit first. */
    fun write(value: Int, bitCount: Int) {
        require(bitCount in 1..32) { "bitCount must be 1..32, was $bitCount" }
        for (shift in bitCount - 1 downTo 0) {
            val bit = (value ushr shift) and 1
            current = (current shl 1) or bit
            bitsFilled++
            if (bitsFilled == 8) {
                out.write(current and 0xFF)
                current = 0
                bitsFilled = 0
            }
        }
    }

    /** Flush with zero padding — a truncated final byte is a malformed frame. */
    fun toByteArray(): ByteArray {
        if (bitsFilled > 0) {
            current = current shl (8 - bitsFilled)
            out.write(current and 0xFF)
            current = 0
            bitsFilled = 0
        }
        return out.toByteArray()
    }

    val bitsWritten: Int get() = out.size() * 8 + bitsFilled
}
