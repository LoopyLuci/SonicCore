package com.soniccore

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Hilt needs its own Application during instrumented tests. Without this runner,
 * `@HiltAndroidTest` fails at startup because the real @HiltAndroidApp class is
 * used instead of HiltTestApplication.
 *
 * Wired via `testInstrumentationRunner` in the app build file.
 */
class SonicCoreTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
