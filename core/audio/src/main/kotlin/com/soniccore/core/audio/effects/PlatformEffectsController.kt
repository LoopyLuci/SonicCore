package com.soniccore.core.audio.effects

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.effects.ReverbPreset
import com.soniccore.core.model.eq.EqSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to the platform `android.media.audiofx` effects.
 *
 * These are the only effects that can influence audio played by *other* apps: they
 * attach to a shared audio session (session 0 = global output mix). Session 0 is
 * honoured by most OEMs but silently ignored by some — every attach is wrapped and
 * [lastError] tells the UI when the platform refused, so we never claim an effect
 * is active when it isn't.
 *
 * Our own in-process [com.soniccore.core.dsp.EqualizerEngine] handles the response
 * curve and anything we render ourselves; this class handles system-wide reach.
 */
@Singleton
class PlatformEffectsController @Inject constructor() {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    private var sessionId: Int = GLOBAL_SESSION
    var lastError: String? = null
        private set

    val isAttached: Boolean get() = equalizer != null

    /** Number of bands the platform equalizer exposes (typically 5). */
    var platformBandCount: Int = 0
        private set

    /** Platform band centre frequencies in Hz. */
    var platformBandFrequencies: List<Int> = emptyList()
        private set

    /** Platform gain range in millibels, e.g. -1500..1500. */
    var platformGainRangeMb: IntRange = 0..0
        private set

    @Synchronized
    fun attach(sessionId: Int = GLOBAL_SESSION): Boolean {
        release()
        this.sessionId = sessionId
        lastError = null

        val ok = runCatching {
            equalizer = Equalizer(EFFECT_PRIORITY, sessionId).also { eq ->
                platformBandCount = eq.numberOfBands.toInt()
                platformBandFrequencies = (0 until platformBandCount).map { band ->
                    eq.getCenterFreq(band.toShort()) / 1000 // milliHz -> Hz
                }
                val range = eq.bandLevelRange
                platformGainRangeMb = range[0].toInt()..range[1].toInt()
            }
            true
        }.getOrElse { error ->
            lastError = "Platform equalizer unavailable: ${error.message}"
            Log.w(TAG, "Equalizer attach failed", error)
            false
        }

        runCatching { bassBoost = BassBoost(EFFECT_PRIORITY, sessionId) }
        runCatching { virtualizer = Virtualizer(EFFECT_PRIORITY, sessionId) }
        runCatching { presetReverb = PresetReverb(EFFECT_PRIORITY, sessionId) }
        runCatching { loudnessEnhancer = LoudnessEnhancer(sessionId) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dynamicsProcessing = DynamicsProcessing(
                    EFFECT_PRIORITY,
                    sessionId,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        CHANNEL_COUNT,
                        /* preEqInUse = */ true, PRE_EQ_BANDS,
                        /* mbcInUse = */ true, MBC_BANDS,
                        /* postEqInUse = */ true, POST_EQ_BANDS,
                        /* limiterInUse = */ true,
                    ).build(),
                )
            }
        }

        return ok
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { presetReverb?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { dynamicsProcessing?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        presetReverb = null
        loudnessEnhancer = null
        dynamicsProcessing = null
        platformBandCount = 0
        platformBandFrequencies = emptyList()
    }

    /**
     * Project our arbitrary-band EQ curve onto the platform's fixed bands by
     * sampling the desired response at each platform centre frequency.
     */
    @Synchronized
    fun applyEqualizer(settings: EqSettings, curveAt: (Float) -> Float): Boolean {
        val eq = equalizer ?: return false
        return runCatching {
            eq.enabled = settings.enabled
            if (!settings.enabled) return@runCatching true
            platformBandFrequencies.forEachIndexed { index, frequency ->
                val desiredDb = curveAt(frequency.toFloat())
                val millibels = (desiredDb * 100f).toInt()
                    .coerceIn(platformGainRangeMb.first, platformGainRangeMb.last)
                eq.setBandLevel(index.toShort(), millibels.toShort())
            }
            true
        }.getOrElse { error ->
            lastError = "Could not apply equalizer: ${error.message}"
            false
        }
    }

    @Synchronized
    fun applyEffects(settings: EffectsSettings): Boolean {
        var allApplied = true

        bassBoost?.let { effect ->
            runCatching {
                effect.enabled = settings.bassBoost.enabled
                if (settings.bassBoost.enabled) {
                    effect.setStrength((settings.bassBoost.strength * 1000).toInt().coerceIn(0, 1000).toShort())
                }
            }.onFailure { allApplied = false }
        }

        virtualizer?.let { effect ->
            runCatching {
                effect.enabled = settings.virtualizer.enabled
                if (settings.virtualizer.enabled) {
                    effect.setStrength((settings.virtualizer.strength * 1000).toInt().coerceIn(0, 1000).toShort())
                }
            }.onFailure { allApplied = false }
        }

        presetReverb?.let { effect ->
            runCatching {
                effect.enabled = settings.reverb.enabled
                effect.preset = mapReverbPreset(settings.reverb.preset)
            }.onFailure { allApplied = false }
        }

        loudnessEnhancer?.let { effect ->
            runCatching {
                effect.enabled = settings.loudness.enabled
                if (settings.loudness.enabled) {
                    effect.setTargetGain(settings.loudness.targetGainMb.coerceIn(0, 2000))
                }
            }.onFailure { allApplied = false }
        }

        if (!allApplied) lastError = "Some effects were rejected by the platform"
        return allApplied
    }

    private fun mapReverbPreset(preset: ReverbPreset): Short = when (preset) {
        ReverbPreset.NONE -> PresetReverb.PRESET_NONE
        ReverbPreset.SMALL_ROOM -> PresetReverb.PRESET_SMALLROOM
        ReverbPreset.MEDIUM_ROOM -> PresetReverb.PRESET_MEDIUMROOM
        ReverbPreset.LARGE_ROOM -> PresetReverb.PRESET_LARGEROOM
        ReverbPreset.MEDIUM_HALL -> PresetReverb.PRESET_MEDIUMHALL
        ReverbPreset.LARGE_HALL, ReverbPreset.CATHEDRAL -> PresetReverb.PRESET_LARGEHALL
        ReverbPreset.PLATE, ReverbPreset.STUDIO -> PresetReverb.PRESET_PLATE
    }

    /** Whether the platform advertises support for a given effect type at all. */
    fun availableEffects(): Set<String> = runCatching {
        AudioEffect.queryEffects()?.map { it.name }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    fun supportsGlobalSession(): Boolean = isAttached && sessionId == GLOBAL_SESSION

    companion object {
        private const val TAG = "PlatformEffects"

        /** Session 0 targets the global output mix — how EQ apps reach other apps. */
        const val GLOBAL_SESSION = 0
        private const val EFFECT_PRIORITY = 1000
        private const val CHANNEL_COUNT = 2
        private const val PRE_EQ_BANDS = 6
        private const val MBC_BANDS = 6
        private const val POST_EQ_BANDS = 6
    }
}
