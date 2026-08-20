package com.soniccore.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.soniccore.automation.AutomationEngine
import com.soniccore.service.SonicAudioService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hilt entry point used by receivers to resolve dependencies lazily.
 *
 * Receivers are deliberately NOT annotated with [dagger.hilt.android.AndroidEntryPoint].
 * The generated `Hilt_*Receiver` wrapper calls `inject()` on every `onReceive()` and
 * throws "The component was not created" whenever the component does not exist yet.
 * Under instrumented tests the component is only created per-test by `HiltAndroidRule`,
 * so a *real* broadcast (e.g. `MY_PACKAGE_REPLACED` delivered when the test run installs
 * the APK over a previous session) crashes the whole process before a single test runs.
 * Resolving through an [EntryPoint] inside `runCatching` lets a receiver degrade to a
 * no-op until DI is available — which is also the correct behaviour in production edge
 * cases such as direct boot or a process restored solely to receive a broadcast.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun automationEngine(): AutomationEngine
}

/** Resolves the engine without crashing the process when Hilt is not yet initialised. */
private fun resolveEngine(context: Context): AutomationEngine? = runCatching {
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ReceiverEntryPoint::class.java,
    ).automationEngine()
}.getOrNull()

/** Restarts the service after boot or an app update so automation keeps working. */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val engine = resolveEngine(context) ?: return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SonicAudioService::class.java),
            )
        }
        scope.launch { engine.onBootCompleted() }
    }
}

/** Wired/Bluetooth connect events that warrant an automation pass. */
class AudioDeviceReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioManager.ACTION_HEADSET_PLUG -> {
                val engine = resolveEngine(context) ?: return
                val plugged = intent.getIntExtra("state", -1) == 1
                scope.launch { engine.onHeadsetPlug(plugged) }
            }
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                val engine = resolveEngine(context) ?: return
                // Output is about to be pulled — treat as an unplug.
                scope.launch { engine.onHeadsetPlug(false) }
            }
            else -> {
                // ACL connect/disconnect: the registry flow picks up the detail.
                if (resolveEngine(context) == null) return
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
class UsbAttachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        if (resolveEngine(context) == null) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SonicAudioService::class.java),
            )
        }
    }
}