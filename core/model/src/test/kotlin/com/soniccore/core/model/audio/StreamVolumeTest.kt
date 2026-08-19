package com.soniccore.core.model.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volume index arithmetic. `getStreamMaxVolume` is device- and stream-specific
 * (7 on some tablets, 30+ on some phones), so every conversion must round-trip
 * through the device's own range rather than assuming 15.
 */
class StreamVolumeTest {

    private fun volume(index: Int, max: Int, min: Int = 0) =
        StreamVolume(AudioStream.MUSIC, index, min, max, isMuted = false)

    @Test
    fun `percent is computed against the device range`() {
        assertEquals(0f, volume(0, 15).percent, 0.001f)
        assertEquals(1f, volume(15, 15).percent, 0.001f)
        assertEquals(0.5f, volume(15, 30).percent, 0.001f)
        // A 7-step device: index 3 is not 50%.
        assertEquals(3f / 7f, volume(3, 7).percent, 0.001f)
    }

    @Test
    fun `index for percent round-trips at the boundaries`() {
        listOf(7, 15, 16, 25, 30, 100).forEach { max ->
            val v = volume(0, max)
            assertEquals("min for $max", 0, v.indexForPercent(0f))
            assertEquals("max for $max", max, v.indexForPercent(1f))
        }
    }

    @Test
    fun `index for percent rounds to nearest rather than truncating`() {
        val v = volume(0, 15)
        // 0.5 * 15 = 7.5 -> 8, not 7. Truncation would bias every device quiet.
        assertEquals(8, v.indexForPercent(0.5f))
        assertEquals(1, v.indexForPercent(0.05f))
        assertEquals(14, v.indexForPercent(0.95f))
    }

    @Test
    fun `out of range percentages are clamped not wrapped`() {
        val v = volume(5, 15)
        assertEquals(0, v.indexForPercent(-3f))
        assertEquals(15, v.indexForPercent(9f))
        assertEquals(0f, volume(-5, 15).percent, 0.001f)
        assertEquals(1f, volume(99, 15).percent, 0.001f)
    }

    @Test
    fun `non zero minimum index is honoured`() {
        // API 28+ devices can report a minimum above zero.
        val v = volume(index = 2, max = 10, min = 2)
        assertEquals(0f, v.percent, 0.001f)
        assertEquals(8, v.stepCount)
        assertEquals(2, v.indexForPercent(0f))
        assertEquals(10, v.indexForPercent(1f))
        assertEquals(6, v.indexForPercent(0.5f))
    }

    @Test
    fun `step count never reports zero so the UI cannot divide by it`() {
        assertEquals(1, volume(0, 0).stepCount)
        assertEquals(1, volume(5, 5, min = 5).stepCount)
    }

    @Test
    fun `full round trip across every index of a real device range`() {
        val max = 15
        for (index in 0..max) {
            val v = volume(index, max)
            assertEquals(
                "index $index must survive percent conversion",
                index,
                v.indexForPercent(v.percent),
            )
        }
    }

    @Test
    fun `empty factory produces a safe default`() {
        val v = StreamVolume.empty(AudioStream.RING)
        assertEquals(AudioStream.RING, v.stream)
        assertTrue(v.stepCount >= 1)
        assertEquals(0f, v.percent, 0.001f)
    }
}
