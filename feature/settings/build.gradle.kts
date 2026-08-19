plugins {
    id("soniccore.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.soniccore.feature.settings"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
