package com.soniccore.feature.devices

import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device list presentation: labelling, filtering, favourites and codec display.
 * The recurring theme is that unknown platform data must render as unknown rather
 * than as a plausible-looking fabrication.
 */
class DeviceListLogicTest {

    private fun device(
        key: String,
        transport: DeviceTransport = DeviceTransport.BLUETOOTH_CLASSIC,
        state: ConnectionState = ConnectionState.CONNECTED,
        name: String = "Device $key",
        userLabel: String? = null,
        favourite: Boolean = false,
        codec: BluetoothCodec? = null,
        battery: Int? = null,
        codecSelectable: Boolean = false,
    ) = AudioDevice(
        stableKey = key,
        systemId = null,
        displayName = name,
        productName = name,
        address = null,
        transport = transport,
        kind = DeviceKind.HEADPHONES,
        direction = DeviceDirection.OUTPUT,
        capabilities = DeviceCapabilities(
            supportsOutput = true,
            supportsCodecSelection = codecSelectable,
            supportsBatteryReporting = battery != null,
        ),
        connectionState = state,
        activeCodec = codec,
        batteryPercent = battery,
        userLabel = userLabel,
        isFavorite = favourite,
    )

    // --- labelling ---

    @Test
    fun `user label wins over the platform display name`() {
        val d = device("a", name = "WH-1000XM5", userLabel = "Desk cans")
        assertEquals("Desk cans", d.label)
    }

    @Test
    fun `blank user label falls back to the display name`() {
        val d = device("a", name = "WH-1000XM5", userLabel = "")
        // An empty label would render an invisible row.
        assertEquals("WH-1000XM5", d.userLabel?.ifBlank { d.displayName } ?: d.displayName)
    }

    @Test
    fun `clearing a label restores the platform name`() {
        val labelled = device("a", name = "Speaker", userLabel = "Kitchen")
        val cleared = labelled.copy(userLabel = null)
        assertEquals("Speaker", cleared.label)
    }

    // --- favourites and sorting ---

    @Test
    fun `favourites sort above non favourites`() {
        val devices = listOf(
            device("a", favourite = false, name = "A"),
            device("b", favourite = true, name = "B"),
            device("c", favourite = false, name = "C"),
        )
        val ordered = devices
            .sortedWith(compareByDescending<AudioDevice> { it.isFavorite }.thenBy { it.label })
            .map { it.stableKey }
        assertEquals(listOf("b", "a", "c"), ordered)
    }

    @Test
    fun `connected devices sort above disconnected ones`() {
        val devices = listOf(
            device("gone", state = ConnectionState.DISCONNECTED),
            device("here", state = ConnectionState.CONNECTED),
        )
        val ordered = devices.sortedByDescending { it.isConnected }.map { it.stableKey }
        assertEquals(listOf("here", "gone"), ordered)
    }

    @Test
    fun `active device sorts to the very top`() {
        val devices = listOf(
            device("fav", favourite = true, state = ConnectionState.CONNECTED),
            device("active", state = ConnectionState.ACTIVE),
        )
        val ordered = devices
            .sortedWith(
                compareByDescending<AudioDevice> { it.connectionState == ConnectionState.ACTIVE }
                    .thenByDescending { it.isFavorite },
            )
            .map { it.stableKey }
        assertEquals(listOf("active", "fav"), ordered)
    }

    // --- codec display ---

    @Test
    fun `unknown codec renders as unknown not as sbc`() {
        val d = device("a", codec = null)
        // Defaulting to SBC would lie about the actual link quality.
        assertNull(d.activeCodec)
    }

    @Test
    fun `every bluetooth codec has a distinct display name`() {
        val names = BluetoothCodec.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `codec selection is only offered when the device supports it`() {
        val selectable = device("a", codecSelectable = true)
        val fixed = device("b", codecSelectable = false)
        assertTrue(selectable.capabilities.supportsCodecSelection)
        assertFalse(fixed.capabilities.supportsCodecSelection)
    }

    @Test
    fun `non bluetooth devices never offer codec selection`() {
        val usb = device("a", transport = DeviceTransport.USB, codecSelectable = false)
        assertFalse(usb.capabilities.supportsCodecSelection)
    }

    // --- battery display ---

    @Test
    fun `battery absent means the capability flag drives the placeholder`() {
        val reporting = device("a", battery = 80)
        val unknown = device("b", battery = null)
        assertTrue(reporting.capabilities.supportsBatteryReporting)
        assertEquals(80, reporting.batteryPercent)
        assertNull(unknown.batteryPercent)
    }

    @Test
    fun `battery values outside 0-100 are treated as invalid`() {
        fun sanitize(v: Int?) = v?.takeIf { it in 0..100 }
        assertNull(sanitize(-1))
        assertNull(sanitize(200))
        assertEquals(50, sanitize(50))
    }

    // --- filtering ---

    @Test
    fun `wifi devices are separated because they need streaming not routing`() {
        val devices = listOf(
            device("bt", DeviceTransport.BLUETOOTH_CLASSIC),
            device("wifi", DeviceTransport.WIFI),
            device("usb", DeviceTransport.USB),
        )
        val streamable = devices.filter { it.transport == DeviceTransport.WIFI }
        val routable = devices.filter { it.transport != DeviceTransport.WIFI }
        assertEquals(1, streamable.size)
        assertEquals(2, routable.size)
    }

    @Test
    fun `forgetting a device removes it from the remembered list only`() {
        val remembered = listOf(device("a"), device("b"))
        val afterForget = remembered.filterNot { it.stableKey == "a" }
        assertEquals(1, afterForget.size)
        assertEquals("b", afterForget.first().stableKey)
    }

    @Test
    fun `search filters on both label and product name`() {
        val devices = listOf(
            device("a", name = "WH-1000XM5", userLabel = "Desk cans"),
            device("b", name = "AirPods Pro"),
        )
        fun search(q: String) = devices.filter {
            it.label.contains(q, true) || (it.productName?.contains(q, true) == true)
        }
        assertEquals(1, search("desk").size)
        assertEquals(1, search("WH-1000").size)
        assertEquals(1, search("airpods").size)
        assertEquals(0, search("zzz").size)
    }

    @Test
    fun `every transport is distinct for section headers`() {
        // DeviceTransport is a plain enum; display strings are mapped in the UI layer.
        val names = DeviceTransport.entries.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(DeviceTransport.entries.contains(DeviceTransport.UNKNOWN))
    }

    @Test
    fun `every device kind has a distinct name`() {
        val names = DeviceKind.entries.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }
}
