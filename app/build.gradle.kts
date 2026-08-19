import java.util.Properties

plugins {
    id("soniccore.android.application")
    id("soniccore.android.compose")
    id("soniccore.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing credentials come from a gitignored keystore.properties.
 * The release build falls back to unsigned when the file is absent (CI without
 * secrets), so a fresh clone still builds.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.soniccore"

    defaultConfig {
        applicationId = "com.soniccore"
        testInstrumentationRunner = "com.soniccore.SonicCoreTestRunner"
    }

    /*
     * Distribution flavors.
     *
     *  foss — F-Droid: no Google Play Services, no proprietary dependencies. Cast is
     *         replaced by a no-op that explains itself; AirPlay still works because it
     *         is implemented from scratch over RTSP.
     *  full — GitHub release / Play: includes the Google Cast SDK.
     *
     * F-Droid builds `fossRelease`; GitHub releases ship `fullRelease`.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
        create("full") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            /*
             * Minify the SHIPPING binary, but not when the instrumented suite is
             * pointed at release (-PtestBuildType=release).
             *
             * compose-ui-test reaches into Compose internals (SemanticsNode.getParentInfo,
             * SemanticsProperties.INSTANCE, MonotonicFrameClock$DefaultImpls) that R8's
             * OPTIMIZER rewrites even when -keep preserves the class. Chasing each
             * NoSuchMethodError individually does not converge, and loosening the
             * shipping config to satisfy a test harness is the wrong trade.
             *
             * So: verify the app's OWN code under full R8 via ComponentResolutionTest
             * (services, tiles, receivers, widget, Cast provider — all pass minified),
             * and run Compose UI assertions unminified. The shipped artifact keeps
             * every optimization.
             */
            val testingRelease = project.findProperty("testBuildType") == "release"
            isMinifyEnabled = !testingRelease
            isShrinkResources = !testingRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            enableAndroidTestCoverage = false
            testProguardFiles("proguard-test-rules.pro")
        }

        /*
         * Build type for :benchmark — release-like (minified, shrunk) but debuggable
         * enough for macrobenchmark to attach. Benchmarking a debug build measures
         * nothing useful, so this inherits from release.
         */
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    /*
     * Which build type instrumented tests run against.
     *
     * Defaults to debug for fast iteration. Pass -PtestBuildType=release to run the
     * suite against the MINIFIED, signed binary users actually install — the only way
     * to catch R8/obfuscation faults in reflection-based code paths.
     */
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "debug"

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.audio)
    implementation(projects.core.data)
    implementation(projects.core.dsp)
    implementation(projects.core.streaming)

    implementation(projects.feature.dashboard)
    implementation(projects.feature.devices)
    implementation(projects.feature.equalizer)
    implementation(projects.feature.profiles)
    implementation(projects.feature.mixer)
    implementation(projects.feature.effects)
    implementation(projects.feature.microphone)
    implementation(projects.feature.automation)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(projects.core.streaming)

    // Cast SDK only in the `full` flavor — keeps the F-Droid build free of
    // proprietary dependencies. See core/streaming for the CastStreamer split.
    "fullImplementation"(libs.play.services.cast.framework)
    "fullImplementation"(libs.androidx.mediarouter)

    // Installs the Baseline Profile bundled in the APK/AAB at first run, so users get
    // the pre-compiled startup path without waiting for JIT to warm up.
    implementation(libs.androidx.profileinstaller)

    // Explicit, not transitive: androidx.test and profileinstaller resolve
    // androidx.tracing.Trace reflectively, and R8 strips it from the release build
    // otherwise (NoClassDefFoundError at process start).
    implementation("androidx.tracing:tracing:1.2.0")

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.navigation.testing)
    /*
     * PITFALL: do NOT add androidx.test:rules here.
     *
     * It transitively upgrades androidx.test:monitor 1.6.1 -> 1.7.1, whose
     * ActivityScenario internals are incompatible with compose ui-test 1.7.5. Every
     * createAndroidComposeRule test then fails with
     *   IllegalStateException: No compose hierarchies found in the app
     * even though the app renders Compose correctly. Grant permissions with
     * `adb shell pm grant` instead of GrantPermissionRule.
     */
    androidTestImplementation("androidx.test:monitor:1.7.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
