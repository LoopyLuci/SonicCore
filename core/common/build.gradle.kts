plugins {
    id("soniccore.android.library")
    id("soniccore.android.hilt")
}

android {
    namespace = "com.soniccore.core.common"
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
