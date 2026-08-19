pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SonicCore"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:dsp")
include(":core:common")
include(":core:audio")
include(":core:data")
include(":core:ui")
include(":core:streaming")
include(":benchmark")

include(":feature:dashboard")
include(":feature:devices")
include(":feature:equalizer")
include(":feature:profiles")
include(":feature:mixer")
include(":feature:effects")
include(":feature:microphone")
include(":feature:automation")
include(":feature:settings")
