package com.soniccore.core.model.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device identity is a correctness requirement, not a nicety: if [AudioDevice.stableKey]
 * changes across a reconnect, every profile bound to that device silently detaches.
 */
class AudioDeviceIdentityTest {

    @Test
    fun `stable key survives a reconnect that changes the system id`() {
        val first = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "AA:BB:CC:DD:EE:FF", "WH-1000XM5", DeviceDirection.OUTPUT,
        )
        val second = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "AA:BB:CC:DD:EE:FF", "WH-1000XM5", DeviceDirection.OUTPUT,
        )
        assertEquals("same hardware must yield the same key", first, second)
    }

    @Test
    fun `address takes priority over product name`() {
        // Two identical models must not collide.
        val deskPair = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "AA:AA:AA:AA:AA:AA", "AirPods Pro", DeviceDirection.OUTPUT,
        )
        val gymPair = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "BB:BB:BB:BB:BB:BB", "AirPods Pro", DeviceDirection.OUTPUT,
        )
        assertNotEquals("same model, different hardware must differ", deskPair, gymPair)
    }

    @Test
    fun `falls back to product name when there is no address`() {
        val key = AudioDevice.buildStableKey(
            DeviceTransport.USB, null, "Schiit Modi 3", DeviceDirection.OUTPUT,
        )
        assertTrue(key.contains("Schiit Modi 3"))
        assertTrue(key.startsWith("USB:OUTPUT:"))
    }

    @Test
    fun `blank and placeholder addresses are rejected in favour of the name`() {
        // Some OEMs report an all-zero MAC; keying on it would merge every device.
        val zeroMac = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "00:00:00:00:00:00", "Speaker A", DeviceDirection.OUTPUT,
        )
        val blank = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "", "Speaker B", DeviceDirection.OUTPUT,
        )
        assertTrue(zeroMac.contains("Speaker A"))
        assertTrue(blank.contains("Speaker B"))
        assertNotEquals(zeroMac, blank)
    }

    @Test
    fun `input and output on the same hardware are distinct keys`() {
        // A headset is one device but two endpoints; profiles bind to them separately.
        val out = AudioDevice.buildStableKey(
            DeviceTransport.USB, "usb:1", "Yeti", DeviceDirection.OUTPUT,
        )
        val input = AudioDevice.buildStableKey(
            DeviceTransport.USB, "usb:1", "Yeti", DeviceDirection.INPUT,
        )
        assertNotEquals(out, input)
    }

    @Test
    fun `transport is part of identity so a dual-mode device does not collide`() {
        val classic = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_CLASSIC, "AA:BB", "Buds", DeviceDirection.OUTPUT,
        )
        val le = AudioDevice.buildStableKey(
            DeviceTransport.BLUETOOTH_LE, "AA:BB", "Buds", DeviceDirection.OUTPUT,
        )
        assertNotEquals(classic, le)
    }

    @Test
    fun `completely unknown device still produces a usable key`() {
        val key = AudioDevice.buildStableKey(
            DeviceTransport.UNKNOWN, null, null, DeviceDirection.OUTPUT,
        )
        assertEquals("UNKNOWN:OUTPUT:unknown", key)
    }

    @Test
    fun `user label overrides display name but identity is unchanged`() {
        val device = AudioDevice(
            stableKey = "k", systemId = 1, displayName = "WH-1000XM5", productName = "WH-1000XM5",
            address = "AA", transport = DeviceTransport.BLUETOOTH_CLASSIC, kind = DeviceKind.HEADPHONES,
            direction = DeviceDirection.OUTPUT, capabilities = DeviceCapabilities(supportsOutput = true),
        )
        assertEquals("WH-1000XM5", device.label)
        assertEquals("Desk cans", device.copy(userLabel = "Desk cans").label)
        assertEquals("k", device.copy(userLabel = "Desk cans").stableKey)
    }

    @Test
    fun `wireless and connected flags reflect transport and state`() {
        fun device(transport: DeviceTransport, state: ConnectionState) = AudioDevice(
            stableKey = "k", systemId = null, displayName = "d", productName = null, address = null,
            transport = transport, kind = DeviceKind.UNKNOWN, direction = DeviceDirection.OUTPUT,
            capabilities = DeviceCapabilities(supportsOutput = true), connectionState = state,
        )

        assertTrue(device(DeviceTransport.BLUETOOTH_CLASSIC, ConnectionState.CONNECTED).isWireless)
        assertTrue(device(DeviceTransport.WIFI, ConnectionState.CONNECTED).isWireless)
        assertTrue(!device(DeviceTransport.USB, ConnectionState.CONNECTED).isWireless)
        assertTrue(!device(DeviceTransport.ANALOG_35MM, ConnectionState.CONNECTED).isWireless)

        assertTrue(device(DeviceTransport.USB, ConnectionState.ACTIVE).isConnected)
        assertTrue(device(DeviceTransport.USB, ConnectionState.CONNECTED).isConnected)
        assertTrue(!device(DeviceTransport.USB, ConnectionState.DISCONNECTED).isConnected)
        assertTrue(!device(DeviceTransport.USB, ConnectionState.ERROR).isConnected)
    }

    @Test
    fun `battery is nullable so unknown is never fabricated`() {
        val device = AudioDevice(
            stableKey = "k", systemId = null, displayName = "d", productName = null, address = null,
            transport = DeviceTransport.BLUETOOTH_CLASSIC, kind = DeviceKind.EARBUDS,
            direction = DeviceDirection.OUTPUT,
            capabilities = DeviceCapabilities(supportsOutput = true, supportsBatteryReporting = true),
        )
        // The platform hides battery level; null must remain null.
        assertNull(device.batteryPercent)
        assertNull(device.secondaryBatteryPercent)
        assertNull(device.caseBatteryPercent)
    }

    @Test
    fun `capabilities derive max channel count from the reported list`() {
        val caps = DeviceCapabilities(supportsOutput = true, channelCounts = listOf(1, 2, 6, 8))
        assertEquals(8, caps.maxChannelCount)
        // Defaults to stereo when the platform reports nothing.
        assertEquals(2, DeviceCapabilities(supportsOutput = true).maxChannelCount)
    }

    @Test
    fun `bidirectional requires both directions`() {
        assertTrue(
            DeviceCapabilities(supportsOutput = true, supportsInput = true).isBidirectional,
        )
        assertTrue(!DeviceCapabilities(supportsOutput = true).isBidirectional)
        assertTrue(!DeviceCapabilities(supportsInput = true).isBidirectional)
    }
}
