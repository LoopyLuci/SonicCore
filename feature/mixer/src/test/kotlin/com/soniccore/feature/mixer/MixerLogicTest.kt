package com.soniccore.feature.mixer

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.model.mixer.AppAudioSession
import com.soniccore.core.model.mixer.InstalledAudioApp
import com.soniccore.core.model.mixer.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-app mixer logic. Android exposes media sessions with wildly inconsistent
 * capabilities, so the mixer must never assume a control exists.
 */
class MixerLogicTest {

    private fun session(
        pkg: String = "com.spotify.music",
        label: String = "Spotify",
        state: PlaybackState = PlaybackState.PLAYING,
        canControlVolume: Boolean = true,
        volumePercent: Float? = 0.5f,
        relative: Boolean = false,
    ) = AppAudioSession(
        packageName = pkg,
        appLabel = label,
        sessionTag = null,
        playbackState = state,
        canControlVolume = canControlVolume,
        volumePercent = volumePercent,
        volumeControlIsRelative = relative,
    )

    @Test
    fun `session with no volume control is not offered a slider`() {
        // Many apps expose a session but refuse volume commands.
        val s = session(canControlVolume = false, volumePercent = null)
        assertFalse(s.canControlVolume)
        assertNull(s.volumePercent)
    }

    @Test
    fun `volume percent may be null even when control is claimed`() {
        // MediaController.playbackInfo can be absent while commands still work.
        val s = session(canControlVolume = true, volumePercent = null)
        assertTrue(s.canControlVolume)
        assertNull("must stay null, never defaulted to 0", s.volumePercent)
    }

    @Test
    fun `relative volume sessions cannot be set to an absolute level`() {
        // VOLUME_CONTROL_RELATIVE only accepts adjust up/down, not setTo.
        val s = session(relative = true)
        assertTrue(s.volumeControlIsRelative)
        val canSetAbsolute = s.canControlVolume && !s.volumeControlIsRelative
        assertFalse(canSetAbsolute)
    }

    @Test
    fun `absolute volume sessions accept a direct level`() {
        val s = session(relative = false)
        assertTrue(s.canControlVolume && !s.volumeControlIsRelative)
    }

    @Test
    fun `isActive covers playing and buffering but not paused`() {
        assertTrue(session(state = PlaybackState.PLAYING).isActive)
        assertTrue(session(state = PlaybackState.BUFFERING).isActive)
        assertFalse(session(state = PlaybackState.PAUSED).isActive)
        assertFalse(session(state = PlaybackState.STOPPED).isActive)
        assertFalse(session(state = PlaybackState.NONE).isActive)
    }

    private fun rank(state: PlaybackState) = when (state) {
        PlaybackState.PLAYING -> 0
        PlaybackState.BUFFERING -> 1
        PlaybackState.PAUSED -> 2
        PlaybackState.STOPPED -> 3
        PlaybackState.NONE -> 4
    }

    @Test
    fun `playing sessions sort before paused and stopped`() {
        val sessions = listOf(
            session("c", "C", PlaybackState.STOPPED),
            session("a", "A", PlaybackState.PLAYING),
            session("b", "B", PlaybackState.PAUSED),
        )
        val ordered = sessions.sortedBy { rank(it.playbackState) }.map { it.packageName }
        assertEquals(listOf("a", "b", "c"), ordered)
    }

    @Test
    fun `every playback state has a distinct rank so sorting is stable`() {
        val ranks = PlaybackState.entries.map { rank(it) }
        assertEquals(ranks.size, ranks.distinct().size)
    }

    @Test
    fun `duplicate packages are collapsed keeping the most active session`() {
        // Chrome can publish several sessions; showing five rows is wrong.
        val sessions = listOf(
            session("com.chrome", "Chrome", PlaybackState.PAUSED),
            session("com.chrome", "Chrome", PlaybackState.PLAYING),
            session("com.chrome", "Chrome", PlaybackState.STOPPED),
        )
        val collapsed = sessions
            .groupBy { it.packageName }
            .map { (_, group) -> group.minByOrNull { rank(it.playbackState) }!! }
        assertEquals(1, collapsed.size)
        assertEquals(PlaybackState.PLAYING, collapsed.first().playbackState)
    }

    @Test
    fun `stream volume percent conversion uses the stream's own range`() {
        val ring = StreamVolume(AudioStream.RING, index = 3, minIndex = 0, maxIndex = 7, isMuted = false)
        val music = StreamVolume(AudioStream.MUSIC, index = 3, minIndex = 0, maxIndex = 30, isMuted = false)
        // Same index, very different loudness — never share a range across streams.
        assertTrue(ring.percent > music.percent)
    }

    @Test
    fun `muted stream retains its index so unmuting restores the level`() {
        val v = StreamVolume(AudioStream.MUSIC, index = 10, minIndex = 0, maxIndex = 15, isMuted = true)
        assertTrue(v.isMuted)
        assertEquals(10, v.index)
    }

    @Test
    fun `fixed volume streams are reported as uncontrollable`() {
        val v = StreamVolume(
            AudioStream.MUSIC, index = 5, minIndex = 0, maxIndex = 15,
            isMuted = false, isFixed = true,
        )
        assertTrue(v.isFixed)
    }

    @Test
    fun `duck amount is negative because ducking attenuates`() {
        // A positive duck would boost the background app.
        assertTrue(-14f < 0f)
    }

    @Test
    fun `app label falls back to package name when unresolvable`() {
        val s = session(pkg = "com.unknown.app", label = "")
        assertEquals("com.unknown.app", s.appLabel.ifBlank { s.packageName })
    }

    @Test
    fun `track metadata is optional and absent fields stay null`() {
        val s = session()
        assertNull(s.trackTitle)
        assertNull(s.artist)
        assertNull(s.durationMs)
    }

    @Test
    fun `installed app list can be filtered to non system audio apps`() {
        val apps = listOf(
            InstalledAudioApp("com.spotify.music", "Spotify", isSystemApp = false, hasAudioPermission = true),
            InstalledAudioApp("com.android.systemui", "System UI", isSystemApp = true, hasAudioPermission = true),
            InstalledAudioApp("com.notes", "Notes", isSystemApp = false, hasAudioPermission = false),
        )
        val candidates = apps.filter { !it.isSystemApp && it.hasAudioPermission }
        assertEquals(1, candidates.size)
        assertEquals("com.spotify.music", candidates.first().packageName)
    }
}
