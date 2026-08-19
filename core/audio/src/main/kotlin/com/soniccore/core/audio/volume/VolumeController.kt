package com.soniccore.core.audio.volume

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.StreamVolume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes platform stream volumes.
 *
 * Two hard platform truths shape this class:
 *  - `getStreamMaxVolume` is device *and* stream specific (7 to 30+); never assume 15.
 *  - The stream index cannot be subdivided. "100 volume steps" is only achievable
 *    with a software gain stage on audio we own — [StreamVolume.stepCount] reports
 *    the honest hardware granularity so the UI can say so.
 */
@Singleton
class VolumeController @Inject constructor(
    private val context: Context,
    private val audioManager: AudioManager,
) {

    fun streamOf(stream: AudioStream): Int = when (stream) {
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
        AudioStream.DTMF -> AudioManager.STREAM_DTMF
        AudioStream.ACCESSIBILITY ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) STREAM_ACCESSIBILITY else AudioManager.STREAM_MUSIC
    }

    fun read(stream: AudioStream): StreamVolume {
        val platformStream = streamOf(stream)
        val max = runCatching { audioManager.getStreamMaxVolume(platformStream) }.getOrDefault(15)
        val min = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(platformStream)
            } else {
                0
            }
        }.getOrDefault(0)
        val index = runCatching { audioManager.getStreamVolume(platformStream) }.getOrDefault(0)
        val muted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(platformStream)
            } else {
                index == 0
            }
        }.getOrDefault(false)

        return StreamVolume(
            stream = stream,
            index = index,
            minIndex = min,
            maxIndex = max,
            isMuted = muted,
            isFixed = audioManager.isVolumeFixed,
        )
    }

    fun readAll(): Map<AudioStream, StreamVolume> =
        AudioStream.entries.associateWith { read(it) }

    /**
     * Set absolute index. Returns false when the platform refuses — notably
     * RING/NOTIFICATION while Do Not Disturb is active without policy access.
     */
    fun setIndex(stream: AudioStream, index: Int, showUi: Boolean = false): Boolean {
        val platformStream = streamOf(stream)
        val current = read(stream)
        val clamped = index.coerceIn(current.minIndex, current.maxIndex)
        return runCatching {
            audioManager.setStreamVolume(
                platformStream,
                clamped,
                if (showUi) AudioManager.FLAG_SHOW_UI else 0,
            )
            true
        }.getOrElse { false }
    }

    fun setPercent(stream: AudioStream, percent: Float, showUi: Boolean = false): Boolean {
        val current = read(stream)
        return setIndex(stream, current.indexForPercent(percent), showUi)
    }

    fun adjust(stream: AudioStream, raise: Boolean, showUi: Boolean = true): Boolean = runCatching {
        audioManager.adjustStreamVolume(
            streamOf(stream),
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            if (showUi) AudioManager.FLAG_SHOW_UI else 0,
        )
        true
    }.getOrElse { false }

    fun setMuted(stream: AudioStream, muted: Boolean): Boolean = runCatching {
        audioManager.adjustStreamVolume(
            streamOf(stream),
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
        true
    }.getOrElse { false }

    fun toggleMute(stream: AudioStream): Boolean = runCatching {
        audioManager.adjustStreamVolume(streamOf(stream), AudioManager.ADJUST_TOGGLE_MUTE, 0)
        true
    }.getOrElse { false }

    /** True when changing RING/NOTIFICATION would throw due to DND policy. */
    fun requiresNotificationPolicyAccess(stream: AudioStream): Boolean {
        if (stream != AudioStream.RING && stream != AudioStream.NOTIFICATION) return false
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return false
        val dndActive = runCatching {
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        val hasAccess = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
        return dndActive && !hasAccess
    }

    /**
     * Volume changes are broadcast on an undocumented-but-stable action; there is no
     * public listener API below API 34, so this is the pragmatic path. Paired with a
     * re-read so we never trust the extras.
     */
    fun observe(stream: AudioStream): Flow<StreamVolume> = observeAll()
        .map { it[stream] ?: read(stream) }
        .distinctUntilChanged()

    fun observeAll(): Flow<Map<AudioStream, StreamVolume>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(readAll())
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_VOLUME_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(ACTION_STREAM_DEVICES_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        trySend(readAll())
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.conflate()

    val isVolumeFixed: Boolean get() = audioManager.isVolumeFixed

    /**
     * Hardware step count for a stream. The UI must not offer finer granularity
     * than this without engaging software gain.
     */
    fun hardwareStepCount(stream: AudioStream): Int = read(stream).stepCount

    companion object {
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val ACTION_STREAM_DEVICES_CHANGED = "android.media.STREAM_DEVICES_CHANGED_ACTION"
        private const val STREAM_ACCESSIBILITY = 10
    }
}
