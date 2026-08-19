package com.soniccore.core.data.engine

import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.audio.effects.PlatformEffectsController
import com.soniccore.core.audio.routing.AudioRouter
import com.soniccore.core.audio.volume.VolumeController
import com.soniccore.core.data.repository.DeviceRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.dsp.EqualizerEngine
import com.soniccore.core.model.audio.RoutingResult
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.profile.AudioProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Per-step outcome so the UI can show exactly what applied and what didn't. */
data class ProfileApplyReport(
    val profileId: String,
    val profileName: String,
    val volumeApplied: Boolean,
    val volumeFailures: List<String> = emptyList(),
    val equalizerApplied: Boolean = false,
    val equalizerNote: String? = null,
    val effectsApplied: Boolean = false,
    val effectsNote: String? = null,
    val routingResult: RoutingResult? = null,
    val warnings: List<String> = emptyList(),
) {
    val fullySucceeded: Boolean
        get() = volumeFailures.isEmpty() &&
            warnings.isEmpty() &&
            (routingResult == null || routingResult is RoutingResult.Success)
}

/**
 * Applies a profile across every subsystem: volume, EQ (platform + in-process),
 * effects, and routing.
 *
 * Every step reports success independently — a profile that half-applies because
 * the OEM refused a codec change must be visible to the user, not swallowed.
 */
@Singleton
class ProfileEngine @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val volumeController: VolumeController,
    private val audioRouter: AudioRouter,
    private val platformEffects: PlatformEffectsController,
    private val deviceRegistry: AudioDeviceRegistry,
    private val settingsStore: SettingsStore,
) {
    /** In-process engine used for curve computation and our own playback path. */
    val dspEqualizer = EqualizerEngine()

    private val applyMutex = Mutex()

    private val _lastReport = MutableStateFlow<ProfileApplyReport?>(null)
    val lastReport: StateFlow<ProfileApplyReport?> = _lastReport.asStateFlow()

    private val _activeProfile = MutableStateFlow<AudioProfile?>(null)
    val activeProfile: StateFlow<AudioProfile?> = _activeProfile.asStateFlow()

    suspend fun apply(profile: AudioProfile, targetDevice: AudioDevice? = null): ProfileApplyReport =
        applyMutex.withLock {
            val warnings = mutableListOf<String>()
            val volumeFailures = mutableListOf<String>()

            // 1. Volume, per stream.
            var volumeApplied = false
            if (profile.volume.applyOnActivate) {
                profile.volume.streamPercents.forEach { (stream, percent) ->
                    val limited = profile.volume.volumeLimitPercent
                        ?.let { minOf(percent, it) } ?: percent
                    val ok = volumeController.setPercent(stream, limited)
                    if (ok) {
                        volumeApplied = true
                    } else {
                        val reason = if (volumeController.requiresNotificationPolicyAccess(stream)) {
                            "${stream.displayName}: blocked by Do Not Disturb — grant notification policy access"
                        } else {
                            "${stream.displayName}: the system rejected the change"
                        }
                        volumeFailures += reason
                    }
                }
            }

            // 2. Equalizer — configure our engine first so the curve is authoritative.
            dspEqualizer.configure(profile.eq)
            var equalizerApplied = false
            var equalizerNote: String? = null

            if (profile.eq.enabled) {
                if (!platformEffects.isAttached) {
                    platformEffects.attach(PlatformEffectsController.GLOBAL_SESSION)
                }
                if (platformEffects.isAttached) {
                    val curve = buildCurveSampler(profile.eq)
                    equalizerApplied = platformEffects.applyEqualizer(profile.eq, curve)
                    if (!equalizerApplied) {
                        equalizerNote = platformEffects.lastError
                            ?: "The platform equalizer rejected these settings"
                        warnings += equalizerNote
                    } else if (platformEffects.platformBandCount in 1..5) {
                        equalizerNote =
                            "System-wide EQ uses ${platformEffects.platformBandCount} hardware bands; " +
                                "the full curve applies to audio played by SonicCore."
                    }
                } else {
                    equalizerNote = platformEffects.lastError
                        ?: "This device does not expose a system-wide equalizer"
                    warnings += equalizerNote
                }
            }

            // 3. Effects.
            var effectsApplied = false
            var effectsNote: String? = null
            if (platformEffects.isAttached) {
                effectsApplied = platformEffects.applyEffects(profile.effects)
                if (!effectsApplied) {
                    effectsNote = platformEffects.lastError
                    effectsNote?.let { warnings += it }
                }
            }

            // 4. Routing, only when the profile names a target.
            val routingTarget = targetDevice
                ?: profile.output.targetDeviceKey?.let { key ->
                    deviceRegistry.snapshotPlatformDevices().firstOrNull { it.stableKey == key }
                }
            val routingResult = routingTarget?.let { audioRouter.routeCommunicationTo(it) }
            if (routingResult is RoutingResult.Unsupported) warnings += routingResult.reason
            if (routingResult is RoutingResult.Failed) warnings += routingResult.reason

            // 5. Persist activation.
            profileRepository.activate(profile.id)
            settingsStore.setActiveProfileId(profile.id)
            routingTarget?.let { deviceRepository.recordConnection(it.stableKey) }

            _activeProfile.value = profile.copy(isActive = true)

            ProfileApplyReport(
                profileId = profile.id,
                profileName = profile.name,
                volumeApplied = volumeApplied,
                volumeFailures = volumeFailures,
                equalizerApplied = equalizerApplied,
                equalizerNote = equalizerNote,
                effectsApplied = effectsApplied,
                effectsNote = effectsNote,
                routingResult = routingResult,
                warnings = warnings,
            ).also { _lastReport.value = it }
        }

    /** Re-apply just the EQ, for live editing without a full profile switch. */
    suspend fun applyEqualizerOnly(settings: EqSettings): Boolean = applyMutex.withLock {
        dspEqualizer.configure(settings)
        if (!settings.enabled) {
            platformEffects.applyEqualizer(settings) { 0f }
            return@withLock true
        }
        if (!platformEffects.isAttached) {
            platformEffects.attach(PlatformEffectsController.GLOBAL_SESSION)
        }
        platformEffects.applyEqualizer(settings, buildCurveSampler(settings))
    }

    /**
     * Samples the desired response at an arbitrary frequency so the platform's
     * fixed bands can be driven from our arbitrary-band curve.
     */
    private fun buildCurveSampler(settings: EqSettings): (Float) -> Float {
        val engine = EqualizerEngine().apply { configure(settings) }
        val response = engine.frequencyResponse(pointCount = 256)
        if (response.isEmpty()) return { 0f }
        return { frequency ->
            val nearest = response.minByOrNull { kotlin.math.abs(it.frequencyHz - frequency) }
            nearest?.magnitudeDb ?: 0f
        }
    }

    /** Auto-activate the profile bound to a newly connected device. */
    suspend fun onDeviceConnected(device: AudioDevice): ProfileApplyReport? {
        deviceRepository.remember(device)
        deviceRepository.recordConnection(device.stableKey)
        val profile = profileRepository.findForDevice(device.stableKey) ?: return null
        return apply(profile, targetDevice = device)
    }

    suspend fun deactivate() = applyMutex.withLock {
        profileRepository.deactivateAll()
        settingsStore.setActiveProfileId(null)
        platformEffects.release()
        _activeProfile.value = null
        _lastReport.value = null
    }

    fun releaseEffects() = platformEffects.release()
}
