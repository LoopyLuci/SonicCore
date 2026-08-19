package com.soniccore.core.model.automation

import com.soniccore.core.model.audio.AudioStream
import kotlinx.serialization.Serializable

/** What starts a rule evaluation. */
@Serializable
sealed interface RuleTrigger {
    @Serializable
    data class DeviceConnected(val deviceKey: String) : RuleTrigger

    @Serializable
    data class DeviceDisconnected(val deviceKey: String) : RuleTrigger

    @Serializable
    data class AppForeground(val packageName: String) : RuleTrigger

    @Serializable
    data class AppAudioStarted(val packageName: String) : RuleTrigger

    @Serializable
    data class TimeWindow(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    ) : RuleTrigger

    @Serializable
    data class BatteryBelow(val percent: Int) : RuleTrigger

    @Serializable
    data class DeviceBatteryBelow(val deviceKey: String, val percent: Int) : RuleTrigger

    @Serializable
    data class CallStateChanged(val ringing: Boolean, val offHook: Boolean) : RuleTrigger

    @Serializable
    data class HeadsetPlugged(val plugged: Boolean) : RuleTrigger

    @Serializable
    data class WifiNetwork(val ssid: String, val connected: Boolean) : RuleTrigger

    @Serializable
    data class VolumeChanged(val stream: AudioStream) : RuleTrigger

    @Serializable
    data object ScreenOff : RuleTrigger

    @Serializable
    data object BootCompleted : RuleTrigger

    @Serializable
    data class Manual(val label: String = "Manual") : RuleTrigger
}

/** Extra gate that must hold for actions to run. */
@Serializable
sealed interface RuleCondition {
    @Serializable
    data class ActiveOutputIs(val deviceKey: String) : RuleCondition

    @Serializable
    data class ActiveProfileIs(val profileId: String) : RuleCondition

    @Serializable
    data class TimeBetween(val startMinuteOfDay: Int, val endMinuteOfDay: Int) : RuleCondition

    @Serializable
    data class BatteryAbove(val percent: Int) : RuleCondition

    @Serializable
    data class MediaIsPlaying(val playing: Boolean) : RuleCondition

    @Serializable
    data class IsCharging(val charging: Boolean) : RuleCondition

    @Serializable
    data class Not(val inner: RuleCondition) : RuleCondition
}

/** Effect to apply when a rule fires. */
@Serializable
sealed interface RuleAction {
    @Serializable
    data class ActivateProfile(val profileId: String) : RuleAction

    @Serializable
    data class SetStreamVolume(val stream: AudioStream, val percent: Float) : RuleAction

    @Serializable
    data class SetMute(val stream: AudioStream, val muted: Boolean) : RuleAction

    @Serializable
    data class RouteOutput(val deviceKey: String) : RuleAction

    @Serializable
    data class RouteInput(val deviceKey: String) : RuleAction

    @Serializable
    data class ApplyEqPreset(val presetId: String) : RuleAction

    @Serializable
    data class SetEqEnabled(val enabled: Boolean) : RuleAction

    @Serializable
    data class SetAnc(val level: Float) : RuleAction

    @Serializable
    data class Notify(val title: String, val message: String) : RuleAction

    @Serializable
    data class Delay(val millis: Long) : RuleAction

    @Serializable
    data class FadeVolume(val stream: AudioStream, val toPercent: Float, val durationMs: Long) : RuleAction
}

@Serializable
enum class ConditionLogic { ALL, ANY }

@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition> = emptyList(),
    val conditionLogic: ConditionLogic = ConditionLogic.ALL,
    val actions: List<RuleAction> = emptyList(),
    val priority: Int = 0,
    val cooldownMs: Long = 1_000L,
    val lastFiredEpochMs: Long? = null,
    val fireCount: Int = 0,
    val isBuiltIn: Boolean = false,
)
