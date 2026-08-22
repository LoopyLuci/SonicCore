package com.soniccore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.soniccore.automation.AutomationEngine
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.audio.volume.VolumeController
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.repository.AutomationRepository
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleTrigger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The automation `OpenApp` action must only start the activity when the user has granted
 * the SYSTEM_ALERT_WINDOW permission ("Display over other apps").
 *
 * That permission is the permanent, cross-OEM exemption from Android's background
 * activity-start restriction. Without it a background activity start is silently aborted on
 * aggressive ROMs (HyperOS/MIUI, some Samsung/OnePlus). So the safe, expected behaviour when
 * the permission is NOT granted is a no-op that does not crash and does not disturb the other
 * actions of a rule — the "open app" step degrades gracefully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationOpenAppTest {

    private lateinit var repository: AutomationRepository
    private lateinit var engine: AutomationEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        coEvery { repository.recordFire(any()) } returns Unit
        engine = AutomationEngine(
            context = context,
            repository = repository,
            profileRepository = mockk(relaxed = true),
            presetRepository = mockk(relaxed = true),
            profileEngine = mockk(relaxed = true),
            volumeController = mockk(relaxed = true),
            router = mockk(relaxed = true),
            registry = mockk<AudioDeviceRegistry>(relaxed = true),
        )
    }

    private fun openAppRule(): AutomationRule = AutomationRule(
        id = "rule-open",
        name = "Open app",
        enabled = true,
        trigger = RuleTrigger.BootCompleted,
        actions = listOf(RuleAction.OpenApp),
    )

    @Test
    fun `open app without overlay permission is a safe no-op`() = runTest {
        // Default: SYSTEM_ALERT_WINDOW is NOT granted (we never request it in this test).
        coEvery { repository.get("rule-open") } returns openAppRule()

        // Must complete without throwing — the overlay gate returns early and the rule
        // runner (which appends recordFire) finishes normally.
        engine.runManually("rule-open")
        // No assertion of a started activity: the point is that it does NOT crash when the
        // permission is missing, so a multi-action rule still runs its other steps.
    }

    @Test
    fun `open app rule still records its fire when permission is missing`() = runTest {
        coEvery { repository.get("rule-open") } returns openAppRule()

        engine.runManually("rule-open")

        // The no-op overlay gate must not skip bookkeeping: recordFire (which drives cooldown
        // and priority) still runs when the ungranted permission short-circuits the UI launch.
        io.mockk.coVerify { repository.recordFire("rule-open") }
    }
}