plugins {
    id("soniccore.android.feature")
}

android {
    namespace = "com.soniccore.feature.dashboard"
}

dependencies {
    implementation(projects.core.dsp)
}
