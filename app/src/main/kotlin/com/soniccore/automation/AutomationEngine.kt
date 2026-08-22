package com.soniccore.automation

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.soniccore.MainActivity
import com.soniccore.SonicCoreApplication
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.audio.volume.VolumeController
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.AutomationRepository
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.ConditionLogic
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleCondition
import com.soniccore.core.model.automation.RuleTrigger
import com.soniccore.core.model.device.AudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates automation rules and executes their actions.
 *
 * Rules are matched by trigger, gated by conditions, ordered by priority, and
 * rate-limited by each rule's cooldown so a chatty broadcast cannot thrash the
 * audio state.
 */
@Singleton
class AutomationEngine @Inject constructor(
    private val context: Context,
    private val repository: AutomationRepository,
    private val profileRepository: ProfileRepository,
    private val presetRepository: EqPresetRepository,
    private val profileEngine: ProfileEngine,
    private val volumeController: VolumeController,
    private val router: AudioRouter,
    private val registry: AudioDeviceRegistry,
) {
    private var scope: CoroutineScope? = null
    private var timeCheckJob: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        startTimeWatcher(scope)
    }

    fun stop() {
        timeCheckJob?.cancel()
        timeCheckJob = null
        scope = null
    }

    /** Poll for time-window rules once a minute — cheap and Doze-tolerant. */
    private fun startTimeWatcher(scope: CoroutineScope) {
        timeCheckJob = scope.launch {
            while (true) {
                evaluate { trigger ->
                    trigger is RuleTrigger.TimeWindow && isInWindow(trigger)
                }
                delay(60_000L)
            }
        }
    }

    private fun isInWindow(trigger: RuleTrigger.TimeWindow): Boolean {
        val now = LocalTime.now()
        val minuteOfDay = now.hour * 60 + now.minute
        val today = java.time.LocalDate.now().dayOfWeek.value
        if (today !in trigger.daysOfWeek) return false
        return if (trigger.startMinuteOfDay <= trigger.endMinuteOfDay) {
            minuteOfDay in trigger.startMinuteOfDay..trigger.endMinuteOfDay
        } else {
            // Window crosses midnight.
            minuteOfDay >= trigger.startMinuteOfDay || minuteOfDay <= trigger.endMinuteOfDay
        }
    }

    suspend fun onDeviceConnected(device: AudioDevice) {
        evaluate { trigger ->
            trigger is RuleTrigger.DeviceConnected && trigger.deviceKey == device.stableKey
        }
    }

    suspend fun onDeviceDisconnected(deviceKey: String) {
        evaluate { trigger ->
            trigger is RuleTrigger.DeviceDisconnected && trigger.deviceKey == deviceKey
        }
    }

    suspend fun onHeadsetPlug(plugged: Boolean) {
        evaluate { trigger ->
            trigger is RuleTrigger.HeadsetPlugged && trigger.plugged == plugged
        }
    }

    suspend fun onBootCompleted() {
        evaluate { it is RuleTrigger.BootCompleted }
    }

    suspend fun onScreenOff() {
        evaluate { it == RuleTrigger.ScreenOff }
    }

    suspend fun onCallStateChanged(ringing: Boolean, offHook: Boolean) {
        evaluate { trigger ->
            trigger is RuleTrigger.CallStateChanged &&
                trigger.ringing == ringing &&
                trigger.offHook == offHook
        }
    }

    suspend fun onBatteryLevel(percent: Int) {
        evaluate { trigger ->
            trigger is RuleTrigger.BatteryBelow && percent < trigger.percent
        }
    }

    suspend fun runManually(ruleId: String) {
        val rule = repository.get(ruleId) ?: return
        execute(rule)
    }

    /** Cycle to the next profile — used by the Quick Settings tile and widget. */
    suspend fun cycleProfile() {
        val profiles = profileRepository.getAll().sortedBy { it.name }
        if (profiles.isEmpty()) return
        val activeIndex = profiles.indexOfFirst { it.isActive }
        val next = profiles[(activeIndex + 1).mod(profiles.size)]
        profileEngine.apply(next)
    }

    private suspend fun evaluate(matches: (RuleTrigger) -> Boolean) {
        val now = System.currentTimeMillis()
        repository.enabledRules()
            .filter { matches(it.trigger) }
            .filterNot { repository.isInCooldown(it, now) }
            .sortedByDescending { it.priority }
            .forEach { rule ->
                if (conditionsHold(rule)) execute(rule)
            }
    }

    private suspend fun conditionsHold(rule: AutomationRule): Boolean {
        if (rule.conditions.isEmpty()) return true
        val results = rule.conditions.map { evaluateCondition(it) }
        return when (rule.conditionLogic) {
            ConditionLogic.ALL -> results.all { it }
            ConditionLogic.ANY -> results.any { it }
        }
    }

    private suspend fun evaluateCondition(condition: RuleCondition): Boolean = when (condition) {
        is RuleCondition.ActiveOutputIs -> router.activeOutput()?.stableKey == condition.deviceKey
        is RuleCondition.ActiveProfileIs -> profileEngine.activeProfile.value?.id == condition.profileId
        is RuleCondition.TimeBetween -> {
            val now = LocalTime.now()
            val minuteOfDay = now.hour * 60 + now.minute
            if (condition.startMinuteOfDay <= condition.endMinuteOfDay) {
                minuteOfDay in condition.startMinuteOfDay..condition.endMinuteOfDay
            } else {
                minuteOfDay >= condition.startMinuteOfDay || minuteOfDay <= condition.endMinuteOfDay
            }
        }
        is RuleCondition.BatteryAbove -> batteryPercent() > condition.percent
        is RuleCondition.MediaIsPlaying -> condition.playing // Verified by the caller's context.
        is RuleCondition.IsCharging -> isCharging() == condition.charging
        is RuleCondition.Not -> !evaluateCondition(condition.inner)
    }

    private fun batteryPercent(): Int = runCatching {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }.getOrDefault(100)

    private fun isCharging(): Boolean = runCatching {
        val manager = context.getSystemService(android.os.BatteryManager::class.java)
        manager?.isCharging ?: false
    }.getOrDefault(false)

    private suspend fun execute(rule: AutomationRule) {
        rule.actions.forEach { action ->
            when (action) {
                is RuleAction.ActivateProfile ->
                    profileRepository.get(action.profileId)?.let { profileEngine.apply(it) }

                is RuleAction.SetStreamVolume ->
                    volumeController.setPercent(action.stream, action.percent)

                is RuleAction.SetMute ->
                    volumeController.setMuted(action.stream, action.muted)

                is RuleAction.RouteOutput ->
                    registry.snapshotPlatformDevices()
                        .firstOrNull { it.stableKey == action.deviceKey }
                        ?.let { router.routeCommunicationTo(it) }

                is RuleAction.RouteInput ->
                    registry.snapshotPlatformDevices()
                        .firstOrNull { it.stableKey == action.deviceKey && it.canInput }
                        ?.let { router.routeCommunicationTo(it) }

                is RuleAction.ApplyEqPreset ->
                    presetRepository.get(action.presetId)?.let {
                        profileEngine.applyEqualizerOnly(it.settings)
                    }

                is RuleAction.SetEqEnabled -> {
                    val active = profileEngine.activeProfile.value
                    val settings = active?.eq ?: com.soniccore.core.model.eq.EqSettings()
                    profileEngine.applyEqualizerOnly(settings.copy(enabled = action.enabled))
                }

                is RuleAction.SetAnc -> Unit // Vendor-specific; no public Android API.

                is RuleAction.Notify -> postAlert(action.title, action.message)

                is RuleAction.OpenApp -> openAppIfAllowed()

                is RuleAction.Delay -> delay(action.millis.coerceIn(0L, 30_000L))

                is RuleAction.FadeVolume -> fadeVolume(action)
            }
        }
        repository.recordFire(rule.id)
    }

    /**
     * Brings the app to the foreground, but only when the user has granted the SYSTEM_ALERT_WINDOW
     * permission ("Display over other apps").
     *
     * That permission is the permanent, cross-OEM exemption from Android's background
     * activity-start restriction (`ActivityStarter.restrictBackgroundActivity`). Without it, a
     * raw `startActivity` from a background rule is silently aborted on HyperOS/MIUI and other
     * aggressive ROMs. Granting overlay makes the launch allowed everywhere — otherwise the
     * action degrades to a no-op rather than throwing, so a rule with an ungranted "Open app"
     * step still runs its other actions.
     */
    private fun openAppIfAllowed() {
        if (!Settings.canDrawOverlays(context)) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    /**
     * Surfaces an automation result to the user as a real notification.
     *
     * This is deliberately the ONLY UI path for background automation output. An app that
     * tries to `startActivity` directly from a receiver or a coroutine fired by a background
     * rule is silently blocked on aggressive OEMs (HyperOS/MIUI, some Samsung/OnePlus builds)
     * whose SmartPower layers abort "background activity starts". A notification's content
     * intent, by contrast, is user-mediated: the user taps it, which is always a trusted start
     * on every ROM. So automation results reach the user everywhere, with the added benefit
     * that they remain visible in the shade and are dismissible.
     */
    private fun postAlert(title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            title.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, SonicCoreApplication.CHANNEL_ALERTS)
            .setSmallIcon(com.soniccore.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(title.hashCode(), notification)
    }

    private suspend fun fadeVolume(action: RuleAction.FadeVolume) {
        val current = volumeController.read(action.stream)
        val steps = 20
        val stepDelay = (action.durationMs / steps).coerceAtLeast(10L)
        val startPercent = current.percent
        for (i in 1..steps) {
            val target = startPercent + (action.toPercent - startPercent) * (i / steps.toFloat())
            volumeController.setPercent(action.stream, target)
            delay(stepDelay)
        }
    }
}
