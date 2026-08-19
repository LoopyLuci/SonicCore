dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            library("android-gradlePlugin", "com.android.tools.build:gradle:8.6.1")
            library("kotlin-gradlePlugin", "org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
            library("compose-gradlePlugin", "org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
            library("ksp-gradlePlugin", "com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.25")
        }
    }
}

rootProject.name = "build-logic"
