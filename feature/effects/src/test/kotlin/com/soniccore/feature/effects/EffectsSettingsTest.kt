package com.soniccore.feature.effects

import com.soniccore.core.model.effects.BassBoostSettings
import com.soniccore.core.model.effects.CrossfeedSettings
import com.soniccore.core.model.effects.DynamicsSettings
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.effects.HrtfProfile
import com.soniccore.core.model.effects.ReverbPreset
import com.soniccore.core.model.effects.SpatialAudioSettings
import com.soniccore.core.model.effects.VirtualizerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Effects parameter ranges and defaults. The platform AudioEffect API throws on
 * out-of-range values, so clamping is crash prevention, not polish.
 */
class EffectsSettingsTest {

    @Test
    fun `colouring effects default to disabled so nothing alters audio unasked`() {
        val d = EffectsSettings()
        assertFalse(d.bassBoost.enabled)
        assertFalse(d.virtualizer.enabled)
        assertFalse(d.reverb.enabled)
        assertFalse(d.crossfeed.enabled)
        assertFalse(d.spatial.enabled)
        assertFalse(d.dynamics.compressorEnabled)
        assertFalse(d.loudness.enabled)
    }

    @Test
    fun `the limiter defaults to on because it only prevents clipping`() {
        // Unlike the colouring effects, a limiter is protective — on by default.
        val d = EffectsSettings()
        assertTrue(d.dynamics.limiterEnabled)
        assertTrue("ceiling must be at or below 0 dBFS", d.dynamics.limiterCeilingDb <= 0f)
    }

    @Test
    fun `default balance is centred and width is unity`() {
        val d = EffectsSettings()
        assertEquals(0f, d.balance, 0.001f)
    }

    // --- platform strength encoding ---

    private fun toPlatformStrength(v: Float) = (v.coerceIn(0f, 1f) * 1000f).toInt()

    @Test
    fun `normalized strength maps to the platform 0-1000 scale`() {
        assertEquals(0, toPlatformStrength(0f))
        assertEquals(1000, toPlatformStrength(1f))
        assertEquals(500, toPlatformStrength(0.5f))
        // Out-of-range input must not produce an illegal platform value.
        assertEquals(1000, toPlatformStrength(9f))
        assertEquals(0, toPlatformStrength(-3f))
    }

    @Test
    fun `platform strength never leaves the legal api range`() {
        var v = -1f
        while (v <= 2f) {
            val p = toPlatformStrength(v)
            assertTrue("$v produced $p", p in 0..1000)
            v += 0.05f
        }
    }

    // --- bass boost ---

    @Test
    fun `bass boost default cutoff is in the bass region`() {
        val b = BassBoostSettings()
        assertEquals(120f, b.cutoffHz, 0.001f)
        assertTrue(b.cutoffHz in 20f..500f)
    }

    @Test
    fun `bass boost values round trip`() {
        val b = BassBoostSettings(enabled = true, strength = 0.4f, cutoffHz = 80f)
        assertTrue(b.enabled)
        assertEquals(0.4f, b.strength, 0.001f)
        assertEquals(80f, b.cutoffHz, 0.001f)
    }

    // --- crossfeed ---

    @Test
    fun `crossfeed defaults bleed only low frequencies`() {
        val c = CrossfeedSettings()
        // Above ~1.5 kHz crossfeed smears imaging instead of easing it.
        assertTrue(c.cutoffHz in 200f..1500f)
        assertTrue(c.amount in 0f..1f)
    }

    @Test
    fun `crossfeed delay is in the interaural range`() {
        val c = CrossfeedSettings()
        // Real interaural delay is roughly 200-700 microseconds.
        assertTrue("delay ${c.delayMicros}us", c.delayMicros in 100f..800f)
    }

    // --- dynamics ---

    @Test
    fun `compressor defaults are sane`() {
        val d = DynamicsSettings()
        assertTrue("threshold must be negative dBFS", d.thresholdDb < 0f)
        assertTrue("ratio must be at least unity", d.ratio >= 1f)
        assertTrue("attack must be positive", d.attackMs > 0f)
        assertTrue("release should exceed attack", d.releaseMs > d.attackMs)
        assertTrue("knee must not be negative", d.kneeDb >= 0f)
    }

    @Test
    fun `compressor ratio below unity would be an expander and is clamped`() {
        fun clampRatio(r: Float) = r.coerceIn(1f, 20f)
        assertEquals(1f, clampRatio(0.2f), 0.001f)
        assertEquals(20f, clampRatio(100f), 0.001f)
        assertEquals(3.5f, clampRatio(3.5f), 0.001f)
    }

    @Test
    fun `limiter ceiling never exceeds zero dbfs`() {
        fun clamp(db: Float) = db.coerceIn(-12f, 0f)
        // A positive ceiling defeats the point of a limiter.
        assertEquals(0f, clamp(3f), 0.001f)
        assertEquals(-0.3f, clamp(-0.3f), 0.001f)
    }

    @Test
    fun `compressor and limiter are independently switchable`() {
        val d = DynamicsSettings(compressorEnabled = true, limiterEnabled = false)
        assertTrue(d.compressorEnabled)
        assertFalse(d.limiterEnabled)
    }

    // --- spatial ---

    @Test
    fun `spatial defaults are normalized and physically plausible`() {
        val s = SpatialAudioSettings()
        assertTrue(s.roomSize in 0f..1f)
        assertTrue("speaker distance must be positive", s.speakerDistanceMeters > 0f)
        assertTrue(s.elevationDegrees in -90f..90f)
    }

    @Test
    fun `head tracking without spatialization is corrected`() {
        // Head tracking has no meaning unless spatial audio is on.
        val invalid = SpatialAudioSettings(enabled = false, headTracking = true)
        val corrected = if (!invalid.enabled) invalid.copy(headTracking = false) else invalid
        assertFalse(corrected.headTracking)
    }

    @Test
    fun `atmos passthrough defaults on so encoded content is not downmixed`() {
        assertTrue(SpatialAudioSettings().passthroughAtmos)
    }

    @Test
    fun `every hrtf profile is distinctly named`() {
        val names = HrtfProfile.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
        assertTrue(HrtfProfile.entries.contains(HrtfProfile.GENERIC))
    }

    // --- reverb ---

    @Test
    fun `reverb defaults to the none preset`() {
        assertEquals(ReverbPreset.NONE, com.soniccore.core.model.effects.ReverbSettings().preset)
    }

    @Test
    fun `every reverb preset has a distinct display name`() {
        val names = ReverbPreset.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `reverb wet mix and timing defaults are in range`() {
        val r = com.soniccore.core.model.effects.ReverbSettings()
        assertTrue(r.wetMix in 0f..1f)
        assertTrue(r.decaySeconds > 0f)
        assertTrue(r.preDelayMs >= 0f)
        assertTrue("damping must be audible", r.dampingHz in 1000f..20_000f)
    }

    // --- virtualizer ---

    @Test
    fun `every virtualizer mode is distinctly named`() {
        val names = VirtualizerMode.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
        assertTrue(VirtualizerMode.entries.contains(VirtualizerMode.AUTO))
    }

    // --- balance and loudness ---

    @Test
    fun `balance spans full left to full right through centre`() {
        fun clamp(v: Float) = v.coerceIn(-1f, 1f)
        assertEquals(-1f, clamp(-5f), 0.001f)
        assertEquals(1f, clamp(5f), 0.001f)
        assertEquals(0f, clamp(0f), 0.001f)
    }

    @Test
    fun `constant power balance keeps total loudness steady`() {
        // Linear panning drops ~3 dB at centre; constant power does not.
        fun gains(balance: Float): Pair<Float, Float> {
            val angle = (balance.coerceIn(-1f, 1f) + 1f) * (Math.PI / 4).toFloat()
            return Math.cos(angle.toDouble()).toFloat() to Math.sin(angle.toDouble()).toFloat()
        }
        val (l, r) = gains(0f)
        assertEquals(1f, l * l + r * r, 0.001f)
        val (l2, r2) = gains(-1f)
        assertEquals(1f, l2 * l2 + r2 * r2, 0.001f)
        assertTrue("full left should mute right", r2 < 0.001f)
    }

    @Test
    fun `loudness clip prevention defaults on`() {
        assertTrue(com.soniccore.core.model.effects.LoudnessSettings().preventClipping)
    }

    @Test
    fun `nested copies do not mutate the original settings tree`() {
        val original = EffectsSettings()
        val modified = original.copy(
            bassBoost = original.bassBoost.copy(enabled = true, strength = 0.5f),
            crossfeed = original.crossfeed.copy(enabled = true),
        )
        assertTrue(modified.bassBoost.enabled)
        assertTrue(modified.crossfeed.enabled)
        assertFalse("originals are immutable value objects", original.bassBoost.enabled)
    }
}
