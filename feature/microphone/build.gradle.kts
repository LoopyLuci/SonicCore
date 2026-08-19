plugins {
    id("soniccore.android.feature")
}

android {
    namespace = "com.soniccore.feature.microphone"
}

dependencies {
    implementation(projects.core.dsp)
}
