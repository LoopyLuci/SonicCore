package com.soniccore.core.model.profile

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.CodecStrategy
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.audio.NoiseSuppressionMode
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqSettings
import kotlinx.serialization.Serializable

/** Per-stream target volumes a profile applies on activation. */
@Serializable
data class VolumeSettings(
    val streamPercents: Map<AudioStream, Float> = emptyMap(),
    val applyOnActivate: Boolean = true,
    val volumeLimitPercent: Float? = null,
    val fadeInMs: Int = 0,
    val lockVolume: Boolean = false,
    val volumeKeyTarget: AudioStream = AudioStream.MUSIC,
    val softwareGainDb: Float = 0f,
    val useSoftwareFineSteps: Boolean = false,
    val fineStepCount: Int = 100,
)

/** Output-side configuration. */
@Serializable
data class OutputSettings(
    val targetDeviceKey: String? = null,
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val balance: Float = 0f,
    val preferredSampleRate: Int? = null,
    val preferredEncodingName: String? = null,
    val lowLatencyMode: Boolean = false,
    val codecStrategy: CodecStrategy = CodecStrategy.AUTO,
    val preferredCodec: BluetoothCodec? = null,
    val codecBitrateKbps: Int? = null,
    val crossfadeSeconds: Float = 0f,
    val gaplessPlayback: Boolean = true,
    val ancLevel: Float? = null,
    val transparencyLevel: Float? = null,
    val windNoiseReduction: Boolean = false,
    val impedanceCompensationDb: Float = 0f,
)

/** Input-side configuration. */
@Serializable
data class InputSettings(
    val targetDeviceKey: String? = null,
    val micSource: MicSource = MicSource.MIC,
    val gainDb: Float = 0f,
    val autoGainControl: Boolean = false,
    val agcTargetDb: Float = -18f,
    val noiseSuppression: NoiseSuppressionMode = NoiseSuppressionMode.PLATFORM,
    val echoCancellation: Boolean = true,
    val windNoiseReduction: Boolean = false,
    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Float = -50f,
    val noiseGateAttackMs: Float = 5f,
    val noiseGateReleaseMs: Float = 150f,
    val sidetoneEnabled: Boolean = false,
    val sidetoneLevel: Float = 0.3f,
    val preferredSampleRate: Int? = null,
    val channelCount: Int = 1,
    val beamforming: Boolean = false,
    val micEq: EqSettings = EqSettings(),
    val compressorEnabled: Boolean = false,
    val deEsserEnabled: Boolean = false,
    val pushToTalk: Boolean = false,
)

/** Per-app override applied while that app holds audio focus. */
@Serializable
data class AppOverride(
    val packageName: String,
    val appLabel: String? = null,
    val volumePercent: Float? = null,
    val muted: Boolean = false,
    val eqSettings: EqSettings? = null,
    val effectsSettings: EffectsSettings? = null,
    val duckOthers: Boolean = false,
    val duckAmountDb: Float = -12f,
)

@Serializable
enum class ProfileIcon {
    MUSIC, GAMING, MOVIE, PODCAST, SLEEP, WORKOUT, CALL, RECORDING,
    NAVIGATION, ACCESSIBILITY, CAR, HOME, WORK, STUDY, PARTY, CUSTOM,
}

/**
 * A named bundle of every audio setting, bindable to a device and/or trigger.
 * Users may create unlimited profiles; [priority] resolves overlap.
 */
@Serializable
data class AudioProfile(
    val id: String,
    val name: String,
    val icon: ProfileIcon = ProfileIcon.CUSTOM,
    val colorArgb: Int = 0xFF4F8CFF.toInt(),
    val description: String? = null,
    val boundDeviceKeys: Set<String> = emptySet(),
    val volume: VolumeSettings = VolumeSettings(),
    val output: OutputSettings = OutputSettings(),
    val input: InputSettings = InputSettings(),
    val eq: EqSettings = EqSettings(),
    val effects: EffectsSettings = EffectsSettings(),
    val appOverrides: List<AppOverride> = emptyList(),
    val autoActivate: Boolean = true,
    val priority: Int = 0,
    val isBuiltIn: Boolean = false,
    val isActive: Boolean = false,
    val createdAtEpochMs: Long = 0L,
    val modifiedAtEpochMs: Long = 0L,
    val activationCount: Int = 0,
    val lastActivatedEpochMs: Long? = null,
)
