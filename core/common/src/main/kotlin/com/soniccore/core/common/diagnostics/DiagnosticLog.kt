package com.soniccore.core.common.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/** One recorded event. Kept small — thousands of these live in memory. */
data class DiagEvent(
    val timestampMs: Long,
    val level: DiagLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
) {
    fun format(): String {
        val time = TIME_FORMAT.format(Date(timestampMs))
        val base = "$time ${level.name.first()} $tag: $message"
        return if (throwable != null) "$base\n    ↳ $throwable" else base
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-memory diagnostic log.
 *
 * SonicCore wraps ~150 platform calls in `runCatching` because OEM audio stacks
 * routinely refuse operations that the public API claims to support (codec selection,
 * battery reporting, routing). Swallowing those failures keeps the app alive, but with
 * nothing recorded a bug report like "codec switching doesn't work on my phone" is
 * unactionable.
 *
 * This is a bounded ring buffer, not a file logger: no I/O on the audio path, no
 * unbounded growth, and the user can export it on demand. Nothing here is PII —
 * device names are the most identifying thing recorded, and export is user-initiated.
 */
@Singleton
class DiagnosticLog @Inject constructor() {

    private val buffer = ArrayDeque<DiagEvent>(CAPACITY)
    private val lock = Any()

    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    /** Counts by level, for a "3 warnings" badge without walking the buffer. */
    private val counts = mutableMapOf<DiagLevel, Int>()

    var mirrorToLogcat: Boolean = true

    fun d(tag: String, message: String) = record(DiagLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = record(DiagLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) =
        record(DiagLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) =
        record(DiagLevel.ERROR, tag, message, error)

    private fun record(level: DiagLevel, tag: String, message: String, error: Throwable?) {
        val event = DiagEvent(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = error?.let { "${it::class.java.simpleName}: ${it.message}" },
        )

        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(event)
            counts[level] = (counts[level] ?: 0) + 1
            _events.value = buffer.toList()
        }

        if (mirrorToLogcat) {
            when (level) {
                DiagLevel.DEBUG -> Log.d(LOG_TAG, "$tag: $message")
                DiagLevel.INFO -> Log.i(LOG_TAG, "$tag: $message")
                DiagLevel.WARN -> Log.w(LOG_TAG, "$tag: $message", error)
                DiagLevel.ERROR -> Log.e(LOG_TAG, "$tag: $message", error)
            }
        }
    }

    fun count(level: DiagLevel): Int = synchronized(lock) { counts[level] ?: 0 }

    fun snapshot(): List<DiagEvent> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            counts.clear()
            _events.value = emptyList()
        }
    }

    /** Plain-text export for a bug report. */
    fun export(): String = buildString {
        appendLine("SonicCore diagnostic log")
        appendLine("Android ${android.os.Build.VERSION.SDK_INT} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val snapshot = snapshot()
        appendLine("${snapshot.size} events (buffer holds $CAPACITY)")
        appendLine("errors=${count(DiagLevel.ERROR)} warnings=${count(DiagLevel.WARN)}")
        appendLine("-".repeat(60))
        snapshot.forEach { appendLine(it.format()) }
    }

    /**
     * Run [block], recording any failure instead of discarding it.
     *
     * Drop-in replacement for the bare `runCatching { ... }.getOrDefault(x)` pattern:
     *   platformCall("BluetoothCodec", default = null) { readCodecReflectively() }
     */
    inline fun <T> platformCall(
        tag: String,
        operation: String = "",
        default: T,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: Throwable) {
        w(tag, "platform call failed${if (operation.isEmpty()) "" else " ($operation)"}", error)
        default
    }

    /** Nullable variant. */
    inline fun <T> platformCallOrNull(
        tag: String,
        operation: String = "",
        block: () -> T,
    ): T? = try {
        block()
    } catch (error: Throwable) {
        w(tag, "platform call failed${if (operation.isEmpty()) "" else " ($operation)"}", error)
        null
    }

    companion object {
        /** ~4k events is a few MB at most and covers a long session. */
        const val CAPACITY = 4_000
        private const val LOG_TAG = "SonicCore"
    }
}
