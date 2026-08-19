plugins {
    id("soniccore.android.library")
    id("soniccore.android.hilt")
}

android {
    namespace = "com.soniccore.core.streaming"

    /*
     * The foss/full dimension itself comes from the library convention plugin (every
     * module needs it so variants resolve). THIS module is the only one with different
     * source per flavor:
     *   foss — binds NoOpCastStreamer, no Play Services at all (F-Droid policy)
     *   full — binds the real Cast SDK implementation
     * AirPlay/RAOP is hand-written over RTSP and ships in both.
     */
    sourceSets {
        getByName("foss") { kotlin.srcDir("src/foss/kotlin") }
        getByName("full") { kotlin.srcDir("src/full/kotlin") }
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Cast SDK ONLY in the full flavor — this is what keeps the FOSS build clean.
    "fullImplementation"(libs.play.services.cast.framework)
    "fullImplementation"(libs.androidx.mediarouter)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
