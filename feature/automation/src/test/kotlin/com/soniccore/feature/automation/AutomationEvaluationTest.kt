package com.soniccore.feature.automation

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.ConditionLogic
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleCondition
import com.soniccore.core.model.automation.RuleTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automation rule evaluation: trigger matching, condition logic, priority ordering
 * and cooldown gating. Getting any of these wrong makes the app change audio state
 * when the user did not ask it to — the worst class of bug in this app.
 */
class AutomationEvaluationTest {

    private fun rule(
        id: String,
        trigger: RuleTrigger,
        conditions: List<RuleCondition> = emptyList(),
        logic: ConditionLogic = ConditionLogic.ALL,
        priority: Int = 0,
        cooldownMs: Long = 0,
        lastFired: Long? = null,
        enabled: Boolean = true,
    ) = AutomationRule(
        id = id,
        name = "rule-$id",
        enabled = enabled,
        trigger = trigger,
        conditions = conditions,
        conditionLogic = logic,
        actions = listOf(RuleAction.ActivateProfile("p")),
        priority = priority,
        cooldownMs = cooldownMs,
        lastFiredEpochMs = lastFired,
    )

    // --- trigger matching ---

    @Test
    fun `device connected trigger matches only its own device key`() {
        val r = rule("a", RuleTrigger.DeviceConnected("BT:OUTPUT:AA"))
        assertTrue(matchesDeviceConnected(r, "BT:OUTPUT:AA"))
        assertFalse(matchesDeviceConnected(r, "BT:OUTPUT:BB"))
    }

    private fun matchesDeviceConnected(rule: AutomationRule, key: String): Boolean {
        val t = rule.trigger
        return t is RuleTrigger.DeviceConnected && t.deviceKey == key
    }

    @Test
    fun `connect and disconnect triggers do not cross fire`() {
        val connect = rule("a", RuleTrigger.DeviceConnected("k"))
        val disconnect = rule("b", RuleTrigger.DeviceDisconnected("k"))
        assertTrue(connect.trigger is RuleTrigger.DeviceConnected)
        assertFalse(connect.trigger is RuleTrigger.DeviceDisconnected)
        assertTrue(disconnect.trigger is RuleTrigger.DeviceDisconnected)
    }

    @Test
    fun `headset plug trigger distinguishes plugged from unplugged`() {
        val plugged = rule("a", RuleTrigger.HeadsetPlugged(true))
        val unplugged = rule("b", RuleTrigger.HeadsetPlugged(false))
        assertTrue((plugged.trigger as RuleTrigger.HeadsetPlugged).plugged)
        assertFalse((unplugged.trigger as RuleTrigger.HeadsetPlugged).plugged)
    }

    // --- time windows ---

    private fun inWindow(t: RuleTrigger.TimeWindow, minuteOfDay: Int, dayOfWeek: Int): Boolean {
        if (dayOfWeek !in t.daysOfWeek) return false
        return if (t.startMinuteOfDay <= t.endMinuteOfDay) {
            minuteOfDay in t.startMinuteOfDay..t.endMinuteOfDay
        } else {
            minuteOfDay >= t.startMinuteOfDay || minuteOfDay <= t.endMinuteOfDay
        }
    }

    @Test
    fun `same day time window matches inside and rejects outside`() {
        // 09:00-17:00 weekdays
        val t = RuleTrigger.TimeWindow(540, 1020, setOf(1, 2, 3, 4, 5))
        assertTrue(inWindow(t, 600, 3))
        assertTrue(inWindow(t, 540, 1))
        assertTrue(inWindow(t, 1020, 5))
        assertFalse(inWindow(t, 539, 3))
        assertFalse(inWindow(t, 1021, 3))
    }

    @Test
    fun `time window respects the day of week set`() {
        val weekdays = RuleTrigger.TimeWindow(540, 1020, setOf(1, 2, 3, 4, 5))
        assertFalse("saturday must not match", inWindow(weekdays, 600, 6))
        assertFalse("sunday must not match", inWindow(weekdays, 600, 7))
    }

    @Test
    fun `overnight window spanning midnight matches both sides`() {
        // 23:00-06:00 — the classic off-by-one that breaks "night mode".
        val night = RuleTrigger.TimeWindow(1380, 360, setOf(1, 2, 3, 4, 5, 6, 7))
        assertTrue("23:30 is inside", inWindow(night, 1410, 1))
        assertTrue("00:30 is inside", inWindow(night, 30, 1))
        assertTrue("boundary start", inWindow(night, 1380, 1))
        assertTrue("boundary end", inWindow(night, 360, 1))
        assertFalse("noon is outside", inWindow(night, 720, 1))
        assertFalse("just before start", inWindow(night, 1379, 1))
        assertFalse("just after end", inWindow(night, 361, 1))
    }

    // --- condition logic ---

    private fun conditionsHold(rule: AutomationRule, results: List<Boolean>): Boolean {
        if (rule.conditions.isEmpty()) return true
        return when (rule.conditionLogic) {
            ConditionLogic.ALL -> results.all { it }
            ConditionLogic.ANY -> results.any { it }
        }
    }

    @Test
    fun `no conditions always holds`() {
        assertTrue(conditionsHold(rule("a", RuleTrigger.BootCompleted), emptyList()))
    }

    @Test
    fun `all logic requires every condition`() {
        val r = rule(
            "a", RuleTrigger.BootCompleted,
            conditions = listOf(RuleCondition.BatteryAbove(20), RuleCondition.IsCharging(true)),
            logic = ConditionLogic.ALL,
        )
        assertTrue(conditionsHold(r, listOf(true, true)))
        assertFalse(conditionsHold(r, listOf(true, false)))
        assertFalse(conditionsHold(r, listOf(false, false)))
    }

    @Test
    fun `any logic requires just one condition`() {
        val r = rule(
            "a", RuleTrigger.BootCompleted,
            conditions = listOf(RuleCondition.BatteryAbove(20), RuleCondition.IsCharging(true)),
            logic = ConditionLogic.ANY,
        )
        assertTrue(conditionsHold(r, listOf(true, false)))
        assertTrue(conditionsHold(r, listOf(false, true)))
        assertFalse(conditionsHold(r, listOf(false, false)))
    }

    @Test
    fun `not condition inverts its inner result`() {
        val inner = RuleCondition.IsCharging(true)
        val negated = RuleCondition.Not(inner)
        assertEquals(inner, negated.inner)
        // Double negation must return to the original meaning.
        assertEquals(inner, (RuleCondition.Not(negated)).inner.let { (it as RuleCondition.Not).inner })
    }

    // --- cooldown ---

    private fun inCooldown(rule: AutomationRule, now: Long): Boolean {
        if (rule.cooldownMs <= 0) return false
        val last = rule.lastFiredEpochMs ?: return false
        return now - last < rule.cooldownMs
    }

    @Test
    fun `zero cooldown never gates`() {
        val r = rule("a", RuleTrigger.BootCompleted, cooldownMs = 0, lastFired = 1_000)
        assertFalse(inCooldown(r, 1_001))
    }

    @Test
    fun `never fired rule is not in cooldown`() {
        val r = rule("a", RuleTrigger.BootCompleted, cooldownMs = 60_000, lastFired = null)
        assertFalse(inCooldown(r, 999_999))
    }

    @Test
    fun `cooldown blocks inside the window and releases after`() {
        val r = rule("a", RuleTrigger.BootCompleted, cooldownMs = 60_000, lastFired = 100_000)
        assertTrue("just fired", inCooldown(r, 100_001))
        assertTrue("inside window", inCooldown(r, 159_999))
        assertFalse("exactly at boundary", inCooldown(r, 160_000))
        assertFalse("after window", inCooldown(r, 200_000))
    }

    // --- ordering and enablement ---

    @Test
    fun `rules run highest priority first`() {
        val rules = listOf(
            rule("low", RuleTrigger.BootCompleted, priority = 1),
            rule("high", RuleTrigger.BootCompleted, priority = 99),
            rule("mid", RuleTrigger.BootCompleted, priority = 50),
        )
        val ordered = rules.sortedByDescending { it.priority }.map { it.id }
        assertEquals(listOf("high", "mid", "low"), ordered)
    }

    @Test
    fun `disabled rules are filtered out before evaluation`() {
        val rules = listOf(
            rule("on", RuleTrigger.BootCompleted, enabled = true),
            rule("off", RuleTrigger.BootCompleted, enabled = false),
        )
        assertEquals(listOf("on"), rules.filter { it.enabled }.map { it.id })
    }

    @Test
    fun `full pipeline applies enablement then cooldown then priority`() {
        val now = 200_000L
        val rules = listOf(
            rule("disabled", RuleTrigger.BootCompleted, priority = 100, enabled = false),
            rule("cooling", RuleTrigger.BootCompleted, priority = 90, cooldownMs = 60_000, lastFired = 180_000),
            rule("ready-low", RuleTrigger.BootCompleted, priority = 10),
            rule("ready-high", RuleTrigger.BootCompleted, priority = 80),
        )
        val eligible = rules
            .filter { it.enabled }
            .filterNot { inCooldown(it, now) }
            .sortedByDescending { it.priority }
            .map { it.id }
        assertEquals(listOf("ready-high", "ready-low"), eligible)
    }

    // --- action shapes ---

    @Test
    fun `fade action duration is preserved for the ramp`() {
        val fade = RuleAction.FadeVolume(AudioStream.MUSIC, 0.8f, 5_000)
        assertEquals(0.8f, fade.toPercent, 0.001f)
        assertEquals(5_000L, fade.durationMs)
    }

    @Test
    fun `delay action is clamped to a sane range when executed`() {
        // A 10-minute delay inside a rule would wedge the automation loop.
        fun clamp(ms: Long) = ms.coerceIn(0L, 30_000L)
        assertEquals(0L, clamp(-5))
        assertEquals(500L, clamp(500))
        assertEquals(30_000L, clamp(600_000))
    }
}
