import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: org.gradle.api.artifacts.VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Shared Android config applied to both application and library modules. */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = SonicCoreBuild.COMPILE_SDK

        defaultConfig {
            minSdk = SonicCoreBuild.MIN_SDK
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = false
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/LICENSE*"
                excludes += "DebugProbesKt.bin"
            }
        }

        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
        }

        /*
         * Register `kotlin/` as a source root for every variant.
         *
         * PITFALL: AGP's default Kotlin roots cover main and unit test, but NOT
         * androidTest. With sources in src/androidTest/kotlin/ and no explicit
         * srcDir, the instrumented suite compiles to nothing and the run reports
         * "Starting 0 tests" while still exiting non-zero — it looks like a device
         * problem but is purely a source-set gap.
         */
        sourceSets.configureEach {
            java.srcDir("src/$name/kotlin")
        }
    }

    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }
}

object SonicCoreBuild {
    const val COMPILE_SDK = 35
    const val TARGET_SDK = 35
    const val MIN_SDK = 26
    const val VERSION_CODE = 1
    const val VERSION_NAME = "1.0.0"
    const val NAMESPACE_ROOT = "com.soniccore"
}
