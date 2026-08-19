# Keep rules applied to the INSTRUMENTATION APK when testing the minified release
# build. The app APK keeps its own rules (proguard-rules.pro); these only stop the
# test harness itself from being stripped or renamed.

# JUnit / AndroidX Test / Espresso are all reflection-driven.
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
-dontwarn androidx.test.**
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.compose.ui.test.** { *; }

# Test classes and @Test methods are discovered by name at runtime.
-keep class com.soniccore.**Test { *; }
-keep class com.soniccore.**Test$* { *; }
-keepclassmembers class com.soniccore.** {
    @org.junit.Test *;
    @org.junit.Before *;
    @org.junit.After *;
    @org.junit.Rule *;
    @org.junit.BeforeClass *;
    @org.junit.AfterClass *;
}

# The custom runner is named in the manifest as a string.
-keep class com.soniccore.SonicCoreTestRunner { *; }

# Hilt testing replaces the Application at runtime via reflection.
-keep class dagger.hilt.android.testing.** { *; }
-keep class dagger.hilt.android.internal.testing.** { *; }
-keep class * extends dagger.hilt.android.testing.HiltTestApplication { *; }
-keep class androidx.test.runner.AndroidJUnitRunner { *; }

# Kotlin reflection used by test assertions on data classes.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**

# The Kotlin stdlib itself must survive in the TEST apk.
# androidx.test's own classes are written in Kotlin (TestDirCalculator.kt etc.), so
# stripping stdlib helpers kills the runner during onCreate:
#   NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt;
# R8 cannot see these references because they come from the instrumentation side.
-keep class kotlin.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlin.collections.** { *; }
-dontwarn kotlin.**

# Compile-only annotation classes referenced by Hilt's testing library and its
# transitive deps. These never exist at runtime, so R8 must be told to ignore them
# rather than failing with "Missing classes detected while running R8".
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.tools.**
-dontwarn com.squareup.javapoet.**

# androidx.tracing.Trace is loaded REFLECTIVELY by androidx.test's monitoring layer.
# R8 cannot see the reference, strips it, and every test then dies at startup with
#   NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
# This only manifests in the minified build — the debug suite passes cleanly.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.platform.tracing.** { *; }
-dontwarn androidx.tracing.**

# The androidx.test monitor/runner internals are equally reflective.
-keep class androidx.test.internal.** { *; }
-keep class androidx.test.platform.** { *; }
-keep class androidx.test.orchestrator.** { *; }
-dontwarn androidx.test.**
