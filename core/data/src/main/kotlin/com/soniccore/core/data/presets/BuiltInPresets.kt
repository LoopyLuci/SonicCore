package com.soniccore.core.data.presets

import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.CodecStrategy
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.audio.NoiseSuppressionMode
import com.soniccore.core.model.effects.BassBoostSettings
import com.soniccore.core.model.effects.CrossfeedSettings
import com.soniccore.core.model.effects.DynamicsSettings
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.effects.ReverbPreset
import com.soniccore.core.model.effects.ReverbSettings
import com.soniccore.core.model.effects.SpatialAudioSettings
import com.soniccore.core.model.effects.VirtualizerSettings
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqPreset
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.InputSettings
import com.soniccore.core.model.profile.OutputSettings
import com.soniccore.core.model.profile.ProfileIcon
import com.soniccore.core.model.profile.VolumeSettings

/**
 * Built-in EQ presets, expressed as gain offsets on the 10-band ISO grid.
 * Stored by frequency/gain/Q so they survive a change of EQ mode.
 */
object BuiltInEqPresets {

    private fun graphic(name: String, gains: List<Float>, description: String): EqPreset {
        val frequencies = EqSettings.ISO_10_BANDS
        val q = EqSettings.graphicQFor(EqMode.GRAPHIC_10)
        return EqPreset(
            id = "builtin_${name.lowercase().replace(' ', '_')}",
            name = name,
            description = description,
            isBuiltIn = true,
            settings = EqSettings(
                enabled = true,
                mode = EqMode.GRAPHIC_10,
                autoPreamp = true,
                bands = frequencies.mapIndexed { index, frequency ->
                    EqBand(
                        id = "band_$index",
                        type = FilterType.PEAK,
                        frequencyHz = frequency,
                        gainDb = gains.getOrElse(index) { 0f },
                        q = q,
                    )
                },
            ),
        )
    }

    val all: List<EqPreset> = listOf(
        graphic("Flat", List(10) { 0f }, "No colouration — the reference"),
        graphic("Bass Boost", listOf(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f), "Lifts sub and low bass"),
        graphic("Bass Reduce", listOf(-6f, -5f, -3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f), "Tames boomy rooms and buses"),
        graphic("Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 5.5f), "Adds air and detail"),
        graphic("Treble Reduce", listOf(0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -4f, -5f, -5f), "Softens harsh sibilance"),
        graphic("V-Shape", listOf(5f, 4f, 2f, 0f, -1.5f, -1f, 1f, 3f, 4.5f, 5f), "Fun, energetic, recessed mids"),
        graphic("Vocal", listOf(-2f, -1f, 0f, 2f, 4f, 4.5f, 3f, 1f, 0f, -1f), "Pushes voices forward"),
        graphic("Podcast", listOf(-6f, -4f, -1f, 2f, 4f, 4f, 2.5f, 0.5f, -1f, -2f), "Speech clarity, less rumble"),
        graphic("Rock", listOf(4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 3.5f, 3f), "Punchy drums and guitars"),
        graphic("Metal", listOf(4.5f, 3f, 0f, -1f, 1f, 2f, 3f, 4f, 4f, 3.5f), "Tight low end, cutting highs"),
        graphic("Jazz", listOf(3f, 2f, 1f, 1.5f, -0.5f, -0.5f, 0.5f, 1.5f, 2.5f, 3f), "Warm, natural, open"),
        graphic("Classical", listOf(3.5f, 2.5f, 1f, 0f, 0f, 0f, -0.5f, 1f, 2.5f, 3.5f), "Wide dynamics, gentle lift"),
        graphic("Electronic", listOf(5.5f, 4.5f, 1.5f, 0f, -1.5f, 1f, 1.5f, 3f, 4.5f, 5f), "Deep sub, crisp synths"),
        graphic("Hip-Hop", listOf(6f, 5f, 2.5f, 1f, -1f, -0.5f, 1f, 2f, 3f, 3f), "Heavy 808s, present vocals"),
        graphic("Acoustic", listOf(3f, 2.5f, 1.5f, 1f, 1.5f, 1.5f, 2f, 2.5f, 2f, 1.5f), "Intimate and airy"),
        graphic("Pop", listOf(-1f, 1f, 2.5f, 3.5f, 3f, 1.5f, 0f, -0.5f, 0.5f, 1.5f), "Polished, vocal-centred"),
        graphic("Gaming", listOf(4f, 2.5f, 0f, -1f, 0.5f, 2f, 3.5f, 4f, 3f, 2f), "Footsteps and positional cues"),
        graphic("Movie", listOf(4f, 3f, 1f, 1.5f, 2.5f, 2f, 1.5f, 2f, 2.5f, 2f), "Weighty effects, clear dialogue"),
        graphic("Night", listOf(-3f, -2f, 0f, 1.5f, 2.5f, 2.5f, 1.5f, 0f, -1.5f, -3f), "Quiet listening without losing detail"),
        graphic("Loudness", listOf(6f, 4.5f, 2f, 0f, -1f, 0f, 1.5f, 3.5f, 5f, 6f), "Equal-loudness compensation at low SPL"),
        graphic("Small Speaker", listOf(-8f, -5f, -1f, 2f, 3f, 2f, 1f, 1.5f, 2f, 1f), "Avoids distorting tiny drivers"),
        graphic("Car Audio", listOf(4f, 3f, 0f, -1.5f, -1f, 0.5f, 2f, 3f, 3.5f, 2.5f), "Compensates road noise"),
        graphic("Hearing Clarity", listOf(-2f, -1f, 0f, 2f, 4f, 5f, 6f, 5f, 3f, 1f), "Boosts speech band for hearing loss"),
    )
}

/** Built-in profiles — sensible, opinionated starting points the user can clone. */
object BuiltInProfiles {

    private fun profile(
        id: String,
        name: String,
        icon: ProfileIcon,
        color: Int,
        description: String,
        eqPresetName: String,
        volumePercent: Float,
        priority: Int = 0,
        effects: EffectsSettings = EffectsSettings(),
        output: OutputSettings = OutputSettings(),
        input: InputSettings = InputSettings(),
    ): AudioProfile {
        val eq = BuiltInEqPresets.all.firstOrNull { it.name == eqPresetName }?.settings
            ?: EqSettings.flat(EqMode.GRAPHIC_10)
        return AudioProfile(
            id = id,
            name = name,
            icon = icon,
            colorArgb = color,
            description = description,
            eq = eq,
            effects = effects,
            output = output,
            input = input,
            volume = VolumeSettings(
                streamPercents = mapOf(AudioStream.MUSIC to volumePercent),
                applyOnActivate = true,
            ),
            isBuiltIn = true,
            priority = priority,
        )
    }

    val all: List<AudioProfile> = listOf(
        profile(
            id = "builtin_music", name = "Music", icon = ProfileIcon.MUSIC,
            color = 0xFF4F8CFF.toInt(),
            description = "Reference playback with the best available codec",
            eqPresetName = "Flat", volumePercent = 0.6f, priority = 10,
            output = OutputSettings(codecStrategy = CodecStrategy.MAX_QUALITY, gaplessPlayback = true),
        ),
        profile(
            id = "builtin_gaming", name = "Gaming", icon = ProfileIcon.GAMING,
            color = 0xFF9B5DE5.toInt(),
            description = "Low latency, positional clarity, mic monitoring",
            eqPresetName = "Gaming", volumePercent = 0.7f, priority = 20,
            output = OutputSettings(codecStrategy = CodecStrategy.LOW_LATENCY, lowLatencyMode = true),
            input = InputSettings(
                micSource = MicSource.VOICE_COMMUNICATION,
                sidetoneEnabled = true,
                sidetoneLevel = 0.25f,
                noiseGateEnabled = true,
            ),
            effects = EffectsSettings(
                spatial = SpatialAudioSettings(enabled = true),
                virtualizer = VirtualizerSettings(enabled = true, strength = 0.7f),
            ),
        ),
        profile(
            id = "builtin_movie", name = "Movie", icon = ProfileIcon.MOVIE,
            color = 0xFFF15BB5.toInt(),
            description = "Virtual surround with dialogue lift and night mode ready",
            eqPresetName = "Movie", volumePercent = 0.65f, priority = 15,
            effects = EffectsSettings(
                virtualizer = VirtualizerSettings(enabled = true, strength = 0.8f),
                dynamics = DynamicsSettings(speechEnhancement = true),
                spatial = SpatialAudioSettings(enabled = true, passthroughAtmos = true),
            ),
        ),
        profile(
            id = "builtin_podcast", name = "Podcast", icon = ProfileIcon.PODCAST,
            color = 0xFF00BBF9.toInt(),
            description = "Speech-forward with rumble removed and levels evened out",
            eqPresetName = "Podcast", volumePercent = 0.55f, priority = 12,
            effects = EffectsSettings(
                dynamics = DynamicsSettings(compressorEnabled = true, ratio = 3f, speechEnhancement = true),
                channelMode = ChannelMode.STEREO,
            ),
        ),
        profile(
            id = "builtin_sleep", name = "Sleep", icon = ProfileIcon.SLEEP,
            color = 0xFF3A506B.toInt(),
            description = "Volume-limited, dynamics tamed, treble softened",
            eqPresetName = "Night", volumePercent = 0.25f, priority = 30,
            effects = EffectsSettings(
                dynamics = DynamicsSettings(compressorEnabled = true, nightMode = true, ratio = 4f),
                crossfeed = CrossfeedSettings(enabled = true, amount = 0.4f),
            ),
        ),
        profile(
            id = "builtin_workout", name = "Workout", icon = ProfileIcon.WORKOUT,
            color = 0xFFFF6B35.toInt(),
            description = "Driving bass with ambient awareness for safety",
            eqPresetName = "Electronic", volumePercent = 0.75f, priority = 18,
            output = OutputSettings(transparencyLevel = 0.5f, windNoiseReduction = true),
            effects = EffectsSettings(bassBoost = BassBoostSettings(enabled = true, strength = 0.5f)),
        ),
        profile(
            id = "builtin_call", name = "Call", icon = ProfileIcon.CALL,
            color = 0xFF06D6A0.toInt(),
            description = "Echo cancellation, noise suppression, sidetone on",
            eqPresetName = "Vocal", volumePercent = 0.6f, priority = 40,
            input = InputSettings(
                micSource = MicSource.VOICE_COMMUNICATION,
                echoCancellation = true,
                noiseSuppression = NoiseSuppressionMode.AGGRESSIVE,
                autoGainControl = true,
                sidetoneEnabled = true,
            ),
        ),
        profile(
            id = "builtin_recording", name = "Recording", icon = ProfileIcon.RECORDING,
            color = 0xFFEF476F.toInt(),
            description = "Flat unprocessed capture with gate, de-esser and monitoring",
            eqPresetName = "Flat", volumePercent = 0.5f, priority = 25,
            input = InputSettings(
                micSource = MicSource.UNPROCESSED,
                noiseSuppression = NoiseSuppressionMode.OFF,
                echoCancellation = false,
                autoGainControl = false,
                noiseGateEnabled = true,
                deEsserEnabled = true,
                compressorEnabled = true,
                sidetoneEnabled = true,
                preferredSampleRate = 48_000,
            ),
        ),
        profile(
            id = "builtin_navigation", name = "Navigation", icon = ProfileIcon.NAVIGATION,
            color = 0xFFFFD166.toInt(),
            description = "Mono-safe, speech enhanced, ducks music",
            eqPresetName = "Vocal", volumePercent = 0.7f, priority = 35,
            effects = EffectsSettings(
                dynamics = DynamicsSettings(speechEnhancement = true, compressorEnabled = true),
            ),
        ),
        profile(
            id = "builtin_accessibility", name = "Accessibility", icon = ProfileIcon.ACCESSIBILITY,
            color = 0xFF118AB2.toInt(),
            description = "Mono downmix, speech-band lift, balance control",
            eqPresetName = "Hearing Clarity", volumePercent = 0.7f, priority = 22,
            effects = EffectsSettings(
                channelMode = ChannelMode.MONO,
                dynamics = DynamicsSettings(compressorEnabled = true, ratio = 3f, speechEnhancement = true),
            ),
        ),
        profile(
            id = "builtin_car", name = "Car", icon = ProfileIcon.CAR,
            color = 0xFF6C757D.toInt(),
            description = "Road-noise compensation with safer top end",
            eqPresetName = "Car Audio", volumePercent = 0.7f, priority = 14,
            effects = EffectsSettings(dynamics = DynamicsSettings(compressorEnabled = true, ratio = 2.5f)),
        ),
        profile(
            id = "builtin_studio", name = "Studio", icon = ProfileIcon.WORK,
            color = 0xFF8D99AE.toInt(),
            description = "Flat reference with crossfeed for long headphone sessions",
            eqPresetName = "Flat", volumePercent = 0.55f, priority = 8,
            effects = EffectsSettings(
                crossfeed = CrossfeedSettings(enabled = true, amount = 0.25f, cutoffHz = 700f),
                reverb = ReverbSettings(enabled = false, preset = ReverbPreset.STUDIO),
            ),
        ),
    )
}
