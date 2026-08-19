plugins {
    id("soniccore.android.feature")
}

android {
    namespace = "com.soniccore.feature.effects"
}

dependencies {
    implementation(projects.core.dsp)
}
