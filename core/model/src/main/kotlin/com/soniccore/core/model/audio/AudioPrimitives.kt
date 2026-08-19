package com.soniccore.core.model.audio

import kotlinx.serialization.Serializable

/**
 * Android stream types we expose. Mirrors AudioManager.STREAM_* so the data
 * layer can map 1:1 without leaking platform constants into the domain.
 */
@Serializable
enum class AudioStream(val displayName: String) {
    MUSIC("Media"),
    VOICE_CALL("Call"),
    RING("Ringtone"),
    NOTIFICATION("Notifications"),
    ALARM("Alarm"),
    SYSTEM("System"),
    DTMF("Dial pad"),
    ACCESSIBILITY("Accessibility"),
}

/**
 * Volume for one stream. [index] is the raw platform step and [maxIndex] the
 * device-reported ceiling — never assume 15.
 */
@Serializable
data class StreamVolume(
    val stream: AudioStream,
    val index: Int,
    val minIndex: Int,
    val maxIndex: Int,
    val isMuted: Boolean,
    val isFixed: Boolean = false,
) {
    val percent: Float
        get() {
            val span = (maxIndex - minIndex).coerceAtLeast(1)
            return ((index - minIndex).toFloat() / span).coerceIn(0f, 1f)
        }

    val stepCount: Int get() = (maxIndex - minIndex).coerceAtLeast(1)

    fun indexForPercent(target: Float): Int {
        val span = (maxIndex - minIndex).coerceAtLeast(1)
        return (minIndex + Math.round(target.coerceIn(0f, 1f) * span)).coerceIn(minIndex, maxIndex)
    }

    companion object {
        fun empty(stream: AudioStream) = StreamVolume(stream, 0, 0, 15, false)
    }
}

/** Result of a routing attempt — routing legitimately fails and the UI must say so. */
sealed interface RoutingResult {
    data object Success : RoutingResult
    data class Unsupported(val reason: String) : RoutingResult
    data class PermissionRequired(val permission: String) : RoutingResult
    data class Failed(val reason: String) : RoutingResult
}

@Serializable
enum class AudioFocusState { GAINED, LOST, LOST_TRANSIENT, LOST_TRANSIENT_CAN_DUCK, NONE }

/** Channel presentation for output. */
@Serializable
enum class ChannelMode(val displayName: String) {
    STEREO("Stereo"),
    MONO("Mono"),
    SWAP_LR("Swap L/R"),
    LEFT_ONLY("Left only"),
    RIGHT_ONLY("Right only"),
    VIRTUAL_51("Virtual 5.1"),
    VIRTUAL_71("Virtual 7.1"),
}

/** ReplayGain / loudness normalization behaviour. */
@Serializable
enum class ReplayGainMode(val displayName: String) {
    OFF("Off"),
    TRACK("Per track"),
    ALBUM("Per album"),
    SMART("Smart (album, fallback track)"),
}

/** Android AudioRecord source — the source *is* the processing switch. */
@Serializable
enum class MicSource(val displayName: String, val description: String) {
    MIC("Default", "Standard microphone path"),
    VOICE_RECOGNITION("Voice recognition", "No AGC or noise suppression"),
    VOICE_COMMUNICATION("Voice call", "Echo cancellation + noise suppression + AGC"),
    UNPROCESSED("Unprocessed", "Flattest response, no platform DSP"),
    CAMCORDER("Camcorder", "Tuned for video recording"),
    VOICE_PERFORMANCE("Performance", "Low-latency live monitoring"),
}

@Serializable
enum class NoiseSuppressionMode(val displayName: String) {
    OFF("Off"),
    PLATFORM("Platform"),
    AGGRESSIVE("Aggressive"),
    VOICE_ISOLATION("Voice isolation"),
}

/** Bluetooth codec preference strategy. */
@Serializable
enum class CodecStrategy(val displayName: String) {
    AUTO("Automatic"),
    MAX_QUALITY("Highest quality"),
    BALANCED("Balanced"),
    LOW_LATENCY("Lowest latency"),
    BATTERY_SAVER("Battery saver"),
    MANUAL("Manual"),
}
