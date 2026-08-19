package com.soniccore.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start measurements.
 *
 * Run each variant and compare, so a claimed improvement is a measured one:
 *
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *
 * `CompilationMode.None` is the worst case (JIT only), `Partial` is what a user gets
 * with a Baseline Profile installed, and `Full` is the ceiling. The Partial-vs-None
 * delta is the value the Baseline Profile actually delivers.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = measure(
        CompilationMode.Partial(baselineProfileMode = androidx.benchmark.macro.BaselineProfileMode.Require),
    )

    @Test
    fun startupFullyCompiled() = measure(CompilationMode.Full())

    private fun measure(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Wait for real content, not just the window: measuring to first frame of a
        // splash screen would flatter the numbers.
        device.wait(Until.hasObject(By.textContains("Dashboard")), 5_000)
    }

    private companion object {
        const val PACKAGE = "com.soniccore"
    }
}
