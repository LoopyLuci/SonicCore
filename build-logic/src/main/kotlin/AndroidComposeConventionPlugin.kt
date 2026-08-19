import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        @Suppress("UNCHECKED_CAST")
        val extension = extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>
        extension.buildFeatures.compose = true

        dependencies {
            val bom = platform(libs.findLibrary("androidx-compose-bom").get())
            add("implementation", bom)
            add("androidTestImplementation", bom)

            listOf(
                "androidx-compose-ui",
                "androidx-compose-ui-graphics",
                "androidx-compose-ui-tooling-preview",
                "androidx-compose-material3",
                "androidx-compose-material-icons-extended",
                "androidx-lifecycle-runtime-compose",
                "androidx-lifecycle-viewmodel-compose",
            ).forEach { alias ->
                add("implementation", libs.findLibrary(alias).get())
            }

            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        }
    }
}
