package com.soniccore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun isMIUI(): Boolean = runCatching {
    val clazz = Class.forName("android.os.SystemProperties")
    val getProp = clazz.getMethod("get", String::class.java)
    val prop = getProp.invoke(null, "ro.miui.ui.version.name") as? String
    !prop.isNullOrBlank()
}.getOrDefault(false)

/**
 * The Diagnostics screen must be reachable.
 *
 * The README, CONTRIBUTING guide and the GitHub bug-report template all instruct users to
 * go to "Settings → Diagnostics → Export log". For a while that screen did not exist and
 * the instructions were impossible to follow — this test exists so that cannot regress.
 *
 * MIUI SmartPower aborts background activity starts from the UTP harness process,
 * killing it before this test can complete. When that is detected the class is
 * skipped cleanly with a clear explanation instead of failing with "Process crashed".
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DiagnosticsNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun skipOnMIUI() {
        assumeFalse("MIUI SmartPower aborts UTP harness activity starts — skipped", isMIUI())
    }

    private fun openMore() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("More").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("More")[0].performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun diagnosticsEntryPointExistsInMore() {
        openMore()
        // assertExists, not assertIsDisplayed: the More tab is taller than the viewport,
        // and a node below the fold has semantics but no display bounds. What matters is
        // that the entry point is in the tree and clickable.
        composeRule.onNodeWithText("Diagnostics").assertExists()
    }

    @Test
    fun diagnosticsScreenOpensAndExplainsItself() {
        openMore()
        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.waitForIdle()

        // The explainer tells the user WHY the log matters, not just that it exists.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Export log").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Export log").assertIsDisplayed()
    }

    @Test
    fun diagnosticsShowsEventCountOrAllClear() {
        openMore()
        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.waitForIdle()

        // Either a count of recorded events, or the explicit "nothing recorded" copy —
        // never a blank pane the user cannot interpret.
        val hasCount = composeRule
            .onAllNodesWithText("events recorded", substring = true)
            .fetchSemanticsNodes().isNotEmpty()
        val hasEmptyCopy = composeRule
            .onAllNodesWithText("Nothing recorded yet", substring = true)
            .fetchSemanticsNodes().isNotEmpty()
        val hasSingular = composeRule
            .onAllNodesWithText("event recorded", substring = true)
            .fetchSemanticsNodes().isNotEmpty()

        assert(hasCount || hasEmptyCopy || hasSingular) {
            "Diagnostics screen showed neither an event count nor the empty-state copy"
        }
    }
}
