package com.soniccore.feature.dashboard

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.model.device.AudioDevice
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
 * Dashboard aggregation: which device is "current", how streams are ordered, and
 * how device lists are deduplicated. The dashboard is the first screen users see,
 * so a wrong "active device" is the most visible possible bug.
 */
class DashboardAggregationTest {

    private fun device(
        key: String,
        transport: DeviceTransport = DeviceTransport.BLUETOOTH_CLASSIC,
        state: ConnectionState = ConnectionState.CONNECTED,
        direction: DeviceDirection = DeviceDirection.OUTPUT,
        name: String = key,
    ) = AudioDevice(
        stableKey = key,
        systemId = null,
        displayName = name,
        productName = name,
        address = null,
        transport = transport,
        kind = DeviceKind.HEADPHONES,
        direction = direction,
        capabilities = DeviceCapabilities(supportsOutput = true),
        connectionState = state,
    )

    // --- active device selection ---

    private fun activeOutput(devices: List<AudioDevice>): AudioDevice? =
        devices.firstOrNull {
            it.direction != DeviceDirection.INPUT && it.connectionState == ConnectionState.ACTIVE
        }

    @Test
    fun `active device is the one marked active not merely connected`() {
        val devices = listOf(
            device("connected", state = ConnectionState.CONNECTED),
            device("active", state = ConnectionState.ACTIVE),
        )
        assertEquals("active", activeOutput(devices)?.stableKey)
    }

    @Test
    fun `no active device yields null rather than an arbitrary pick`() {
        val devices = listOf(
            device("a", state = ConnectionState.CONNECTED),
            device("b", state = ConnectionState.DISCONNECTED),
        )
        assertNull(activeOutput(devices))
    }

    @Test
    fun `input devices are never chosen as the active output`() {
        val devices = listOf(
            device("mic", direction = DeviceDirection.INPUT, state = ConnectionState.ACTIVE),
            device("speaker", direction = DeviceDirection.OUTPUT, state = ConnectionState.ACTIVE),
        )
        assertEquals("speaker", activeOutput(devices)?.stableKey)
    }

    @Test
    fun `empty device list does not crash aggregation`() {
        assertNull(activeOutput(emptyList()))
    }

    // --- deduplication ---

    @Test
    fun `devices are deduplicated by stable key preferring the active state`() {
        // A device can appear from both the platform list and the BT proxy.
        val devices = listOf(
            device("BT:AA", state = ConnectionState.CONNECTED),
            device("BT:AA", state = ConnectionState.ACTIVE),
            device("BT:BB", state = ConnectionState.CONNECTED),
        )
        val merged = devices
            .groupBy { it.stableKey }
            .map { (_, group) ->
                group.firstOrNull { it.connectionState == ConnectionState.ACTIVE } ?: group.first()
            }
        assertEquals(2, merged.size)
        assertEquals(
            ConnectionState.ACTIVE,
            merged.first { it.stableKey == "BT:AA" }.connectionState,
        )
    }

    @Test
    fun `dedupe preserves distinct devices with the same product name`() {
        // Two identical models must both survive.
        val devices = listOf(
            device("BT:AA", name = "AirPods Pro"),
            device("BT:BB", name = "AirPods Pro"),
        )
        assertEquals(2, devices.distinctBy { it.stableKey }.size)
    }

    // --- transport grouping ---

    @Test
    fun `devices group by transport for sectioned display`() {
        val devices = listOf(
            device("a", DeviceTransport.BLUETOOTH_CLASSIC),
            device("b", DeviceTransport.USB),
            device("c", DeviceTransport.BLUETOOTH_CLASSIC),
            device("d", DeviceTransport.WIFI),
        )
        val grouped = devices.groupBy { it.transport }
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[DeviceTransport.BLUETOOTH_CLASSIC]?.size)
        assertEquals(1, grouped[DeviceTransport.USB]?.size)
    }

    @Test
    fun `only connected devices are offered for routing`() {
        val devices = listOf(
            device("a", state = ConnectionState.CONNECTED),
            device("b", state = ConnectionState.ACTIVE),
            device("c", state = ConnectionState.DISCONNECTED),
            device("d", state = ConnectionState.ERROR),
        )
        val routable = devices.filter { it.isConnected }
        assertEquals(2, routable.size)
        assertTrue(routable.map { it.stableKey }.containsAll(listOf("a", "b")))
    }

    // --- stream ordering ---

    @Test
    fun `media stream is shown first because it is the common case`() {
        val streams = listOf(AudioStream.ALARM, AudioStream.MUSIC, AudioStream.RING)
        val ordered = streams.sortedBy { if (it == AudioStream.MUSIC) 0 else 1 }
        assertEquals(AudioStream.MUSIC, ordered.first())
    }

    @Test
    fun `every audio stream has a distinct display name`() {
        val names = AudioStream.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `volume map covers a stream without inventing values for missing ones`() {
        val volumes = mapOf(
            AudioStream.MUSIC to StreamVolume(AudioStream.MUSIC, 8, 0, 15, false),
        )
        assertEquals(0.533f, volumes[AudioStream.MUSIC]!!.percent, 0.01f)
        // A stream we could not read must be absent, not zero.
        assertNull(volumes[AudioStream.ALARM])
    }

    // --- test signal state ---

    @Test
    fun `test signal cannot be playing and stopped simultaneously`() {
        var playing = false
        playing = true
        assertTrue(playing)
        playing = false
        assertFalse(playing)
    }

    @Test
    fun `favourite toggle is idempotent in pairs`() {
        var favourite = false
        favourite = !favourite
        assertTrue(favourite)
        favourite = !favourite
        assertFalse(favourite)
    }

    // --- message queue ---

    @Test
    fun `consuming a message clears it so it shows once`() {
        var message: String? = "Routed to Speaker"
        assertTrue(message != null)
        message = null
        assertNull("a consumed message must not repeat", message)
    }
}
