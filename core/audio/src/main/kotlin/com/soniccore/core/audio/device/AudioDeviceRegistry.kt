package com.soniccore.core.audio.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.device.UsbAudioClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
 *  2. Bluetooth detail (battery, codec) layered onto matching BT endpoints
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
) {

    /** Raw platform endpoints, re-enumerated whenever anything changes. */
    private val platformDevices: Flow<List<AudioDevice>> = callbackFlow {
        fun emit() {
            trySend(enumeratePlatform())
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = emit()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = emit()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

        // Wired-headset plug and USB attach also warrant a refresh: the device
        // callback can fire before the platform finishes publishing capabilities.
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

        awaitClose {
            audioManager.unregisterAudioDeviceCallback(callback)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.conflate()

    private fun enumeratePlatform(): List<AudioDevice> = runCatching {
        val activeOutputId = currentActiveOutputId()
        val activeInputId = currentActiveInputId()
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .map { info ->
                AudioDeviceMapper.map(
                    info = info,
                    isActiveOutput = info.id == activeOutputId,
                    isActiveInput = info.id == activeInputId,
                )
            }
            // The platform lists sink and source separately for the same hardware;
            // dedupe on our stable key, preferring the bidirectional/active entry.
            .groupBy { it.stableKey }
            .map { (_, group) ->
                group.maxByOrNull { device ->
                    (if (device.connectionState == ConnectionState.ACTIVE) 10 else 0) +
                        (if (device.capabilities.isBidirectional) 5 else 0)
                } ?: group.first()
            }
    }.getOrDefault(emptyList())

    private fun currentActiveOutputId(): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.id
        } else {
            null
        }
    }.getOrNull()

    private fun currentActiveInputId(): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.id
        } else {
            null
        }
    }.getOrNull()

    /** Bluetooth battery/codec detail keyed by MAC address. */
    private val bluetoothDetails: Flow<Map<String, BluetoothDeviceDetail>> =
        bluetoothInfo.observeChanges().map {
            bluetoothInfo.connectedDevices().associateBy { detail -> detail.address }
        }.conflate()

    /** USB descriptors keyed by product name. */
    private val usbDetails: Flow<Map<String, UsbAudioDetail>> =
        platformDevices.map {
            usbAudio.attachedAudioDevices().associateBy { detail ->
                detail.productName ?: detail.deviceName
            }
        }.conflate()

    private val networkDevices: Flow<List<AudioDevice>> = wifiDiscovery.discover()

    /** The merged, enriched device list the whole app consumes. */
    val devices: Flow<List<AudioDevice>> = combine(
        platformDevices,
        bluetoothDetails,
        usbDetails,
        networkDevices,
    ) { platform, btDetails, usbInfo, network ->
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

                else -> device
            }
        }
        (enriched + network).distinctBy { it.stableKey }
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
