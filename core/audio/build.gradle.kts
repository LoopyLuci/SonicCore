plugins {
    id("soniccore.android.library")
    id("soniccore.android.hilt")
}

android {
    namespace = "com.soniccore.core.audio"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.dsp)
    // api, not implementation: DiagnosticLog and AudioFocusManager appear in public
    // constructor signatures here, so consumers must see the types to inject them.
    api(projects.core.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}
