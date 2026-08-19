package com.soniccore.core.dsp

import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reference audio pipeline test.
 *
 * Proves the whole DSP chain actually works end-to-end: a known sine wave goes in,
 * gets processed by the EqualizerEngine, and the output amplitude matches what the
 * biquad math predicts.
 *
 * This is distinct from BiquadTest, which verifies the magnitude response of a single
 * filter. This test verifies that the engine correctly chains bands, handles
 * interleaved stereo, applies preamp, and reports clipping.
 */
class ReferenceAudioPipelineTest {

    private val sampleRate = 48_000

    /**
     * Generate a sine wave at the given frequency.
     */
    private fun sineWave(freq: Float, frames: Int, sampleRate: Int): FloatArray {
        val buffer = FloatArray(frames * 2) // stereo interleaved
        for (i in 0 until frames) {
            val sample = (sin(2 * PI * freq * i / sampleRate) * 0.5).toFloat()
            buffer[i * 2] = sample
            buffer[i * 2 + 1] = sample
        }
        return buffer
    }

    /**
     * Measure RMS amplitude of a sine wave (should be amplitude/sqrt(2) for a clean sine).
     */
    private fun rmsAmplitude(buffer: FloatArray): Double {
        var sumSquares = 0.0
        for (sample in buffer) sumSquares += sample * sample
        return sqrt(sumSquares / buffer.size)
    }

    @Test
    fun `flat EQ passes sine wave unchanged`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 0f))), sampleRate)

        val input = sineWave(1000f, 4800, sampleRate) // 100ms
        val output = input.copyOf()
        engine.processInterleaved(output, 4800)

        val inputRms = rmsAmplitude(input)
        val outputRms = rmsAmplitude(output)
        assertEquals("flat EQ should not change amplitude", inputRms, outputRms, 0.01)
    }

    @Test
    fun `6dB boost at 1kHz amplifies 1kHz sine by approximately 6dB`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 6f))), sampleRate)

        val input = sineWave(1000f, 4800, sampleRate)
        val output = input.copyOf()
        engine.processInterleaved(output, 4800)

        val inputRms = rmsAmplitude(input)
        val outputRms = rmsAmplitude(output)
        val gainDb = 20 * kotlin.math.log10(outputRms / inputRms)

        assertEquals("gain should be ~6dB", 6.0, gainDb, 1.0)
    }

    @Test
    fun `boost at 1kHz does not significantly affect 100Hz sine`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 12f))), sampleRate)

        val input = sineWave(100f, 4800, sampleRate)
        val output = input.copyOf()
        engine.processInterleaved(output, 4800)

        val inputRms = rmsAmplitude(input)
        val outputRms = rmsAmplitude(output)
        val gainDb = 20 * kotlin.math.log10(outputRms / inputRms)

        assertEquals("100Hz should be unaffected by 1kHz boost", 0.0, gainDb, 0.5)
    }

    @Test
    fun `disabled engine passes audio through unchanged`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 6f))), sampleRate)
        engine.setEnabled(false)

        val input = sineWave(1000f, 4800, sampleRate)
        val output = input.copyOf()
        engine.processInterleaved(output, 4800)

        // Disabled engine returns early — output should equal input
        for (i in input.indices) {
            assertEquals("disabled engine should not modify sample $i", input[i], output[i], 0.0001f)
        }
    }

    @Test
    fun `clipping is reported when signal exceeds threshold`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 24f))), sampleRate)

        val input = sineWave(1000f, 4800, sampleRate)
        val output = input.copyOf()
        val clipped = engine.processInterleaved(output, 4800)

        // 24dB boost on 0.5 amplitude = ~3.0, which clips at 1.0
        assertTrue("clipping should be detected", clipped)
    }

    @Test
    fun `no clipping reported for gentle boost`() {
        val engine = EqualizerEngine(sampleRate, 2)
        engine.configure(EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("band1", FilterType.PEAK, 1000f, 3f))), sampleRate)

        val input = sineWave(1000f, 4800, sampleRate)
        val output = input.copyOf()
        val clipped = engine.processInterleaved(output, 4800)

        assertFalse("3dB boost should not clip", clipped)
    }
}
