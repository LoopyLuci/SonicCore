package com.soniccore.core.audio.session

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.soniccore.core.model.mixer.AppAudioSession
import com.soniccore.core.model.mixer.InstalledAudioApp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads other apps' media sessions to power the per-app mixer.
 *
 * Requires notification-listener access — that is the only sanctioned way to get
 * `MediaSessionManager.getActiveSessions`. Per-app volume is set through each
 * session's own volume provider, which apps may decline; [AppAudioSession.canControlVolume]
 * reflects that honestly rather than showing a slider that does nothing.
 */
@Singleton
class MediaSessionBridge @Inject constructor(
    private val context: Context,
    @com.soniccore.core.audio.di.NotificationListener
    private val notificationListenerComponent: ComponentName,
) {
    private val sessionManager: MediaSessionManager? =
        ContextCompat.getSystemService(context, MediaSessionManager::class.java)

    val hasNotificationAccess: Boolean
        get() = runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            enabled.split(':').any { it.contains(context.packageName) }
        }.getOrDefault(false)

    fun activeSessions(): List<AppAudioSession> {
        if (!hasNotificationAccess) return emptyList()
        val manager = sessionManager ?: return emptyList()
        return runCatching {
            manager.getActiveSessions(notificationListenerComponent).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    fun observeSessions(): Flow<List<AppAudioSession>> = callbackFlow {
        val manager = sessionManager
        if (manager == null || !hasNotificationAccess) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            trySend(controllers?.map { it.toDomain() } ?: emptyList())
        }

        runCatching {
            manager.addOnActiveSessionsChangedListener(listener, notificationListenerComponent)
            trySend(activeSessions())
        }

        awaitClose {
            runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        }
    }.conflate()

    /** Adjust one app's volume through its session. Returns false if it declines. */
    fun setSessionVolume(packageName: String, percent: Float): Boolean {
        val manager = sessionManager ?: return false
        if (!hasNotificationAccess) return false
        return runCatching {
            val controller = manager.getActiveSessions(notificationListenerComponent)
                .firstOrNull { it.packageName == packageName } ?: return false
            val info = controller.playbackInfo ?: return false
            if (info.volumeControl == android.media.VolumeProvider.VOLUME_CONTROL_FIXED) return false
            val target = (percent.coerceIn(0f, 1f) * info.maxVolume).toInt()
            controller.setVolumeTo(target, 0)
            true
        }.getOrDefault(false)
    }

    fun adjustSessionVolume(packageName: String, raise: Boolean): Boolean {
        val manager = sessionManager ?: return false
        return runCatching {
            val controller = manager.getActiveSessions(notificationListenerComponent)
                .firstOrNull { it.packageName == packageName } ?: return false
            controller.adjustVolume(if (raise) 1 else -1, 0)
            true
        }.getOrDefault(false)
    }

    fun transportControl(packageName: String, action: TransportAction): Boolean {
        val manager = sessionManager ?: return false
        return runCatching {
            val controller = manager.getActiveSessions(notificationListenerComponent)
                .firstOrNull { it.packageName == packageName } ?: return false
            when (action) {
                TransportAction.PLAY -> controller.transportControls.play()
                TransportAction.PAUSE -> controller.transportControls.pause()
                TransportAction.NEXT -> controller.transportControls.skipToNext()
                TransportAction.PREVIOUS -> controller.transportControls.skipToPrevious()
                TransportAction.STOP -> controller.transportControls.stop()
            }
            true
        }.getOrDefault(false)
    }

    /** Apps that declare RECORD_AUDIO or are known media apps — candidates for overrides. */
    fun installedAudioApps(): List<InstalledAudioApp> = runCatching {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { info ->
                val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                !isSystem || hasAudioPermission(pm, info.packageName)
            }
            .map { info ->
                InstalledAudioApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    hasAudioPermission = hasAudioPermission(pm, info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    private fun hasAudioPermission(pm: PackageManager, packageName: String): Boolean = runCatching {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.any {
            it == android.Manifest.permission.RECORD_AUDIO ||
                it == android.Manifest.permission.MODIFY_AUDIO_SETTINGS
        } ?: false
    }.getOrDefault(false)

    private fun MediaController.toDomain(): AppAudioSession {
        val info = playbackInfo
        val metadata = metadata
        val state = playbackState
        val appLabel = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        return AppAudioSession(
            packageName = packageName,
            appLabel = appLabel,
            sessionTag = runCatching { tag }.getOrNull(),
            playbackState = when (state?.state) {
                PlaybackState.STATE_PLAYING -> com.soniccore.core.model.mixer.PlaybackState.PLAYING
                PlaybackState.STATE_PAUSED -> com.soniccore.core.model.mixer.PlaybackState.PAUSED
                PlaybackState.STATE_STOPPED -> com.soniccore.core.model.mixer.PlaybackState.STOPPED
                PlaybackState.STATE_BUFFERING -> com.soniccore.core.model.mixer.PlaybackState.BUFFERING
                else -> com.soniccore.core.model.mixer.PlaybackState.NONE
            },
            trackTitle = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position,
            volumePercent = info?.let { pi ->
                if (pi.maxVolume > 0) pi.currentVolume.toFloat() / pi.maxVolume else null
            },
            volumeControlIsRelative =
            info?.volumeControl == android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE,
            canControlVolume =
            info != null && info.volumeControl != android.media.VolumeProvider.VOLUME_CONTROL_FIXED,
            hasArtwork = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) != null,
        )
    }
}

enum class TransportAction { PLAY, PAUSE, NEXT, PREVIOUS, STOP }
