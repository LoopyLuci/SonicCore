package com.soniccore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented navigation tests. Run with a device or emulator attached:
 *   ./gradlew :app:connectedDebugAndroidTest
 *
 * These exercise the real Hilt graph, the real Room database, and the real
 * AudioManager, so they catch wiring and layout faults that JVM tests cannot —
 * the nested-scrollable crash in MoreScreen was found here.
 *
 * NOTE: `HiltTestApplication` replaces `SonicCoreApplication`, so the built-in
 * profile/preset seeding that normally happens in `Application.onCreate` does NOT
 * run. Seeding is performed defensively by the ViewModels instead, which is
 * asynchronous — hence [awaitText] rather than a bare assertion.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /*
     * PITFALL — do NOT add a GrantPermissionRule here.
     *
     * Granting a permission that is not already held RESTARTS the app process, which
     * destroys the Activity that ComposeTestRule is waiting on. Every test then fails
     * with "No compose hierarchies found in the app", which reads like a setContent
     * bug but is really the process restart.
     *
     * MainActivity requests its permissions itself; the dialog does not block the
     * Compose hierarchy from being created. Grant permissions out-of-band instead:
     *   adb shell pm grant com.soniccore android.permission.RECORD_AUDIO
     */
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    /** Waits for text that appears only after async DB seeding completes. */
    private fun awaitText(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openTab(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun dashboardIsTheDefaultDestination() {
        composeRule.onNodeWithText("SonicCore").assertIsDisplayed()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun bottomBarExposesEveryPrimaryDestination() {
        listOf("Home", "EQ", "Devices", "Profiles", "More").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun navigatingToEqualizerShowsEveryResolution() {
        openTab("EQ")
        composeRule.onNodeWithText("Equalizer").assertIsDisplayed()
        composeRule.onNodeWithText("Quick").assertIsDisplayed()
        composeRule.onNodeWithText("10-band").assertIsDisplayed()
        composeRule.onNodeWithText("31-band").assertIsDisplayed()
        composeRule.onNodeWithText("Parametric").assertIsDisplayed()
    }

    @Test
    fun equalizerShowsBuiltInPresetsOnceSeeded() {
        openTab("EQ")
        composeRule.onNodeWithText("Presets").assertIsDisplayed()
        // PITFALL: presets render in a LazyRow/LazyColumn, so only VISIBLE items have
        // semantics nodes. Asserting on "Flat" fails not because seeding is broken but
        // because it is scrolled out of view. Assert on the seeded count and the first
        // alphabetical entry, which are genuinely on screen.
        awaitText("available")
        composeRule.onNodeWithText("Acoustic", substring = true).assertIsDisplayed()
    }

    @Test
    fun switchingToParametricRevealsBandControls() {
        openTab("EQ")
        composeRule.onNodeWithText("Parametric").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bands").assertIsDisplayed()
    }

    @Test
    fun devicesScreenListsTheBuiltInOutput() {
        openTab("Devices")
        // Every Android device has a built-in speaker; the registry must find it.
        awaitText("Built-in")
    }

    @Test
    fun profilesScreenShowsSeededBuiltInProfiles() {
        openTab("Profiles")
        composeRule.onNodeWithText("Your profiles").assertIsDisplayed()
        // Same LazyColumn caveat as the presets: "Music" is seeded but scrolled off
        // screen. The header count proves seeding ran, and "Call" is visible.
        awaitText("saved")
        composeRule.onNodeWithText("Call", substring = true).assertIsDisplayed()
    }

    @Test
    fun moreTabOpensWithoutCrashingAndLinksToTools() {
        // Regression guard: this tab used to throw
        // "Vertically scrollable component was measured with an infinity maximum
        // height constraints" because a LazyColumn was nested in a scrolling Column.
        openTab("More")
        composeRule.onNodeWithText("Audio tools").assertIsDisplayed()
        composeRule.onNodeWithText("Mixer").assertIsDisplayed()
        composeRule.onNodeWithText("Effects").assertIsDisplayed()
        composeRule.onNodeWithText("Automation").assertIsDisplayed()
    }

    @Test
    fun settingsAreReachableFromMore() {
        openTab("More")
        awaitText("Appearance")
    }

    @Test
    fun volumeSlidersRenderForSystemStreams() {
        composeRule.onNodeWithText("Volume").assertIsDisplayed()
        awaitText("Media")
    }
}
