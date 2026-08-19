package com.soniccore.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.soniccore.automation.AutomationEngine
import com.soniccore.service.SonicAudioService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restarts the service after boot or an app update so automation keeps working. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var automationEngine: AutomationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SonicAudioService::class.java),
            )
        }
        scope.launch { automationEngine.onBootCompleted() }
    }
}

/** Wired/Bluetooth connect events that warrant an automation pass. */
@AndroidEntryPoint
class AudioDeviceReceiver : BroadcastReceiver() {

    @Inject lateinit var automationEngine: AutomationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioManager.ACTION_HEADSET_PLUG -> {
                val plugged = intent.getIntExtra("state", -1) == 1
                scope.launch { automationEngine.onHeadsetPlug(plugged) }
            }
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                // Output is about to be pulled — treat as an unplug.
                scope.launch { automationEngine.onHeadsetPlug(false) }
            }
            else -> {
                // ACL connect/disconnect: the registry flow picks up the detail.
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SonicAudioService::class.java),
                    )
                }
            }
        }
    }
}

/** A USB DAC attaching should be able to trigger its profile. */
@AndroidEntryPoint
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SonicAudioService::class.java),
            )
        }
    }
}
