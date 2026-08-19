package com.soniccore

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration test that runs on an emulator.
 *
 * Unit tests verify the math. Instrumented tests verify the UI. This verifies
 * that the app can actually talk to the Android audio stack on a real (or
 * emulated) device — the layer where OEM-specific breakage lives.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AudioStackIntegrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var audioManager: AudioManager

    @Test
    fun audioManagerIsAvailable() {
        hiltRule.inject()
        assertNotNull("AudioManager must be available", audioManager)
    }

    @Test
    fun canQueryAudioState() {
        hiltRule.inject()
        // These calls talk to the actual audio stack. On a broken OEM ROM they
        // can return unexpected values or throw — which is exactly what this
        // test is here to catch.
        val mode = audioManager.mode
        val isSpeakerOn = audioManager.isSpeakerphoneOn
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        assertTrue("mode should be valid", mode >= 0)
        assertTrue("volume should be non-negative", volume >= 0)
        assertTrue("max volume should be positive", maxVolume > 0)
        assertTrue("volume should not exceed max", volume <= maxVolume)
    }

    @Test
    fun applicationContextProvidesSystemService() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        assertNotNull("AUDIO_SERVICE must be retrievable", am)
    }
}
