package com.soniccore.feature.automation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.data.repository.AutomationRepository
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.ConditionLogic
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleCondition
import com.soniccore.core.model.automation.RuleTrigger
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.profile.AudioProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AutomationUiState(
    val rules: List<AutomationRule> = emptyList(),
    val profiles: List<AudioProfile> = emptyList(),
    val devices: List<AudioDevice> = emptyList(),
    val editing: AutomationRule? = null,
    /** True until the first rule list arrives from Room. */
    val isLoading: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val repository: AutomationRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        // Restore the id of any rule that was being edited. Android kills backgrounded
        // processes freely, and returning to a lost half-configured rule is a real
        // annoyance.
        //
        // Only the ID is persisted, not the whole object: the rule is already saved in
        // Room the moment it is created, so the id is enough to re-open it — and it needs
        // no serializer, keeping this module free of the serialization plugin.
        AutomationUiState(),
    )
    val uiState: StateFlow<AutomationUiState> = _uiState.asStateFlow()

    /** Remember which rule was open so it can be restored after process death. */
    private fun rememberDraft(rule: AutomationRule?) {
        savedState[KEY_EDITING_RULE_ID] = rule?.id
    }

    init {
        combine(
            repository.rules,
            profileRepository.profiles,
            deviceRepository.knownDevices,
        ) { rules, profiles, devices ->
            // Re-open the rule that was being edited before the process was killed.
            // Resolved from the freshly loaded list so the restored object is current,
            // never a stale copy.
            val restored = _uiState.value.editing
                ?: savedState.get<String>(KEY_EDITING_RULE_ID)
                    ?.let { id -> rules.firstOrNull { it.id == id } }

            _uiState.value = _uiState.value.copy(
                rules = rules,
                profiles = profiles,
                devices = devices,
                editing = restored,
                isLoading = false,
            )
        }.launchIn(viewModelScope)
    }

    fun createRule(name: String, trigger: RuleTrigger) {
        viewModelScope.launch {
            val rule = repository.save(
                AutomationRule(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { "New rule" },
                    trigger = trigger,
                ),
            )
            _uiState.value = _uiState.value.copy(editing = rule, message = "Created “${rule.name}”")
            rememberDraft(rule)
        }
    }

    fun startEditing(rule: AutomationRule) {
        _uiState.value = _uiState.value.copy(editing = rule)
        rememberDraft(rule)
    }

    fun stopEditing() {
        _uiState.value = _uiState.value.copy(editing = null)
        rememberDraft(null)
    }

    private fun updateEditing(transform: (AutomationRule) -> AutomationRule) {
        val current = _uiState.value.editing ?: return
        val updated = transform(current)
        _uiState.value = _uiState.value.copy(editing = updated)
        // Every field edit is checkpointed: this is the whole point — the user should
        // never lose a rule they were halfway through building.
        rememberDraft(updated)
    }

    fun rename(name: String) = updateEditing { it.copy(name = name) }
    fun setTrigger(trigger: RuleTrigger) = updateEditing { it.copy(trigger = trigger) }
    fun setLogic(logic: ConditionLogic) = updateEditing { it.copy(conditionLogic = logic) }
    fun setPriority(priority: Int) = updateEditing { it.copy(priority = priority) }
    fun setCooldown(ms: Long) = updateEditing { it.copy(cooldownMs = ms.coerceAtLeast(0L)) }

    fun addCondition(condition: RuleCondition) = updateEditing {
        it.copy(conditions = it.conditions + condition)
    }

    fun removeCondition(index: Int) = updateEditing { rule ->
        rule.copy(conditions = rule.conditions.filterIndexed { i, _ -> i != index })
    }

    fun addAction(action: RuleAction) = updateEditing { it.copy(actions = it.actions + action) }

    fun removeAction(index: Int) = updateEditing { rule ->
        rule.copy(actions = rule.actions.filterIndexed { i, _ -> i != index })
    }

    fun moveAction(from: Int, to: Int) = updateEditing { rule ->
        val actions = rule.actions.toMutableList()
        if (from in actions.indices && to in actions.indices) {
            val item = actions.removeAt(from)
            actions.add(to, item)
        }
        rule.copy(actions = actions)
    }

    fun saveEditing() {
        val rule = _uiState.value.editing ?: return
        viewModelScope.launch {
            if (rule.actions.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    message = "Add at least one action — a rule with no actions does nothing",
                )
                return@launch
            }
            repository.save(rule)
            _uiState.value = _uiState.value.copy(editing = null, message = "Saved “${rule.name}”")
        }
    }

    fun setEnabled(rule: AutomationRule, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(rule.id, enabled) }
    }

    fun delete(rule: AutomationRule) {
        viewModelScope.launch {
            repository.delete(rule.id)
            _uiState.value = _uiState.value.copy(message = "Deleted “${rule.name}”")
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** Human-readable rule summary for the list. */
    fun describe(rule: AutomationRule): String {
        val trigger = describeTrigger(rule.trigger)
        val actions = rule.actions.take(2).map { describeAction(it) }
        val suffix = if (rule.actions.size > 2) " +${rule.actions.size - 2} more" else ""
        return "$trigger → ${actions.joinToString(", ")}$suffix"
    }

    fun describeTrigger(trigger: RuleTrigger): String = when (trigger) {
        is RuleTrigger.DeviceConnected -> "When ${deviceName(trigger.deviceKey)} connects"
        is RuleTrigger.DeviceDisconnected -> "When ${deviceName(trigger.deviceKey)} disconnects"
        is RuleTrigger.AppForeground -> "When ${trigger.packageName} opens"
        is RuleTrigger.AppAudioStarted -> "When ${trigger.packageName} starts audio"
        is RuleTrigger.TimeWindow ->
            "Between ${formatMinutes(trigger.startMinuteOfDay)} and ${formatMinutes(trigger.endMinuteOfDay)}"
        is RuleTrigger.BatteryBelow -> "When phone battery drops below ${trigger.percent}%"
        is RuleTrigger.DeviceBatteryBelow ->
            "When ${deviceName(trigger.deviceKey)} battery drops below ${trigger.percent}%"
        is RuleTrigger.CallStateChanged -> "When a call starts or ends"
        is RuleTrigger.HeadsetPlugged ->
            if (trigger.plugged) "When a headset is plugged in" else "When a headset is unplugged"
        is RuleTrigger.WifiNetwork -> "When Wi-Fi ${trigger.ssid} connects"
        is RuleTrigger.VolumeChanged -> "When ${trigger.stream.displayName} volume changes"
        RuleTrigger.ScreenOff -> "When the screen turns off"
        RuleTrigger.BootCompleted -> "After the device boots"
        is RuleTrigger.Manual -> "Manually"
    }

    fun describeAction(action: RuleAction): String = when (action) {
        is RuleAction.ActivateProfile -> "activate ${profileName(action.profileId)}"
        is RuleAction.SetStreamVolume ->
            "set ${action.stream.displayName} to ${(action.percent * 100).toInt()}%"
        is RuleAction.SetMute ->
            "${if (action.muted) "mute" else "unmute"} ${action.stream.displayName}"
        is RuleAction.RouteOutput -> "route output to ${deviceName(action.deviceKey)}"
        is RuleAction.RouteInput -> "route input to ${deviceName(action.deviceKey)}"
        is RuleAction.ApplyEqPreset -> "apply EQ preset"
        is RuleAction.SetEqEnabled -> if (action.enabled) "turn EQ on" else "turn EQ off"
        is RuleAction.SetAnc -> "set ANC to ${(action.level * 100).toInt()}%"
        is RuleAction.Notify -> "notify “${action.title}”"
        RuleAction.OpenApp -> "open SonicCore"
        is RuleAction.Delay -> "wait ${action.millis} ms"
        is RuleAction.FadeVolume ->
            "fade ${action.stream.displayName} to ${(action.toPercent * 100).toInt()}%"
    }

    private fun deviceName(key: String): String =
        _uiState.value.devices.firstOrNull { it.stableKey == key }?.label ?: "a device"

    private fun profileName(id: String): String =
        _uiState.value.profiles.firstOrNull { it.id == id }?.name ?: "a profile"

    private fun formatMinutes(minuteOfDay: Int): String {
        val hours = (minuteOfDay / 60) % 24
        val minutes = minuteOfDay % 60
        return "%02d:%02d".format(hours, minutes)
    }

    private companion object {
        const val KEY_EDITING_RULE_ID = "editing_rule_id"
    }
}
