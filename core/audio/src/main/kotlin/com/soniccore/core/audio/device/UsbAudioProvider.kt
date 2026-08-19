package com.soniccore.core.audio.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.soniccore.core.model.device.UsbAudioClass
import javax.inject.Inject
import javax.inject.Singleton

data class UsbAudioDetail(
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val audioClass: UsbAudioClass,
    val interfaceCount: Int,
    val hasStreamingInterface: Boolean,
    val hasMidi: Boolean,
    val serialNumber: String?,
)

/**
 * Enumerates attached USB Audio Class devices.
 *
 * Android routes UAC devices through the platform mixer automatically. Bit-perfect
 * / DSD playback would require taking exclusive control of the interface with our
 * own UAC driver — that is an explicitly experimental path, not something to
 * silently promise.
 */
@Singleton
class UsbAudioProvider @Inject constructor(
    private val context: Context,
) {
    private val usbManager: UsbManager? =
        ContextCompat.getSystemService(context, UsbManager::class.java)

    fun attachedAudioDevices(): List<UsbAudioDetail> {
        val manager = usbManager ?: return emptyList()
        return runCatching {
            manager.deviceList.values.mapNotNull { device -> detailFor(device) }
        }.getOrDefault(emptyList())
    }

    fun detailFor(device: UsbDevice): UsbAudioDetail? {
        val audioInterfaces = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }

        if (audioInterfaces.isEmpty()) return null

        return UsbAudioDetail(
            deviceName = device.deviceName,
            productName = device.productName,
            manufacturerName = device.manufacturerName,
            vendorId = device.vendorId,
            productId = device.productId,
            audioClass = classify(audioInterfaces),
            interfaceCount = audioInterfaces.size,
            hasStreamingInterface = audioInterfaces.any { it.interfaceSubclass == SUBCLASS_STREAMING },
            hasMidi = audioInterfaces.any { it.interfaceSubclass == SUBCLASS_MIDI },
            serialNumber = runCatching { device.serialNumber }.getOrNull(),
        )
    }

    /**
     * UAC revision is carried in the audio control interface protocol byte:
     * 0x00 = UAC1, 0x20 = UAC2, 0x30 = UAC3.
     */
    private fun classify(interfaces: List<UsbInterface>): UsbAudioClass {
        val control = interfaces.firstOrNull { it.interfaceSubclass == SUBCLASS_CONTROL }
            ?: return UsbAudioClass.UNKNOWN
        return when (control.interfaceProtocol) {
            PROTOCOL_UAC1 -> UsbAudioClass.UAC1
            PROTOCOL_UAC2 -> UsbAudioClass.UAC2
            PROTOCOL_UAC3 -> UsbAudioClass.UAC3
            else -> UsbAudioClass.UNKNOWN
        }
    }

    fun hasPermission(device: UsbDevice): Boolean =
        usbManager?.hasPermission(device) ?: false

    fun findByVendorProduct(vendorId: Int, productId: Int): UsbDevice? =
        usbManager?.deviceList?.values?.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }

    companion object {
        private const val SUBCLASS_CONTROL = 1
        private const val SUBCLASS_STREAMING = 2
        private const val SUBCLASS_MIDI = 3
        private const val PROTOCOL_UAC1 = 0x00
        private const val PROTOCOL_UAC2 = 0x20
        private const val PROTOCOL_UAC3 = 0x30
    }
}
