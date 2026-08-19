package com.soniccore.feature.settings

import com.soniccore.core.common.diagnostics.DiagLevel
import com.soniccore.core.common.diagnostics.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Diagnostics screen is the one the README, CONTRIBUTING guide and bug-report
 * template all point users at. These assert the contract those docs promise.
 */
class DiagnosticSummaryTest {

    private fun log() = DiagnosticLog().apply { mirrorToLogcat = false }

    @Test
    fun `nothing recorded means nothing to report`() {
        val summary = DiagnosticSummary(total = 0, warnings = 0, errors = 0)
        assertFalse(summary.hasSomethingToReport)
    }

    @Test
    fun `info-only events are not worth reporting`() {
        // Info lines are routine bookkeeping; showing a "problem" badge for them would
        // train users to ignore the badge.
        val summary = DiagnosticSummary(total = 12, warnings = 0, errors = 0)
        assertFalse(summary.hasSomethingToReport)
    }

    @Test
    fun `a single warning is worth reporting`() {
        assertTrue(DiagnosticSummary(total = 1, warnings = 1).hasSomethingToReport)
    }

    @Test
    fun `an error is worth reporting`() {
        assertTrue(DiagnosticSummary(total = 1, errors = 1).hasSomethingToReport)
    }

    @Test
    fun `export contains what a bug report needs`() {
        val log = log()
        log.w("BluetoothCodec", "aptX unavailable on this device")
        log.e("Routing", "setCommunicationDevice refused")

        val text = log.export()

        // Device identity: without it a report is unactionable across OEMs.
        assertTrue(text.contains("Android"))
        // The actual failures.
        assertTrue(text.contains("aptX unavailable on this device"))
        assertTrue(text.contains("setCommunicationDevice refused"))
        // A summary the maintainer can triage at a glance.
        assertTrue(text.contains("errors=1"))
        assertTrue(text.contains("warnings=1"))
    }

    @Test
    fun `newest events surface first for the viewer`() {
        val log = log()
        log.i("tag", "oldest")
        log.i("tag", "newest")

        // The screen reverses the buffer so the most recent failure is visible without
        // scrolling — the opposite of what a raw append-order buffer gives you.
        val newestFirst = log.snapshot().asReversed()
        assertEquals("newest", newestFirst.first().message)
        assertEquals("oldest", newestFirst.last().message)
    }

    @Test
    fun `clearing discards everything so a stale log cannot be exported`() {
        val log = log()
        log.e("tag", "boom")
        log.clear()

        assertEquals(0, log.snapshot().size)
        assertEquals(0, log.count(DiagLevel.ERROR))
        assertFalse(log.export().contains("boom"))
    }
}
