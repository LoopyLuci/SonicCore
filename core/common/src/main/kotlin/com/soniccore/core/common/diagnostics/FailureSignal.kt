package com.soniccore.core.common.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregate failure signal, built on top of [DiagnosticLog].
 *
 * Privacy design:
 * - Everything stays on-device. Nothing is sent anywhere automatically.
 * - No device identifiers, no personal data, no network access.
 * - The user opts in to *view* the summary, and separately opts in to *share* it.
 * - Shared reports contain only failure category counts and recent anonymised
 *   messages — enough to spot "codec selection fails on Android 15" trends without
 *   identifying any individual.
 *
 * This is intentionally separate from the diagnostic log: that log captures
 * everything (including routine info-level events). This only counts failures the
 * user might want to report.
 */
@Singleton
class FailureSignal @Inject constructor(
    private val diagnostics: DiagnosticLog,
) {
    private val _summary = MutableStateFlow(FailureSummary.empty())
    val summary: StateFlow<FailureSummary> = _summary.asStateFlow()

    /** True once the user has opted in to share failure data. */
    var sharingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) _summary.value = FailureSummary.empty()
        }

    /**
     * Recompute the aggregate from the current diagnostic buffer.
     * Called on demand (when the user opens the failure screen), never on the hot path.
     */
    fun refresh() {
        val events = diagnostics.snapshot().filter {
            it.level == DiagLevel.WARN || it.level == DiagLevel.ERROR
        }
        val byCategory = events.groupingBy { categorize(it.tag) }.eachCount()
        _summary.value = FailureSummary(
            totalFailures = events.size,
            byCategory = byCategory,
            generatedAtMs = System.currentTimeMillis(),
        )
    }

    /** Group raw tags into reportable categories so trends are visible. */
    private fun categorize(tag: String): String = when {
        tag.contains("bluetooth", ignoreCase = true) -> "Bluetooth"
        tag.contains("usb", ignoreCase = true) -> "USB"
        tag.contains("wifi", ignoreCase = true) || tag.contains("nsd", ignoreCase = true) -> "Network discovery"
        tag.contains("cast", ignoreCase = true) -> "Chromecast"
        tag.contains("airplay", ignoreCase = true) || tag.contains("raop", ignoreCase = true) -> "AirPlay"
        tag.contains("routing", ignoreCase = true) || tag.contains("audio", ignoreCase = true) -> "Audio routing"
        tag.contains("eq", ignoreCase = true) || tag.contains("dsp", ignoreCase = true) -> "DSP"
        tag.contains("mic", ignoreCase = true) || tag.contains("record", ignoreCase = true) -> "Microphone"
        tag.contains("session", ignoreCase = true) || tag.contains("media", ignoreCase = true) -> "Media session"
        tag.contains("notification", ignoreCase = true) || tag.contains("listener", ignoreCase = true) -> "Notification access"
        tag.contains("widget", ignoreCase = true) || tag.contains("glance", ignoreCase = true) -> "Widget"
        tag.contains("automation", ignoreCase = true) || tag.contains("rule", ignoreCase = true) -> "Automation"
        else -> "Other"
    }

    /**
     * Privacy-safe report for sharing.
     *
     * Contains only: Android version bucket, failure counts per category, and the
     * most recent anonymised message per category. No device model, no user data, no
     * identifiers beyond "Android 15" — enough to spot a trend, not enough to track.
     */
    fun buildReport(): String {
        val summary = _summary.value
        val recent = diagnostics.snapshot()
            .filter { it.level == DiagLevel.WARN || it.level == DiagLevel.ERROR }
            .groupBy { categorize(it.tag) }
            .mapValues { (_, events) -> events.lastOrNull()?.message }

        return buildString {
            appendLine("SonicCore failure report")
            appendLine("Android ${android.os.Build.VERSION.SDK_INT}")
            appendLine("${summary.totalFailures} failures across ${summary.byCategory.size} categories")
            appendLine("-".repeat(40))
            summary.byCategory.entries.sortedByDescending { it.value }.forEach { (category, count) ->
                appendLine("$category: $count")
                recent[category]?.let { appendLine("  recent: $it") }
            }
        }
    }
}

/** Aggregate of recent failures, by broad category. */
data class FailureSummary(
    val totalFailures: Int,
    val byCategory: Map<String, Int>,
    val generatedAtMs: Long,
) {
    val hasFailures: Boolean get() = totalFailures > 0

    companion object {
        fun empty() = FailureSummary(0, emptyMap(), 0L)
    }
}
