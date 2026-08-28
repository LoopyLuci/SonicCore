package com.soniccore.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.soniccore.MainActivity
import com.soniccore.R
import com.soniccore.SonicCoreApplication
import com.soniccore.automation.AutomationEngine
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.device.BluetoothInfoProvider
import com.soniccore.core.audio.effects.PlatformEffectsController
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.model.device.AudioDevice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Always-on audio control service.
 *
 * Android 8+ requires a foreground service (with `mediaPlayback` type on API 34+)
 * to keep observing device changes and running automation. Without this, device
 * connect events would be missed whenever the app is backgrounded.
 */
@AndroidEntryPoint
class SonicAudioService : Service() {

    @Inject lateinit var registry: AudioDeviceRegistry
    @Inject lateinit var profileEngine: ProfileEngine
    @Inject lateinit var automationEngine: AutomationEngine
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var platformEffects: PlatformEffectsController
    @Inject lateinit var bluetoothInfo: BluetoothInfoProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var knownKeys: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        // Create the channel defensively. SonicCoreApplication normally does this, but
        // it does NOT run under HiltTestApplication — and on a real device any missing
        // channel makes startForeground throw:
        //   RemoteServiceException: Bad notification for startForeground
        // which kills the process. Cheap insurance for a fatal failure mode.
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        bluetoothInfo.connectProxy()
        platformEffects.attach(PlatformEffectsController.GLOBAL_SESSION)
        automationEngine.start(serviceScope)

        observeDevices()
        observeActiveProfile()
    }

    /**
     * Detect newly connected devices and hand them to the profile engine so a bound
     * profile activates automatically.
     */
    private fun observeDevices() {
        registry.devices
            .onEach { devices ->
                val currentKeys = devices.map { it.stableKey }.toSet()
                val added = devices.filter { it.stableKey !in knownKeys }
                val removed = knownKeys - currentKeys
                knownKeys = currentKeys

                deviceRepository.rememberAll(devices)

                val autoApply = settingsStore.settings.first().autoApplyProfiles
                if (autoApply) {
                    added.filter { it.isConnected }.forEach { device ->
                        profileEngine.onDeviceConnected(device)
                        automationEngine.onDeviceConnected(device)
                    }
                }
                removed.forEach { key -> automationEngine.onDeviceDisconnected(key) }

                updateNotification(devices)
            }
            .launchIn(serviceScope)
    }

    private fun observeActiveProfile() {
        profileEngine.activeProfile
            .onEach { updateNotification(null) }
            .launchIn(serviceScope)
    }

    private fun updateNotification(devices: List<AudioDevice>?) {
        val profile = profileEngine.activeProfile.value
        val active = devices?.firstOrNull { it.connectionState == com.soniccore.core.model.device.ConnectionState.ACTIVE }
        val text = buildString {
            append(profile?.name ?: "No profile")
            active?.let { append(" · ${it.label}") }
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /**
     * Ensure the service channel exists before posting the foreground notification.
     * Idempotent — createNotificationChannel on an existing id is a no-op.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(SonicCoreApplication.CHANNEL_SERVICE) != null) return
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    SonicCoreApplication.CHANNEL_SERVICE,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.service_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SonicCoreApplication.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT_PROFILE -> serviceScope.launch { automationEngine.cycleProfile() }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away, restart the foreground service so device
        // listening and automation keep working. This is safe: the service is already
        // foregrounded and the restart is immediate, not a delayed alarm.
        runCatching {
            val restart = Intent(this, SonicAudioService::class.java)
            startForegroundService(restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        automationEngine.stop()
        platformEffects.release()
        bluetoothInfo.releaseProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.soniccore.action.STOP_SERVICE"
        const val ACTION_NEXT_PROFILE = "com.soniccore.action.NEXT_PROFILE"
    }
}

private typealias NotificationManager = android.app.NotificationManager
