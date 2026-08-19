package com.soniccore.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The diagnostic log is what makes ~150 `runCatching` sites debuggable. If it loses
 * events, grows without bound, or throws from a logging call, it is worse than nothing.
 */
class DiagnosticLogTest {

    private lateinit var log: DiagnosticLog

    @Before
    fun setUp() {
        log = DiagnosticLog()
        // Logcat is unavailable in a plain JVM test; mirroring would throw.
        log.mirrorToLogcat = false
    }

    @Test
    fun `records events in order`() {
        log.i("A", "first")
        log.w("B", "second")
        log.e("C", "third")

        val events = log.snapshot()
        assertEquals(3, events.size)
        assertEquals(listOf("first", "second", "third"), events.map { it.message })
        assertEquals(listOf("A", "B", "C"), events.map { it.tag })
    }

    @Test
    fun `counts by level`() {
        repeat(3) { log.w("tag", "warn $it") }
        repeat(2) { log.e("tag", "err $it") }
        log.i("tag", "info")

        assertEquals(3, log.count(DiagLevel.WARN))
        assertEquals(2, log.count(DiagLevel.ERROR))
        assertEquals(1, log.count(DiagLevel.INFO))
        assertEquals(0, log.count(DiagLevel.DEBUG))
    }

    @Test
    fun `ring buffer evicts oldest and never exceeds capacity`() {
        // Overfill by a known amount; the newest events must survive.
        repeat(DiagnosticLog.CAPACITY + 50) { log.i("tag", "event $it") }

        val events = log.snapshot()
        assertEquals(DiagnosticLog.CAPACITY, events.size)
        // event 0..49 evicted, so the first survivor is event 50.
        assertEquals("event 50", events.first().message)
        assertEquals("event ${DiagnosticLog.CAPACITY + 49}", events.last().message)
    }

    @Test
    fun `throwable is captured as type and message, not a full stack`() {
        log.e("tag", "boom", IllegalStateException("device gone"))

        val event = log.snapshot().single()
        assertEquals("IllegalStateException: device gone", event.throwable)
        // A full stack trace per event would blow the memory budget at 4k events.
        assertFalse(event.throwable!!.contains("\tat "))
    }

    @Test
    fun `events with no throwable leave the field null`() {
        log.i("tag", "fine")
        assertNull(log.snapshot().single().throwable)
    }

    @Test
    fun `platformCall returns the value on success without logging`() {
        val result = log.platformCall("BT", "readCodec", default = -1) { 42 }

        assertEquals(42, result)
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `platformCall returns the default and LOGS on failure`() {
        val result = log.platformCall<Int>("BT", "readCodec", default = -1) {
            throw UnsupportedOperationException("OEM refused")
        }

        assertEquals(-1, result)
        val event = log.snapshot().single()
        assertEquals(DiagLevel.WARN, event.level)
        assertEquals("BT", event.tag)
        assertTrue(event.message.contains("readCodec"))
        assertEquals("UnsupportedOperationException: OEM refused", event.throwable)
    }

    @Test
    fun `platformCallOrNull yields null and logs on failure`() {
        val result = log.platformCallOrNull<String>("USB", "descriptor") {
            error("no descriptor")
        }

        assertNull(result)
        assertEquals(DiagLevel.WARN, log.snapshot().single().level)
    }

    @Test
    fun `platformCall catches Throwable, not just Exception`() {
        // Reflective platform access can throw NoSuchMethodError on some OEM builds;
        // catching only Exception would let that kill the app.
        val result = log.platformCall("Reflect", "hiddenApi", default = "fallback") {
            throw NoSuchMethodError("getCodecConfig")
        }

        assertEquals("fallback", result)
        assertEquals("NoSuchMethodError: getCodecConfig", log.snapshot().single().throwable)
    }

    @Test
    fun `clear empties the buffer and the counts`() {
        log.w("tag", "warn")
        log.e("tag", "err")
        log.clear()

        assertEquals(0, log.snapshot().size)
        assertEquals(0, log.count(DiagLevel.WARN))
        assertEquals(0, log.count(DiagLevel.ERROR))
    }

    @Test
    fun `export includes device info and every event`() {
        log.w("Codec", "aptX unavailable")
        log.e("Route", "device died")

        val text = log.export()
        assertTrue(text.contains("SonicCore diagnostic log"))
        assertTrue(text.contains("aptX unavailable"))
        assertTrue(text.contains("device died"))
        assertTrue(text.contains("errors=1"))
        assertTrue(text.contains("warnings=1"))
    }

    @Test
    fun `format is stable and includes level initial and tag`() {
        val event = DiagEvent(
            timestampMs = 0L,
            level = DiagLevel.WARN,
            tag = "Bluetooth",
            message = "codec locked",
        )

        val line = event.format()
        assertTrue(line.contains("W Bluetooth: codec locked"))
    }

    @Test
    fun `concurrent writes do not lose events or corrupt the buffer`() {
        val threads = (0 until 8).map { t ->
            Thread { repeat(100) { log.i("t$t", "event $it") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 800 events, under capacity, so all must be present.
        assertEquals(800, log.snapshot().size)
    }
}
