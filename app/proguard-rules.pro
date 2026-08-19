-keep class com.soniccore.core.model.** { *; }
-keepclassmembers class com.soniccore.core.model.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.soniccore.**$$serializer { *; }
-keepclassmembers class com.soniccore.** { *** Companion; }
-keepclasseswithmembers class com.soniccore.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Reflection into hidden Bluetooth APIs — these names MUST survive obfuscation or
# codec/battery lookup silently returns null in release builds.
-keep class android.bluetooth.** { *; }
-keepclassmembernames class android.bluetooth.BluetoothDevice { *; }
-keepclassmembernames class android.bluetooth.BluetoothA2dp { *; }
-keepclassmembernames class android.bluetooth.BluetoothCodecConfig { *; }
-keepclassmembernames class android.bluetooth.BluetoothCodecConfig$Builder { *; }
-keepclassmembernames class android.bluetooth.BluetoothCodecStatus { *; }
-dontwarn android.bluetooth.**

# Google Cast SDK — reflection on the OptionsProvider is how the SDK bootstraps.
-keep class com.soniccore.cast.CastOptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { *; }
-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**

# MediaRouter provider discovery
-keep class androidx.mediarouter.** { *; }
-dontwarn androidx.mediarouter.**

# Streaming layer: AirPlay/RAOP crypto uses JCE by name.
-keep class com.soniccore.core.streaming.** { *; }
-keep class javax.crypto.** { *; }
-dontwarn javax.crypto.**

# Glance widgets are instantiated by the framework.
-keep class com.soniccore.widget.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# Quick Settings tiles and services are referenced only from the manifest.
-keep class com.soniccore.tile.** { *; }
-keep class com.soniccore.service.** { *; }
-keep class com.soniccore.receiver.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Dagger's generated code calls dagger.internal.Preconditions and friends. The APP's
# own generated components are kept, but the TEST apk's generated components live in
# the test dex and reference these from outside R8's view:
#   ClassNotFoundException: dagger.internal.Preconditions
-keep class dagger.internal.** { *; }
-keep class dagger.** { *; }
-dontwarn dagger.**

# Hilt/Dagger GENERATED factories and modules in our own packages. The test dex
# contains its own generated component that instantiates these by name, e.g.
#   ClassNotFoundException: com.soniccore.core.audio.di.AudioModule_ProvideContextFactory
# so the *_Factory / *_MembersInjector / *Module classes must survive shrinking.
-keep class com.soniccore.**_Factory { *; }
-keep class com.soniccore.**_MembersInjector { *; }
-keep class com.soniccore.**_HiltModules* { *; }
-keep class com.soniccore.**Module { *; }
-keep class com.soniccore.**Module_* { *; }
-keep class com.soniccore.**_Impl { *; }
-keep class dagger.hilt.internal.** { *; }
-keep class hilt_aggregated_deps.** { *; }

# Hilt's generated component INTERFACES must keep their names. Under
# @HiltAndroidTest the component is DaggerDefault_HiltComponents_*, and it is cast to
# our generated *_HiltComponents interfaces. Obfuscating those yields:
#   ClassCastException: Dagger...SingletonC$ActivityCImpl cannot be cast to com.soniccore.a
-keep class com.soniccore.*_HiltComponents* { *; }
-keep class com.soniccore.*_HiltComponents*$* { *; }
-keep interface com.soniccore.*_HiltComponents* { *; }
-keep class com.soniccore.Hilt_* { *; }
-keep class **.Hilt_* { *; }
-keep class dagger.hilt.android.internal.testing.** { *; }
-keep class dagger.hilt.android.internal.lifecycle.** { *; }

# ViewModels are instantiated reflectively by the ViewModelProvider. R8 removes the
# @Inject constructor as "unused" (nothing calls it directly), and Hilt then falls
# back to a no-arg constructor that does not exist:
#   NoSuchMethodException: com.soniccore.feature.dashboard.G.<init> []
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
    *;
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# Compose
# NOTE: androidx.compose.ui.test links against Compose runtime/platform internals the
# app itself never calls (InfiniteAnimationPolicy, MonotonicFrameClock$DefaultImpls,
# Recomposer plumbing). Same shrink-vs-link problem as the Kotlin runtime below.
#
# Keeping ui.platform + runtime whole is what makes `-PtestBuildType=release`
# converge; chasing individual missing classes does not terminate.
-keep class androidx.compose.ui.platform.** { *; }
-keep interface androidx.compose.ui.platform.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep interface androidx.compose.runtime.** { *; }

# compose-ui-test matchers (hasText, onNodeWithText) read the INSTANCE singleton of
# SemanticsProperties objects. R8 removes those fields as unused, producing:
#   NoSuchFieldError: No field INSTANCE of type Ll0/t;
# Keeping the semantics surface (fields included) is required for any Compose
# assertion to work against a minified build.
-keep class androidx.compose.ui.semantics.** { *; }
-keepclassmembers class androidx.compose.ui.semantics.** {
    public static ** INSTANCE;
    <fields>;
}
-keep class androidx.compose.ui.text.** { *; }

# compose-ui-test reaches into Compose internals that R8's optimizer rewrites
# (SemanticsNode.getParentInfo, SemanticsProperties.INSTANCE). Keeping these helps the
# app's own reflective paths; Compose UI *assertions* are run unminified instead — see
# the release{} block in build.gradle.kts for the reasoning.
-keep class androidx.compose.ui.node.** { *; }
-keepclassmembers class androidx.compose.ui.node.** { *; }
-keepclassmembers class androidx.compose.** {
    public static ** INSTANCE;
}
-dontwarn androidx.compose.**

# androidx.lifecycle / activity / savedstate are linked against by compose-ui-test and
# by the Hilt test component (ViewTreeLifecycleOwner, ViewTreeViewModelStoreOwner...).
# Same shrink-vs-link issue; keep the runtime surface.
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.core.view.** { *; }
-dontwarn androidx.lifecycle.**

# androidx.tracing.Trace is resolved REFLECTIVELY (by androidx.test's monitor and by
# profileinstaller/startup). R8 cannot see the reference, strips the class, and the
# process dies at startup with:
#   NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
# Debug builds are unaffected, so this is only ever caught by running the MINIFIED
# binary — which is why the release APK must be exercised on a device.
-keep class androidx.tracing.** { *; }
-dontwarn androidx.tracing.**
-keep class androidx.profileinstaller.** { *; }
-keep class androidx.startup.** { *; }

# ---------------------------------------------------------------------------
# Kotlin stdlib names must survive for the instrumentation APK to link.
#
# An instrumented test APK runs INSIDE the app's process and links against the
# app's dex. androidx.test is partly written in Kotlin, so it references
# kotlin.LazyKt, kotlin.jvm.internal.Intrinsics and friends BY NAME. R8 renames
# those classes when minifying the app, and the test APK then dies before the
# first test with:
#   NoClassDefFoundError: Failed resolution of: Lkotlin/LazyKt;
# Keeping the names (not the bodies) costs almost nothing and is required for
# -PtestBuildType=release to work at all.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Kotlin runtime libraries must be kept WHOLE, not just name-preserved.
#
# An instrumented test APK runs inside the app's process and links against the
# app's dex. androidx.test and androidx.compose.ui.test are written in Kotlin and
# reference stdlib/coroutines classes the APP itself never calls — e.g.
# kotlin.LazyKt, kotlinx.coroutines.JobKt, kotlinx.coroutines.DelayWithTimeoutDiagnostics.
#
# `-keepnames` is NOT enough: it preserves names but still lets R8 SHRINK unused
# classes, so each fix just surfaces the next missing class. Keeping these two
# packages entirely is the only convergent answer.
#
# Cost: ~1.5 MB of the release APK. Benefit: the shipped binary is the one that
# was actually tested. Drop these keeps only if you also stop running the
# instrumented suite against release.
# ---------------------------------------------------------------------------
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlin.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# kotlinx.serialization facades are referenced by generated serializers.
-keepnames class kotlinx.serialization.** { *; }
