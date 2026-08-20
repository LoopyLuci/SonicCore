package com.soniccore

import android.app.Application
import android.app.NotificationManager
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The automation `Notify` action must reach the user as a real notification.
 *
 * This is the cross-OEM compatibility guarantee: automation output is surfaced through a
 * notification whose content intent is a user-trusted PendingIntent, never a raw
 * `startActivity` from a background rule. Aggressive ROMs (HyperOS/MIUI, some Samsung/OnePlus)
 * silently abort background activity starts, so this path is what keeps automation results
 * visible on every phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationNotifyTest {

    private lateinit var repository: AutomationRepository
    private lateinit var engine: AutomationEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        // Keep recordFire on the test dispatcher so Robolectric's legacy SQLite never
        // spawns a background arch_disk_io coroutine whose invalid connection pointer
        // surfaces as an UncaughtExceptionsBeforeTest on the NEXT test.
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

    private fun notifyRule(title: String, message: String): AutomationRule = AutomationRule(
        id = "rule-notify",
        name = "Notify test",
        enabled = true,
        trigger = RuleTrigger.BootCompleted,
        actions = listOf(RuleAction.Notify(title = title, message = message)),
    )

    @Test
    fun `notify action posts a notification`() = runTest {
        coEvery { repository.get("rule-notify") } returns notifyRule("Headphones connected", "Applied your commute EQ")

        engine.runManually("rule-notify")

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications
        assertEquals("Exactly one notification should be posted", 1, posted.size)
        assertEquals("Headphones connected", posted[0].extras.getString("android.title"))
        assertEquals("Applied your commute EQ", posted[0].extras.getString("android.text"))
    }

    @Test
    fun `notify action content intent opens MainActivity`() = runTest {
        coEvery { repository.get("rule-notify") } returns notifyRule("Title", "Body")

        engine.runManually("rule-notify")

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications
        val contentIntent = posted[0].contentIntent
        assertTrue("Notification must carry a content intent", contentIntent != null)
        // The intent must resolve to MainActivity — the trusted, user-mediated UI path.
        val resolved = shadowOf(contentIntent!!).savedIntent.component?.className
        assertEquals("com.soniccore.MainActivity", resolved)
    }

    @Test
    fun `notify does not crash when no repository rule matches`() = runTest {
        coEvery { repository.get("missing") } returns null
        // Should be a safe no-op, not a crash.
        engine.runManually("missing")
    }
}
