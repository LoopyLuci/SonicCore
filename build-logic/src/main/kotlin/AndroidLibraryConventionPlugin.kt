import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
            resourcePrefix = ""

            /*
             * Every library module carries the `distribution` dimension so it follows
             * whichever flavor :app is building.
             *
             * Only :core:streaming has different SOURCE per flavor (the Cast SDK is
             * proprietary and must not reach the F-Droid build), but the dimension has
             * to exist everywhere in the chain — otherwise a flavorless consumer of a
             * flavored dependency fails with:
             *   "cannot choose between fossDebugRuntimeElements / fullDebugRuntimeElements"
             * and a missingDimensionStrategy fallback would silently pin the FOSS app
             * to the full-flavored library, reintroducing Play Services.
             */
            flavorDimensions += "distribution"
            productFlavors {
                create("foss") { dimension = "distribution" }
                create("full") { dimension = "distribution" }
            }
        }
    }
}
