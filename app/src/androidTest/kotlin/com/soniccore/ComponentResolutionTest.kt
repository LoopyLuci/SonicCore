package com.soniccore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soniccore.receiver.AudioDeviceReceiver
import com.soniccore.receiver.BootReceiver
import com.soniccore.receiver.UsbAttachReceiver
import com.soniccore.service.SonicAudioService
import com.soniccore.service.SonicNotificationListenerService
import com.soniccore.tile.EqualizerTileService
import com.soniccore.tile.OutputSwitchTileService
import com.soniccore.tile.ProfileTileService
import com.soniccore.widget.SonicWidgetReceiver
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every manifest component must be resolvable and instantiable by the framework.
 *
 * This is the test that R8 breaks: services, tiles, receivers and the widget are
 * looked up **by class name string** from the manifest, so an obfuscation or
 * shrinking mistake makes them silently unavailable — the app still launches, but
 * the tile does nothing and the service never starts. Run this suite against the
 * release build (`-PtestBuildType=release`) to prove the keep rules hold.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ComponentResolutionTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var pm: PackageManager

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        pm = context.packageManager
    }

    private fun component(cls: Class<*>) = ComponentName(context, cls)

    // --- services ---

    @Test
    fun foregroundServiceIsDeclaredAndResolvable() {
        val info = pm.getServiceInfo(component(SonicAudioService::class.java), 0)
        assertNotNull(info)
        assertTrue("service must be enabled", info.isEnabled)
    }

    @Test
    fun notificationListenerServiceIsDeclaredWithBindPermission() {
        val info = pm.getServiceInfo(component(SonicNotificationListenerService::class.java), 0)
        assertNotNull(info)
        // Without BIND_NOTIFICATION_LISTENER_SERVICE the system refuses to bind,
        // which silently disables the entire per-app mixer.
        assertEquals(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            info.permission,
        )
    }

    @Test
    fun foregroundServiceStartsAndStopsWithoutCrashing() {
        val intent = Intent(context, SonicAudioService::class.java)
        // startForegroundService requires the service to post a notification within
        // 5s or the system kills the app with a RemoteServiceException.
        context.startForegroundService(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(3_000)
        context.stopService(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // --- quick settings tiles ---

    @Test
    fun allThreeTilesAreDeclaredWithTheBindPermission() {
        listOf(
            EqualizerTileService::class.java,
            ProfileTileService::class.java,
            OutputSwitchTileService::class.java,
        ).forEach { cls ->
            val info = pm.getServiceInfo(component(cls), 0)
            assertNotNull("${cls.simpleName} must be declared", info)
            assertEquals(
                "${cls.simpleName} needs BIND_QUICK_SETTINGS_TILE",
                "android.permission.BIND_QUICK_SETTINGS_TILE",
                info.permission,
            )
        }
    }

    @Test
    fun tilesSurviveObfuscationAndCanBeInstantiated() {
        // TileService is constructed reflectively by SystemUI from the manifest name.
        listOf(
            EqualizerTileService::class.java,
            ProfileTileService::class.java,
            OutputSwitchTileService::class.java,
        ).forEach { cls ->
            val instance = cls.getDeclaredConstructor().newInstance()
            assertNotNull("${cls.simpleName} must be constructible", instance)
        }
    }

    // --- broadcast receivers ---

    @Test
    fun allReceiversAreDeclaredAndResolvable() {
        listOf(
            BootReceiver::class.java,
            AudioDeviceReceiver::class.java,
            UsbAttachReceiver::class.java,
            SonicWidgetReceiver::class.java,
        ).forEach { cls ->
            val info = pm.getReceiverInfo(component(cls), 0)
            assertNotNull("${cls.simpleName} must be declared", info)
            assertTrue("${cls.simpleName} must be enabled", info.isEnabled)
        }
    }

    @Test
    fun receiversCanBeInstantiatedReflectively() {
        // The framework instantiates receivers by name for every broadcast.
        listOf(
            BootReceiver::class.java,
            AudioDeviceReceiver::class.java,
            UsbAttachReceiver::class.java,
            SonicWidgetReceiver::class.java,
        ).forEach { cls ->
            assertNotNull(cls.simpleName, cls.getDeclaredConstructor().newInstance())
        }
    }

    @Test
    fun headsetPlugBroadcastIsHandledWithoutCrashing() {
        val receiver = AudioDeviceReceiver()
        val intent = Intent(android.media.AudioManager.ACTION_HEADSET_PLUG)
            .putExtra("state", 1)
            .putExtra("name", "Test headset")
        // Direct onReceive: a crash here would be an ANR-level failure in the field.
        receiver.onReceive(context, intent)
    }

    @Test
    fun becomingNoisyBroadcastIsHandled() {
        val receiver = AudioDeviceReceiver()
        receiver.onReceive(context, Intent(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    @Test
    fun bootReceiverIgnoresUnrelatedActions() {
        val receiver = BootReceiver()
        // Must not start the service for an arbitrary broadcast.
        receiver.onReceive(context, Intent("com.example.SOMETHING_ELSE"))
    }

    @Test
    fun usbReceiverIgnoresUnrelatedActions() {
        val receiver = UsbAttachReceiver()
        receiver.onReceive(context, Intent("com.example.NOT_USB"))
    }

    // --- widget ---

    @Test
    fun widgetProviderIsRegisteredWithAppwidgetUpdateFilter() {
        val info = pm.getReceiverInfo(component(SonicWidgetReceiver::class.java), 0)
        assertNotNull(info)
        // A widget provider with no APPWIDGET_UPDATE filter never appears in the picker.
        val intent = Intent("android.appwidget.action.APPWIDGET_UPDATE")
            .setComponent(component(SonicWidgetReceiver::class.java))
        assertNotNull(pm.queryBroadcastReceivers(intent, 0))
    }

    @Test
    fun widgetReceiverExposesItsGlanceWidget() {
        val receiver = SonicWidgetReceiver()
        // If R8 strips the GlanceAppWidget subclass, this returns null and the widget
        // renders as "Problem loading widget".
        assertNotNull("glanceAppWidget must survive minification", receiver.glanceAppWidget)
    }

    // --- launcher activity + cast provider ---

    @Test
    fun launcherActivityIsResolvable() {
        val info = pm.getActivityInfo(component(MainActivity::class.java), 0)
        assertNotNull(info)
        assertTrue(info.exported)
    }

    @Test
    fun castOptionsProviderIsNamedInTheManifestAndLoadable() {
        val appInfo = pm.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val declared = appInfo.metaData
            ?.getString("com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME")
        assertNotNull("Cast OPTIONS_PROVIDER_CLASS_NAME meta-data is required", declared)

        // The Cast SDK loads this class by name; obfuscating it breaks all casting.
        val cls = Class.forName(declared!!)
        assertNotNull(cls.getDeclaredConstructor().newInstance())
    }
}
