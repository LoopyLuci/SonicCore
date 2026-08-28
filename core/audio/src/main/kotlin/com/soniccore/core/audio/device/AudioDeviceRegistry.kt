package com.soniccore.core.audio.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.soniccore.core.common.diagnostics.DiagnosticLog
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.device.WifiProtocol
import com.soniccore.core.model.device.UsbAudioClass
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.AudioEncoding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for every audio endpoint the device can see.
 *
 * Merges four streams:
 *  1. platform enumeration (wired / USB / Bluetooth / HDMI / built-in)
 *  2. Bluetooth A2DP devices from the A2DP proxy (covers MIUI gaps)
 *  3. USB descriptors (UAC class, VID/PID) layered onto matching USB endpoints
 *  4. mDNS network speakers, which the platform never reports
 */
@Singleton
class AudioDeviceRegistry @Inject constructor(
    private val context: Context,
    private val audioManager: AudioManager,
    private val bluetoothInfo: BluetoothInfoProvider,
    private val usbAudio: UsbAudioProvider,
    private val wifiDiscovery: WifiSpeakerDiscovery,
    private val diagnostics: DiagnosticLog,
) {

    /** Raw platform endpoints, re-enumerated whenever anything changes. */
    private val platformDevices: Flow<List<AudioDevice>> = callbackFlow {
        fun emit() {
            try {
                trySend(enumeratePlatform())
            } catch (t: Throwable) {
                // A transient platform or DB error must not kill the flow; emit an
                // empty list so downstream UI stays alive and can retry on the next
                // callback.
                trySend(emptyList())
            }
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = emit()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = emit()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = emit()
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        emit()

        // MIUI and some OEMs publish the device list before the Bluetooth
        // stack finishes initializing, so the first callback returns empty.
        // Re-enumerate once after the stack settles so sinks like JBL A2DP
        // actually appear. 1.5s covers the worst-case MIUI delay without
        // causing perceptible UI lag.
        CoroutineScope(coroutineContext).launch {
            delay(1500L)
            trySend(runCatching { enumeratePlatform() }.getOrDefault(emptyList()))
        }

        awaitClose {
            audioManager.unregisterAudioDeviceCallback(callback)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.conflate()

    /**
     * Enumerate all audio devices: platform-reported plus A2DP proxy-discovered.
     *
     * On some OEM builds (notably MIUI), [AudioManager.getDevices] does not include
     * Bluetooth A2DP sinks, so we supplement with [BluetoothInfoProvider.connectedDevices]
     * which uses the A2DP proxy to discover them.
     */
    private fun enumeratePlatform(): List<AudioDevice> = runCatching {
        val activeOutputId = currentActiveOutputId()
        val activeInputId = currentActiveInputId()

        // Platform-reported devices.
        val platformList = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .map { info ->
                AudioDeviceMapper.map(
                    info = info,
                    isActiveOutput = info.id == activeOutputId,
                    isActiveInput = info.id == activeInputId,
                )
            }
            .groupBy { it.stableKey }
            .map { (_, group) ->
                group.maxByOrNull { device ->
                    (if (device.connectionState == ConnectionState.ACTIVE) 10 else 0) +
                        (if (device.capabilities.isBidirectional) 5 else 0)
                } ?: group.first()
            }

        // A2DP-discovered devices — covers MIUI gaps where getDevices() omits A2DP sinks.
        // Use BluetoothManager.getConnectedDevices(A2DP) which is reliable across all OEMs,
        // not the proxy-based path which can return empty before the proxy callback fires.
        val proxyList = runCatching {
            bluetoothInfo.getA2dpDevicesDirect().map { detail ->
                val friendlyName = DeviceNamingPolicy.friendlyNameForBluetooth(detail.name, detail.address)
                AudioDevice(
                    stableKey = AudioDevice.buildStableKey(
                        DeviceTransport.BLUETOOTH_CLASSIC,
                        detail.address,
                        friendlyName,
                        DeviceDirection.OUTPUT,
                    ),
                    systemId = null,
                    displayName = friendlyName,
                    productName = friendlyName,
                    address = detail.address,
                    transport = DeviceTransport.BLUETOOTH_CLASSIC,
                    kind = DeviceKind.HEADPHONES,
                    direction = DeviceDirection.OUTPUT,
                    capabilities = DeviceCapabilities(
                        supportsOutput = true,
                        supportsInput = false,
                        channelCounts = listOf(2),
                        sampleRates = listOf(48_000),
                        encodings = listOf(AudioEncoding.PCM_16BIT),
                        hasHardwareVolume = true,
                        supportsCodecSelection = detail.codecControlSupported,
                        supportsBatteryReporting = true,
                    ),
                    connectionState = ConnectionState.CONNECTED,
                    batteryPercent = detail.batteryPercent,
                    activeCodec = detail.activeCodec,
                    availableCodecs = detail.availableCodecs,
                    lastSeenEpochMs = System.currentTimeMillis(),
                )
            }
        }.getOrDefault(emptyList())

        // Merge: platform list first, then add proxy devices not already present.
        (platformList + proxyList).distinctBy { it.stableKey }
    }.getOrDefault(emptyList())

    private fun currentActiveOutputId(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.id
        } else {
            null
        }
    }.getOrNull()

    private fun currentActiveInputId(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.id
        } else {
            null
        }
    }.getOrNull()

    /** Bluetooth battery/codec detail keyed by MAC address (for enriching platform devices). */
    private val bluetoothDetails: Flow<Map<String, BluetoothDeviceDetail>> = bluetoothInfo.connectedA2dpDevicesFlow().map { details ->
        details.associateBy { it.address }
    }.conflate()

    /** USB descriptors keyed by product name. */
    private val usbDetails: Flow<Map<String, UsbAudioDetail>> =
        platformDevices.map {
            usbAudio.attachedAudioDevices().associateBy { detail ->
                detail.productName ?: detail.deviceName
            }
        }.conflate()

    private val networkDevices: Flow<List<AudioDevice>> = wifiDiscovery.discover()

    /** Bluetooth A2DP devices flow — used to surface devices the platform doesn't enumerate. */
    private val proxyA2dpDevices: Flow<List<AudioDevice>> = bluetoothInfo.connectedA2dpDevicesFlow().map { details ->
        details.map { detail ->
            val friendlyName = DeviceNamingPolicy.friendlyNameForBluetooth(detail.name, detail.address)
            AudioDevice(
                stableKey = AudioDevice.buildStableKey(
                    DeviceTransport.BLUETOOTH_CLASSIC,
                    detail.address,
                    friendlyName,
                    DeviceDirection.OUTPUT,
                ),
                systemId = null,
                displayName = friendlyName,
                productName = friendlyName,
                address = detail.address,
                transport = DeviceTransport.BLUETOOTH_CLASSIC,
                kind = DeviceKind.HEADPHONES,
                direction = DeviceDirection.OUTPUT,
                capabilities = DeviceCapabilities(
                    supportsOutput = true,
                    supportsInput = false,
                    channelCounts = listOf(2),
                    sampleRates = listOf(48_000),
                    encodings = listOf(AudioEncoding.PCM_16BIT),
                    hasHardwareVolume = true,
                    supportsCodecSelection = detail.codecControlSupported,
                    supportsBatteryReporting = true,
                ),
                connectionState = ConnectionState.CONNECTED,
                batteryPercent = detail.batteryPercent,
                activeCodec = detail.activeCodec,
                availableCodecs = detail.availableCodecs,
                lastSeenEpochMs = System.currentTimeMillis(),
            )
        }
    }.conflate()

    /** The merged, enriched device list the whole app consumes. */
    val devices: Flow<List<AudioDevice>> = combine(
        platformDevices,
        bluetoothDetails,
        usbDetails,
        networkDevices,
        proxyA2dpDevices,
    ) { platform, btDetails, usbInfo, network, proxy ->
        val enriched = platform.map { device ->
            when (device.transport) {
                DeviceTransport.BLUETOOTH_CLASSIC, DeviceTransport.BLUETOOTH_LE -> {
                    val detail = device.address?.let { btDetails[it] }
                        ?: btDetails.values.firstOrNull { it.name == device.productName }
                    if (detail == null) device else device.copy(
                        batteryPercent = detail.batteryPercent,
                        activeCodec = detail.activeCodec,
                        availableCodecs = detail.availableCodecs,
                        capabilities = device.capabilities.copy(
                            supportsCodecSelection = detail.codecControlSupported,
                        ),
                    )
                }

                DeviceTransport.USB -> {
                    val detail = usbInfo[device.productName] ?: usbInfo.values.firstOrNull()
                    if (detail == null) device else device.copy(
                        usbAudioClass = detail.audioClass,
                        vendorId = detail.vendorId,
                        productId = detail.productId,
                    )
                }

                DeviceTransport.WIFI -> {
                    if (device.wifiProtocol == null) {
                        diagnostics.w("AudioDeviceRegistry", "network speaker with unknown protocol: ${device.label} address=${device.address} service=${device.wifiProtocol?.serviceType}")
                    }
                    device
                }

                else -> device
            }
        }
        (enriched + network + proxy).distinctBy { it.stableKey }
    }.distinctUntilChanged()

    val outputDevices: Flow<List<AudioDevice>> = devices.map { list ->
        list.filter { it.canOutput }.sortedWith(devicePriority)
    }

    val inputDevices: Flow<List<AudioDevice>> = devices.map { list ->
        list.filter { it.canInput }.sortedWith(devicePriority)
    }

    fun stateIn(scope: CoroutineScope) = devices.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun snapshotPlatformDevices(): List<AudioDevice> = enumeratePlatform()

    fun findSystemDevice(stableKey: String): AudioDeviceInfo? = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL).firstOrNull { info ->
            val mapped = AudioDeviceMapper.map(info)
            mapped.stableKey == stableKey
        }
    }.getOrNull()

    private val devicePriority = compareByDescending<AudioDevice> { device ->
        when {
            device.connectionState == ConnectionState.ACTIVE -> 100
            device.isFavorite -> 50
            device.transport == DeviceTransport.BUILTIN -> 1
            device.isConnected -> 20
            else -> 5
        }
    }.thenBy { it.displayName }

    companion object {
        /** UAC devices that expose no descriptors still deserve a sane label. */
        val UNKNOWN_USB = UsbAudioClass.UNKNOWN
    }
}
