plugins {
    id("soniccore.android.feature")
}

android {
    namespace = "com.soniccore.feature.equalizer"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
