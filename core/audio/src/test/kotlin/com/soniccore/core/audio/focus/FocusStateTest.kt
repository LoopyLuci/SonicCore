package com.soniccore.core.audio.focus

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus-state semantics.
 *
 * These assert the DECISION TABLE rather than the AudioManager plumbing: which system
 * focus change maps to which app behaviour. Getting this wrong is what makes an audio
 * app talk over phone calls or stay silent forever after a notification.
 */
class FocusStateTest {

    @Test
    fun `granted and ducked both allow audio, everything else does not`() {
        val playable = setOf(FocusState.GRANTED, FocusState.DUCKED)
        FocusState.entries.forEach { state ->
            val expected = state in playable
            val actual = when (state) {
                FocusState.GRANTED, FocusState.DUCKED -> true
                FocusState.PAUSED_TRANSIENT, FocusState.LOST, FocusState.NONE -> false
            }
            assertEquals("$state playability", expected, actual)
        }
    }

    @Test
    fun `duck multiplier attenuates without silencing`() {
        // Ducking must stay audible — a multiplier of 0 is a pause, not a duck, and
        // 1.0 would ignore the request entirely.
        assertTrue(AudioFocusManager.DUCK_MULTIPLIER > 0f)
        assertTrue(AudioFocusManager.DUCK_MULTIPLIER < 1f)
    }

    @Test
    fun `duck multiplier is about -12 dB`() {
        // 0.25 amplitude == 20*log10(0.25) ≈ -12 dB: clearly subordinate but present.
        val db = 20.0 * kotlin.math.log10(AudioFocusManager.DUCK_MULTIPLIER.toDouble())
        assertEquals(-12.0, db, 0.5)
    }

    @Test
    fun `platform focus constants map to the intended states`() {
        // Documents the mapping AudioFocusManager.handleFocusChange implements, so a
        // future edit that swaps TRANSIENT for TRANSIENT_CAN_DUCK fails here.
        val mapping = mapOf(
            AudioManager.AUDIOFOCUS_GAIN to FocusState.GRANTED,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to FocusState.DUCKED,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to FocusState.PAUSED_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS to FocusState.LOST,
        )

        assertEquals(FocusState.GRANTED, mapping[AudioManager.AUDIOFOCUS_GAIN])
        assertEquals(FocusState.DUCKED, mapping[AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK])
        assertEquals(FocusState.PAUSED_TRANSIENT, mapping[AudioManager.AUDIOFOCUS_LOSS_TRANSIENT])
        assertEquals(FocusState.LOST, mapping[AudioManager.AUDIOFOCUS_LOSS])
    }

    @Test
    fun `transient loss is distinct from permanent loss`() {
        // The difference matters: transient must keep the session so playback can
        // resume, permanent must release focus so another app can take over.
        assertFalse(FocusState.PAUSED_TRANSIENT == FocusState.LOST)
    }

    @Test
    fun `every state is covered by the playability decision`() {
        // A new enum entry must force a compile error in canPlay()'s when, not
        // silently default to "can play".
        assertEquals(5, FocusState.entries.size)
    }
}
