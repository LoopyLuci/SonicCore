package com.soniccore.core.audio.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccore.core.model.device.BluetoothCodec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/** Battery + codec detail for one Bluetooth endpoint. */
data class BluetoothDeviceDetail(
    val address: String,
    val name: String?,
    val batteryPercent: Int? = null,
    val activeCodec: BluetoothCodec? = null,
    val availableCodecs: List<BluetoothCodec> = emptyList(),
    val isLeAudio: Boolean = false,
    val codecControlSupported: Boolean = false,
)

/**
 * Bluetooth detail source.
 *
 * Codec status and battery level are `@hide`/`@SystemApi` on Android. We reach them
 * through documented broadcasts where possible and reflection where not — every
 * reflective call is wrapped so an OEM that blocks it degrades to read-only
 * display instead of crashing.
 */
@Singleton
class BluetoothInfoProvider @Inject constructor(
    private val context: Context,
) {
    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(context, BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var a2dpProxy: BluetoothA2dp? = null

    val hasBluetoothPermission: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    val isBluetoothSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    /** Connect the A2DP proxy so codec queries can work. Safe to call repeatedly. */
    fun connectProxy(onReady: () -> Unit = {}) {
        if (!hasBluetoothPermission || a2dpProxy != null) {
            onReady()
            return
        }
        connectProxyWithRetry(onReady, attempt = 0)
    }

    private fun connectProxyWithRetry(onReady: () -> Unit, attempt: Int) {
        val maxAttempts = 3
        if (attempt >= maxAttempts) {
            onReady()
            return
        }
        runCatching {
            adapter?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = proxy as? BluetoothA2dp
                            onReady()
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    }
                },
                BluetoothProfile.A2DP,
            )
        }
    }

    fun releaseProxy() {
        runCatching {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        }
        a2dpProxy = null
    }

    /**
     * Enumerate A2DP devices from the BluetoothManager directly — works on all OEMs.
     * This uses BluetoothManager.getConnectedDevices(BluetoothProfile.A2DP) which is
     * the documented API for discovering connected A2DP sinks.
     */
    @SuppressLint("MissingPermission")
    fun getA2dpDevicesDirect(): List<BluetoothDeviceDetail> {
        if (!hasBluetoothPermission) return emptyList()
        return runCatching {
            val btm = bluetoothManager ?: return emptyList()
            val devices = btm.getConnectedDevices(BluetoothProfile.A2DP)
            devices.map { detailFor(it) }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun connectedDevices(): List<BluetoothDeviceDetail> {
        if (!hasBluetoothPermission) return emptyList()
        return runCatching {
            val devices = a2dpProxy?.connectedDevices ?: emptyList()
            devices.map { detailFor(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Flow that emits connected A2DP devices — waits for proxy before first emission.
     */
    fun connectedA2dpDevicesFlow(): Flow<List<BluetoothDeviceDetail>> = callbackFlow {
        connectProxy {}

        var proxyReady = false
        while (!proxyReady && hasBluetoothPermission) {
            delay(500L)
            if (a2dpProxy != null) {
                proxyReady = true
                break
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        when (state) {
                            BluetoothAdapter.STATE_ON -> {
                                connectProxy {}
                                trySend(currentSnapshot())
                            }
                            BluetoothAdapter.STATE_OFF -> {
                                a2dpProxy = null
                                trySend(emptyList())
                            }
                            else -> {}
                        }
                    }
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    ACTION_BATTERY_LEVEL_CHANGED,
                    ACTION_CODEC_CONFIG_CHANGED -> {
                        trySend(currentSnapshot())
                    }
                    else -> {}
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(ACTION_CODEC_CONFIG_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        trySend(currentSnapshot())

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { releaseProxy() }
        }
    }

    @SuppressLint("MissingPermission")
    fun detailFor(device: BluetoothDevice): BluetoothDeviceDetail {
        val name = DeviceNamingPolicy.friendlyNameForBluetooth(
            runCatching { device.name }.getOrNull(),
            device.address,
        )
        return BluetoothDeviceDetail(
            address = device.address,
            name = name,
            batteryPercent = readBatteryLevel(device),
            activeCodec = readActiveCodec(device),
            availableCodecs = readAvailableCodecs(device),
            isLeAudio = false,
            codecControlSupported = a2dpProxy != null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun currentSnapshot(): List<BluetoothDeviceDetail> {
        if (!hasBluetoothPermission) return emptyList()
        return runCatching {
            val devices = a2dpProxy?.connectedDevices ?: emptyList()
            devices.map { detailFor(it) }
        }.getOrDefault(emptyList())
    }

    /** `BluetoothDevice.getBatteryLevel()` is hidden API. Reflection is the only way. */
    private fun readBatteryLevel(device: BluetoothDevice): Int? = runCatching {
        val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        val level = method.invoke(device) as? Int ?: return null
        level.takeIf { it in 0..100 }
    }.getOrNull()

    /** `BluetoothA2dp.getCodecStatus` is @SystemApi — reflective, best-effort. */
    private fun readActiveCodec(device: BluetoothDevice): BluetoothCodec? = runCatching {
        val proxy = a2dpProxy ?: return null
        val getCodecStatus = BluetoothA2dp::class.java.getMethod(
            "getCodecStatus",
            BluetoothDevice::class.java,
        )
        val status = getCodecStatus.invoke(proxy, device) ?: return null
        val getCodecConfig = status.javaClass.getMethod("getCodecConfig")
        val config = getCodecConfig.invoke(status) ?: return null
        val getCodecType = config.javaClass.getMethod("getCodecType")
        codecFromPlatformType(getCodecType.invoke(config) as? Int ?: return null)
    }.getOrNull()

    private fun readAvailableCodecs(device: BluetoothDevice): List<BluetoothCodec> = runCatching {
        val proxy = a2dpProxy ?: return emptyList()
        val getCodecStatus = BluetoothA2dp::class.java.getMethod(
            "getCodecStatus",
            BluetoothDevice::class.java,
        )
        val status = getCodecStatus.invoke(proxy, device) ?: return emptyList()
        val getSelectable = status.javaClass.getMethod("getCodecsSelectableCapabilities")
        @Suppress("UNCHECKED_CAST")
        val list = getSelectable.invoke(status) as? List<Any> ?: return emptyList()
        list.mapNotNull { config ->
            runCatching {
                val getCodecType = config.javaClass.getMethod("getCodecType")
                codecFromPlatformType(getCodecType.invoke(config) as? Int ?: return@runCatching null)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** Requesting a codec requires `BLUETOOTH_PRIVILEGED` on most builds. */
    fun requestCodec(device: BluetoothDevice, codec: BluetoothCodec): Boolean = runCatching {
        val proxy = a2dpProxy ?: return false
        val configClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
        val builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setCodecType", Int::class.javaPrimitiveType)
            .invoke(builder, platformTypeFor(codec))
        val config = builderClass.getMethod("build").invoke(builder)
        val setPreference = BluetoothA2dp::class.java.getMethod(
            "setCodecConfigPreference",
            BluetoothDevice::class.java,
            configClass,
        )
        setPreference.invoke(proxy, device, config)
        true
    }.getOrDefault(false)

    companion object codecConstants {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

        private const val SOURCE_CODEC_TYPE_SBC = 0
        private const val SOURCE_CODEC_TYPE_AAC = 1
        private const val SOURCE_CODEC_TYPE_APTX = 2
        private const val SOURCE_CODEC_TYPE_APTX_HD = 3
        private const val SOURCE_CODEC_TYPE_LDAC = 4
        private const val SOURCE_CODEC_TYPE_LC3 = 5
        private const val SOURCE_CODEC_TYPE_OPUS = 6

        fun codecFromPlatformType(type: Int): BluetoothCodec = when (type) {
            SOURCE_CODEC_TYPE_SBC -> BluetoothCodec.SBC
            SOURCE_CODEC_TYPE_AAC -> BluetoothCodec.AAC
            SOURCE_CODEC_TYPE_APTX -> BluetoothCodec.APTX
            SOURCE_CODEC_TYPE_APTX_HD -> BluetoothCodec.APTX_HD
            SOURCE_CODEC_TYPE_LDAC -> BluetoothCodec.LDAC
            SOURCE_CODEC_TYPE_LC3 -> BluetoothCodec.LC3
            SOURCE_CODEC_TYPE_OPUS -> BluetoothCodec.OPUS
            else -> BluetoothCodec.UNKNOWN
        }

        fun platformTypeFor(codec: BluetoothCodec): Int = when (codec) {
            BluetoothCodec.SBC -> SOURCE_CODEC_TYPE_SBC
            BluetoothCodec.AAC -> SOURCE_CODEC_TYPE_AAC
            BluetoothCodec.APTX -> SOURCE_CODEC_TYPE_APTX
            BluetoothCodec.APTX_HD -> SOURCE_CODEC_TYPE_APTX_HD
            BluetoothCodec.LDAC -> SOURCE_CODEC_TYPE_LDAC
            BluetoothCodec.LC3 -> SOURCE_CODEC_TYPE_LC3
            BluetoothCodec.OPUS -> SOURCE_CODEC_TYPE_OPUS
            else -> SOURCE_CODEC_TYPE_SBC
        }
    }
}
