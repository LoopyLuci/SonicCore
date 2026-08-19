package com.soniccore.core.common.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureSignalTest {

    @Test
    fun `empty signal has no failures`() {
        val signal = FailureSignal(DiagnosticLog().apply { mirrorToLogcat = false })
        signal.refresh()
        assertFalse(signal.summary.value.hasFailures)
    }

    @Test
    fun `categorises bluetooth failures`() {
        val log = DiagnosticLog().apply { mirrorToLogcat = false }
        log.w("BluetoothCodec", "LDAC unavailable")
        log.e("BluetoothBattery", "reflection failed")
        val signal = FailureSignal(log)
        signal.refresh()

        val summary = signal.summary.value
        assertEquals(2, summary.totalFailures)
        assertEquals(2, summary.byCategory["Bluetooth"])
    }

    @Test
    fun `categorises routing failures`() {
        val log = DiagnosticLog().apply { mirrorToLogcat = false }
        log.w("AudioRouting", "setCommunicationDevice refused")
        val signal = FailureSignal(log)
        signal.refresh()

        assertEquals(1, signal.summary.value.byCategory["Audio routing"])
    }

    @Test
    fun `report contains only aggregate counts and anonymised messages`() {
        val log = DiagnosticLog().apply { mirrorToLogcat = false }
        log.w("BluetoothCodec", "LDAC unavailable")
        log.e("AudioRouting", "setCommunicationDevice refused")
        val signal = FailureSignal(log)
        signal.refresh()

        val report = signal.buildReport()

        // Report contains counts, not raw device identifiers
        assertTrue(report.contains("failures across"))
        assertTrue(report.contains("Bluetooth"))
        assertTrue(report.contains("Audio routing"))
        // Recent message included
        assertTrue(report.contains("LDAC unavailable"))
        // No device model or user data
        assertFalse(report.contains("Build.MODEL"))
        assertFalse(report.contains("Manufacturer"))
    }

    @Test
    fun `disabling sharing clears summary`() {
        val log = DiagnosticLog().apply { mirrorToLogcat = false }
        log.w("BluetoothCodec", "LDAC unavailable")
        val signal = FailureSignal(log)
        signal.refresh()

        signal.sharingEnabled = true
        assertTrue(signal.summary.value.hasFailures)

        signal.sharingEnabled = false
        assertFalse(signal.summary.value.hasFailures)
    }

    @Test
    fun `info and debug events do not count as failures`() {
        val log = DiagnosticLog().apply { mirrorToLogcat = false }
        log.i("AudioDevice", "enumeration complete")
        log.d("DSP", "buffer processed")
        val signal = FailureSignal(log)
        signal.refresh()

        assertFalse(signal.summary.value.hasFailures)
    }
}
