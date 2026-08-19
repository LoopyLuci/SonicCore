package com.soniccore.core.dsp

import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class BiquadTest {

    private val sampleRate = 48_000

    @Test
    fun `peaking filter boosts at its centre frequency by the requested gain`() {
        val biquad = Biquad()
        biquad.configure(FilterType.PEAK, 1000.0, sampleRate, 1.41, 6.0)
        val db = biquad.magnitudeDbAt(1000.0, sampleRate)
        assertEquals("centre gain should match request", 6.0, db, 0.15)
    }

    @Test
    fun `peaking filter is transparent far from centre`() {
        val biquad = Biquad()
        biquad.configure(FilterType.PEAK, 1000.0, sampleRate, 4.32, 12.0)
        assertEquals(0.0, biquad.magnitudeDbAt(40.0, sampleRate), 0.6)
        assertEquals(0.0, biquad.magnitudeDbAt(16000.0, sampleRate), 0.6)
    }

    @Test
    fun `low shelf reaches full gain in the stopband and unity in the passband`() {
        val biquad = Biquad()
        biquad.configure(FilterType.LOW_SHELF, 200.0, sampleRate, 0.7, -8.0)
        assertEquals(-8.0, biquad.magnitudeDbAt(20.0, sampleRate), 0.6)
        assertEquals(0.0, biquad.magnitudeDbAt(8000.0, sampleRate), 0.5)
    }

    @Test
    fun `high shelf reaches full gain at the top of the band`() {
        val biquad = Biquad()
        biquad.configure(FilterType.HIGH_SHELF, 4000.0, sampleRate, 0.7, 5.0)
        assertEquals(5.0, biquad.magnitudeDbAt(19000.0, sampleRate), 0.6)
        assertEquals(0.0, biquad.magnitudeDbAt(100.0, sampleRate), 0.5)
    }

    @Test
    fun `low pass attenuates above cutoff and passes below`() {
        val biquad = Biquad()
        biquad.configure(FilterType.LOW_PASS, 1000.0, sampleRate, 0.707, 0.0)
        assertEquals(0.0, biquad.magnitudeDbAt(100.0, sampleRate), 0.4)
        assertEquals(-3.0, biquad.magnitudeDbAt(1000.0, sampleRate), 0.6)
        assertTrue(biquad.magnitudeDbAt(8000.0, sampleRate) < -30.0)
    }

    @Test
    fun `notch deeply attenuates exactly at centre`() {
        val biquad = Biquad()
        biquad.configure(FilterType.NOTCH, 1000.0, sampleRate, 8.0, 0.0)
        assertTrue("notch should be deep", biquad.magnitudeDbAt(1000.0, sampleRate) < -40.0)
    }

    @Test
    fun `insane parameters never produce NaN in the sample path`() {
        val biquad = Biquad()
        biquad.configure(FilterType.PEAK, -500.0, sampleRate, -3.0, 999.0)
        var out = 0.0
        repeat(512) { out = biquad.process(0.5) }
        assertFalse("output must stay finite", out.isNaN() || out.isInfinite())
    }

    @Test
    fun `frequency above nyquist is clamped instead of aliasing into NaN`() {
        val biquad = Biquad()
        biquad.configure(FilterType.PEAK, 96_000.0, sampleRate, 1.0, 6.0)
        var out = 0.0
        repeat(256) { out = biquad.process(0.25) }
        assertFalse(out.isNaN())
    }

    @Test
    fun `bypassed filter is bit-exact passthrough`() {
        val biquad = Biquad()
        biquad.configure(FilterType.PEAK, 1000.0, sampleRate, 1.0, 12.0)
        biquad.setBypassed(true)
        assertEquals(0.321, biquad.process(0.321), 0.0)
    }

    @Test
    fun `measured response of a boost matches the analytic curve`() {
        // Drive a real sine through the filter and compare measured gain to magnitudeDbAt.
        val biquad = Biquad()
        val freq = 1000.0
        biquad.configure(FilterType.PEAK, freq, sampleRate, 2.0, 9.0)

        val cycles = 200
        val totalSamples = (sampleRate / freq * cycles).toInt()
        var peakIn = 0.0
        var peakOut = 0.0
        for (n in 0 until totalSamples) {
            val input = sin(2.0 * Math.PI * freq * n / sampleRate) * 0.5
            val output = biquad.process(input)
            // Skip the transient while the filter settles.
            if (n > totalSamples / 2) {
                peakIn = maxOf(peakIn, abs(input))
                peakOut = maxOf(peakOut, abs(output))
            }
        }
        val measuredDb = 20.0 * kotlin.math.log10(peakOut / peakIn)
        assertEquals("measured must match analytic", 9.0, measuredDb, 0.3)
    }
}

class EqualizerEngineTest {

    @Test
    fun `auto preamp cancels the maximum positive gain`() {
        val settings = EqSettings(
            enabled = true,
            mode = EqMode.GRAPHIC_10,
            bands = listOf(
                EqBand("a", FilterType.PEAK, 100f, 9f, 1.41f),
                EqBand("b", FilterType.PEAK, 1000f, 3f, 1.41f),
            ),
        )
        val preamp = EqualizerEngine.autoPreampDb(settings)
        assertTrue("preamp must be negative when boosting: $preamp", preamp < -8f)
        assertTrue(preamp >= -24f)
    }

    @Test
    fun `flat eq needs no preamp`() {
        val preamp = EqualizerEngine.autoPreampDb(EqSettings.flat(EqMode.GRAPHIC_31).copy(enabled = true))
        assertEquals(0f, preamp, 0.01f)
    }

    @Test
    fun `a heavy boost does not clip the output because of auto preamp`() {
        val engine = EqualizerEngine(48_000, 2)
        engine.configure(
            EqSettings(
                enabled = true,
                autoPreamp = true,
                bands = listOf(EqBand("bass", FilterType.PEAK, 80f, 12f, 1.0f)),
            ),
        )
        val frames = 4096
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2.0 * Math.PI * 80.0 * i / 48_000) * 0.85).toFloat()
            buffer[i * 2] = v
            buffer[i * 2 + 1] = v
        }
        engine.processInterleaved(buffer, frames)
        assertTrue("all samples must stay in range", buffer.all { abs(it) <= 1.0f })
    }

    @Test
    fun `disabled engine leaves the buffer untouched`() {
        val engine = EqualizerEngine(48_000, 2)
        engine.configure(EqSettings(enabled = false, bands = listOf(EqBand("x", FilterType.PEAK, 1000f, 12f, 1f))))
        val buffer = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val copy = buffer.copyOf()
        engine.processInterleaved(buffer, 2)
        assertArrayEqualsExact(copy, buffer)
    }

    @Test
    fun `frequency response is log spaced and covers the audible band`() {
        val engine = EqualizerEngine(48_000, 2)
        engine.configure(EqSettings.flat(EqMode.GRAPHIC_31).copy(enabled = true))
        val response = engine.frequencyResponse(pointCount = 200)
        assertEquals(200, response.size)
        assertTrue(response.first().frequencyHz <= 21f)
        assertTrue(response.last().frequencyHz >= 19_000f)
        // Log spacing: the first gap is far smaller than the last.
        val firstGap = response[1].frequencyHz - response[0].frequencyHz
        val lastGap = response[199].frequencyHz - response[198].frequencyHz
        assertTrue("expected log spacing", lastGap > firstGap * 10)
    }

    @Test
    fun `stereo channels are filtered independently`() {
        val engine = EqualizerEngine(48_000, 2)
        engine.configure(
            EqSettings(enabled = true, autoPreamp = false, bands = listOf(EqBand("m", FilterType.PEAK, 1000f, 6f, 2f))),
        )
        val frames = 512
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            buffer[i * 2] = 0.4f       // left: signal
            buffer[i * 2 + 1] = 0f     // right: silence
        }
        engine.processInterleaved(buffer, frames)
        val rightEnergy = (0 until frames).sumOf { abs(buffer[it * 2 + 1]).toDouble() }
        assertEquals("silence in must stay silence out", 0.0, rightEnergy, 1e-6)
    }

    private fun assertArrayEqualsExact(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 0f)
    }
}

class FftTest {

    @Test
    fun `fft locates a pure tone in the correct bin`() {
        val size = 2048
        val sampleRate = 48_000
        val fft = Fft(size)
        val toneHz = 1000.0
        val samples = FloatArray(size) { sin(2.0 * Math.PI * toneHz * it / sampleRate).toFloat() * 0.5f }

        val spectrum = fft.magnitudeSpectrumDb(samples)
        val peakBin = spectrum.indices.maxBy { spectrum[it] }
        val peakFreq = fft.binFrequency(peakBin, sampleRate)

        assertEquals("peak should sit at the tone", toneHz.toFloat(), peakFreq, 30f)
    }

    @Test
    fun `full scale sine reads near zero dBFS`() {
        val size = 4096
        val fft = Fft(size)
        val samples = FloatArray(size) { sin(2.0 * Math.PI * 1000.0 * it / 48_000).toFloat() }
        val spectrum = fft.magnitudeSpectrumDb(samples)
        val peak = spectrum.max()
        assertEquals("windowed full-scale tone should read ~0 dBFS", 0f, peak, 1.5f)
    }

    @Test
    fun `silence reads at the floor`() {
        val fft = Fft(1024)
        val spectrum = fft.magnitudeSpectrumDb(FloatArray(1024))
        assertTrue(spectrum.all { it <= -100f })
    }

    @Test
    fun `non power of two size is rejected`() {
        try {
            Fft(1000)
            throw AssertionError("should have rejected size 1000")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `log band mapping is monotonic and covers the range`() {
        val size = 2048
        val fft = Fft(size)
        val samples = FloatArray(size) { sin(2.0 * Math.PI * 500.0 * it / 48_000).toFloat() * 0.5f }
        val spectrum = fft.magnitudeSpectrumDb(samples)
        val bands = Fft.toLogBands(spectrum, 48_000, size, bandCount = 32)
        assertEquals(32, bands.size)
        assertTrue("a 500Hz tone must light up a low-mid band", bands.take(20).max() > -40f)
    }

    @Test
    fun `smoother rises fast and falls slowly`() {
        val smoother = SpectrumSmoother(4)
        val loud = floatArrayOf(-6f, -6f, -6f, -6f)
        repeat(20) { smoother.process(loud) }
        val afterRise = smoother.process(loud)[0]
        assertTrue("should have risen close to target", afterRise > -12f)

        val silent = floatArrayOf(-120f, -120f, -120f, -120f)
        val afterOneQuietFrame = smoother.process(silent)[0]
        assertTrue("release must be gradual, not instant", afterOneQuietFrame > -80f)
    }
}
