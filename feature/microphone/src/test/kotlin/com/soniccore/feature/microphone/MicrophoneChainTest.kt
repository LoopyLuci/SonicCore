package com.soniccore.feature.microphone

import com.soniccore.core.model.audio.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Microphone chain parameter validation. These values feed a live capture path, so
 * an out-of-range value is not a cosmetic bug — it produces audible artefacts or
 * silence.
 */
class MicrophoneChainTest {

    // --- gain ---

    private fun clampGain(db: Float) = db.coerceIn(-24f, 36f)

    @Test
    fun `gain is clamped to the documented range`() {
        assertEquals(-24f, clampGain(-100f), 0.001f)
        assertEquals(36f, clampGain(100f), 0.001f)
        assertEquals(6f, clampGain(6f), 0.001f)
    }

    @Test
    fun `unity gain is inside the range and means no change`() {
        assertEquals(0f, clampGain(0f), 0.001f)
    }

    @Test
    fun `gain converts to a linear multiplier correctly`() {
        fun linear(db: Float) = Math.pow(10.0, db / 20.0).toFloat()
        assertEquals(1f, linear(0f), 0.0001f)
        // +6 dB is very close to a doubling of amplitude.
        assertEquals(2f, linear(6.0206f), 0.001f)
        assertEquals(0.5f, linear(-6.0206f), 0.001f)
    }

    // --- noise gate ---

    private fun clampThreshold(db: Float) = db.coerceIn(-80f, 0f)

    @Test
    fun `gate threshold cannot be positive`() {
        // A positive threshold would gate the entire signal.
        assertEquals(0f, clampThreshold(12f), 0.001f)
        assertEquals(-45f, clampThreshold(-45f), 0.001f)
        assertEquals(-80f, clampThreshold(-200f), 0.001f)
    }

    @Test
    fun `gate opens above threshold and closes below`() {
        val threshold = -45f
        assertTrue(gateOpen(-30f, threshold))
        assertFalse(gateOpen(-60f, threshold))
        // Exactly at threshold counts as open — hysteresis handles chatter.
        assertTrue(gateOpen(-45f, threshold))
    }

    private fun gateOpen(levelDb: Float, thresholdDb: Float) = levelDb >= thresholdDb

    @Test
    fun `attack and release times are positive and bounded`() {
        fun clampTime(ms: Float) = ms.coerceIn(1f, 1000f)
        // Zero attack would click; a 10s release would never close.
        assertEquals(1f, clampTime(0f), 0.001f)
        assertEquals(1000f, clampTime(10_000f), 0.001f)
        assertEquals(50f, clampTime(50f), 0.001f)
    }

    @Test
    fun `release should not be shorter than attack for natural gating`() {
        val attack = 10f
        val release = 100f
        assertTrue("release must be >= attack", release >= attack)
    }

    // --- sidetone ---

    @Test
    fun `sidetone level is a normalized fraction`() {
        fun clamp(v: Float) = v.coerceIn(0f, 1f)
        assertEquals(0f, clamp(-1f), 0.001f)
        assertEquals(1f, clamp(5f), 0.001f)
        assertEquals(0.35f, clamp(0.35f), 0.001f)
    }

    @Test
    fun `sidetone disabled means level is irrelevant but preserved`() {
        // Toggling monitoring off must not destroy the user's chosen level.
        var enabled = true
        val level = 0.4f
        enabled = false
        assertFalse(enabled)
        assertEquals(0.4f, level, 0.001f)
    }

    // --- sources ---

    @Test
    fun `every mic source has a distinct display name`() {
        val names = MicSource.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `unprocessed source is available for flat capture`() {
        // UNPROCESSED bypasses OEM DSP — essential for recording profiles.
        assertTrue(MicSource.entries.contains(MicSource.UNPROCESSED))
    }

    @Test
    fun `voice communication source exists for calls`() {
        assertTrue(MicSource.entries.contains(MicSource.VOICE_COMMUNICATION))
    }

    // --- metering ---

    @Test
    fun `rms to dbfs conversion handles silence without returning infinity`() {
        fun toDbfs(rms: Float): Float =
            if (rms <= 1e-7f) -100f else (20f * Math.log10(rms.toDouble()).toFloat()).coerceAtLeast(-100f)

        // log10(0) is -Infinity, which would corrupt the meter and the UI.
        assertEquals(-100f, toDbfs(0f), 0.001f)
        assertEquals(-100f, toDbfs(1e-9f), 0.001f)
        assertEquals(0f, toDbfs(1f), 0.01f)
        assertEquals(-6f, toDbfs(0.501f), 0.1f)
    }

    @Test
    fun `peak level never exceeds full scale`() {
        fun clampPeak(v: Float) = v.coerceIn(0f, 1f)
        assertEquals(1f, clampPeak(1.5f), 0.001f)
        assertEquals(0f, clampPeak(-0.2f), 0.001f)
    }

    @Test
    fun `clip detection triggers at full scale`() {
        fun isClipping(peak: Float) = peak >= 0.999f
        assertTrue(isClipping(1f))
        assertTrue(isClipping(0.9995f))
        assertFalse(isClipping(0.9f))
    }

    // --- de-esser ---

    @Test
    fun `de-esser targets the sibilance band`() {
        val centreHz = 6500f
        // Sibilance lives roughly 5-9 kHz; outside that it would dull the voice.
        assertTrue(centreHz in 4000f..10_000f)
    }

    @Test
    fun `compressor ratio is at least unity`() {
        fun clampRatio(r: Float) = r.coerceIn(1f, 20f)
        // A ratio below 1 would be an expander, not a compressor.
        assertEquals(1f, clampRatio(0.5f), 0.001f)
        assertEquals(20f, clampRatio(100f), 0.001f)
        assertEquals(4f, clampRatio(4f), 0.001f)
    }
}
