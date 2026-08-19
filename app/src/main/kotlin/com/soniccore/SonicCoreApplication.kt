package com.soniccore

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccore.core.data.presets.BuiltInEqPresets
import com.soniccore.core.data.presets.BuiltInProfiles
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.service.SonicAudioService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SonicCoreApplication : Application() {

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var presetRepository: EqPresetRepository
    @Inject lateinit var settingsStore: SettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        seedLibrary()
    }

    /** Seed built-in profiles and presets once, then start the service if wanted. */
    private fun seedLibrary() {
        appScope.launch {
            if (profileRepository.count() == 0) {
                profileRepository.replaceAll(BuiltInProfiles.all)
            }
            presetRepository.seedBuiltIns(BuiltInEqPresets.all)

            val settings = settingsStore.settings.first()
            if (settings.persistentNotification && settings.keepServiceAlive) {
                startAudioService()
            }
        }
    }

    fun startAudioService() {
        runCatching {
            val intent = Intent(this, SonicAudioService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Battery warnings, safe-listening notices and automation results"
        }

        manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
    }

    companion object {
        const val CHANNEL_SERVICE = "soniccore_service"
        const val CHANNEL_ALERTS = "soniccore_alerts"
    }
}
