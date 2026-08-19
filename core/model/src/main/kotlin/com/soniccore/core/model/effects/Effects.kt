package com.soniccore.core.model.effects

import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.ReplayGainMode
import kotlinx.serialization.Serializable

@Serializable
data class BassBoostSettings(
    val enabled: Boolean = false,
    val strength: Float = 0f,
    val cutoffHz: Float = 120f,
)

@Serializable
data class VirtualizerSettings(
    val enabled: Boolean = false,
    val strength: Float = 0f,
    val mode: VirtualizerMode = VirtualizerMode.AUTO,
)

@Serializable
enum class VirtualizerMode(val displayName: String) {
    AUTO("Automatic"),
    BINAURAL("Binaural (headphones)"),
    TRANSAURAL("Transaural (speakers)"),
}

@Serializable
enum class ReverbPreset(val displayName: String) {
    NONE("None"),
    SMALL_ROOM("Small room"),
    MEDIUM_ROOM("Medium room"),
    LARGE_ROOM("Large room"),
    MEDIUM_HALL("Medium hall"),
    LARGE_HALL("Large hall"),
    PLATE("Plate"),
    CATHEDRAL("Cathedral"),
    STUDIO("Studio"),
}

@Serializable
data class ReverbSettings(
    val enabled: Boolean = false,
    val preset: ReverbPreset = ReverbPreset.NONE,
    val wetMix: Float = 0.2f,
    val decaySeconds: Float = 1.5f,
    val preDelayMs: Float = 20f,
    val dampingHz: Float = 6000f,
)

/** Headphone crossfeed — blends channels to reduce super-stereo fatigue. */
@Serializable
data class CrossfeedSettings(
    val enabled: Boolean = false,
    val amount: Float = 0.3f,
    val cutoffHz: Float = 700f,
    val delayMicros: Float = 300f,
)

@Serializable
data class SpatialAudioSettings(
    val enabled: Boolean = false,
    val headTracking: Boolean = false,
    val roomSize: Float = 0.5f,
    val speakerDistanceMeters: Float = 2f,
    val elevationDegrees: Float = 0f,
    val hrtfProfile: HrtfProfile = HrtfProfile.GENERIC,
    val passthroughAtmos: Boolean = true,
)

@Serializable
enum class HrtfProfile(val displayName: String) {
    GENERIC("Generic"),
    SMALL_HEAD("Small head"),
    LARGE_HEAD("Large head"),
    CUSTOM("Custom measurement"),
}

/** Dynamic range compression / limiting. */
@Serializable
data class DynamicsSettings(
    val compressorEnabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 2f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 120f,
    val makeupGainDb: Float = 0f,
    val kneeDb: Float = 6f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
    val nightMode: Boolean = false,
    val speechEnhancement: Boolean = false,
)

@Serializable
data class LoudnessSettings(
    val enabled: Boolean = false,
    val targetGainMb: Int = 0,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreampDb: Float = 0f,
    val preventClipping: Boolean = true,
)

@Serializable
enum class DitheringMode(val displayName: String) {
    OFF("Off"),
    RECTANGULAR("Rectangular"),
    TRIANGULAR("Triangular (TPDF)"),
    SHAPED("Noise-shaped"),
}

/** Complete output-side effect chain. */
@Serializable
data class EffectsSettings(
    val bassBoost: BassBoostSettings = BassBoostSettings(),
    val virtualizer: VirtualizerSettings = VirtualizerSettings(),
    val reverb: ReverbSettings = ReverbSettings(),
    val crossfeed: CrossfeedSettings = CrossfeedSettings(),
    val spatial: SpatialAudioSettings = SpatialAudioSettings(),
    val dynamics: DynamicsSettings = DynamicsSettings(),
    val loudness: LoudnessSettings = LoudnessSettings(),
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val balance: Float = 0f,
    val stereoWidth: Float = 1f,
    val dithering: DitheringMode = DitheringMode.OFF,
    val phaseInvertLeft: Boolean = false,
    val phaseInvertRight: Boolean = false,
    val pitchSemitones: Float = 0f,
    val tempoRatio: Float = 1f,
)
