plugins {
    id("soniccore.android.library")
    id("soniccore.android.compose")
}

android {
    namespace = "com.soniccore.core.ui"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(projects.core.model)
    // api: ImmutableList appears in public @Composable signatures (EqCurveView), so
    // callers must be able to construct one.
    api(libs.kotlinx.collections.immutable)
    implementation(projects.core.dsp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)

    // Robolectric lets Compose UI tests run on the JVM — no emulator needed,
    // so these execute in `./gradlew test` alongside the DSP suite.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // PITFALL: ui-test-manifest must NOT be debugImplementation here. Robolectric
    // runs both testDebugUnitTest AND testReleaseUnitTest; without the manifest on
    // the release classpath, every Compose test fails with
    // "Unable to resolve activity for Intent ... ComponentActivity".
    testImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
