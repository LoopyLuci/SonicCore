plugins {
    id("soniccore.android.feature")
}

android {
    namespace = "com.soniccore.feature.devices"
}

dependencies {
    implementation(projects.core.streaming)
}
