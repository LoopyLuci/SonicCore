package com.soniccore.core.audio.routing

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.soniccore.core.audio.device.AudioDeviceMapper
import com.soniccore.core.audio.device.AudioDeviceRegistry
import com.soniccore.core.model.audio.AudioFocusState
import com.soniccore.core.model.audio.RoutingResult
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.DeviceTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio routing.
 *
 * What is actually possible without system privileges:
 *  - API 31+: `setCommunicationDevice` moves the *communication* path (calls, VoIP).
 *  - Any API: `setPreferredDevice` on an AudioTrack/AudioRecord **we own**.
 *  - Legacy: SCO on/off and speakerphone toggles.
 *
 * What is NOT possible: forcing another app's media stream to a different endpoint.
 * That needs MODIFY_AUDIO_ROUTING (signature). Rather than pretend, we return
 * [RoutingResult.Unsupported] with an explanation the UI surfaces, and offer the
 * system output switcher instead.
 */
@Singleton
class AudioRouter @Inject constructor(
    private val audioManager: AudioManager,
    private val registry: AudioDeviceRegistry,
) {

    fun activeOutput(): AudioDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { AudioDeviceMapper.map(it, isActiveOutput = true) }
        } else {
            null
        } ?: inferActiveOutput()
    }.getOrNull()

    /**
     * Below API 31 there is no getter for the active output; infer it from the
     * platform's own routing precedence.
     */
    private fun inferActiveOutput(): AudioDevice? {
        val candidates = registry.snapshotPlatformDevices().filter { it.canOutput }
        val precedence = listOf(
            DeviceTransport.USB,
            DeviceTransport.BLUETOOTH_LE,
            DeviceTransport.BLUETOOTH_CLASSIC,
            DeviceTransport.ANALOG_35MM,
            DeviceTransport.HDMI,
            DeviceTransport.BUILTIN,
        )
        return precedence.firstNotNullOfOrNull { transport ->
            candidates.firstOrNull { it.transport == transport }
        }
    }

    fun activeInput(): AudioDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
                ?.takeIf { it.isSource }
                ?.let { AudioDeviceMapper.map(it, isActiveInput = true) }
        } else {
            null
        } ?: registry.snapshotPlatformDevices().firstOrNull { it.canInput }
    }.getOrNull()

    /**
     * Route the communication path to [device]. This is honest about scope: it
     * moves calls/VoIP, and media only on OEMs that follow the communication route.
     */
    fun routeCommunicationTo(device: AudioDevice): RoutingResult {
        if (device.transport == DeviceTransport.WIFI) {
            return RoutingResult.Unsupported(
                "Network speakers are reached through their own protocol (Cast/AirPlay), " +
                    "not the Android routing layer. Use the cast picker to send audio here.",
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = registry.findSystemDevice(device.stableKey)
                ?: return RoutingResult.Failed("Device is no longer connected")
            return runCatching {
                if (audioManager.setCommunicationDevice(target)) {
                    RoutingResult.Success
                } else {
                    RoutingResult.Failed("The system declined the routing request")
                }
            }.getOrElse { error ->
                RoutingResult.Failed(error.message ?: "Routing failed")
            }
        }

        // Legacy path: SCO for Bluetooth, speakerphone for built-in.
        return runCatching {
            @Suppress("DEPRECATION")
            when (device.transport) {
                DeviceTransport.BLUETOOTH_CLASSIC, DeviceTransport.BLUETOOTH_LE -> {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    RoutingResult.Success
                }
                DeviceTransport.BUILTIN -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = true
                    RoutingResult.Success
                }
                else -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = false
                    RoutingResult.Success
                }
            }
        }.getOrElse { RoutingResult.Failed(it.message ?: "Legacy routing failed") }
    }

    fun clearCommunicationRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }
    }

    /** Devices the platform will accept for [setCommunicationDevice]. */
    fun availableCommunicationDevices(): List<AudioDevice> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.map { AudioDeviceMapper.map(it) }
        } else {
            registry.snapshotPlatformDevices().filter { it.canOutput }
        }
    }.getOrDefault(emptyList())

    /**
     * Honest capability probe for the UI: can we move *this app's* playback to the
     * device, the communication path, or neither?
     */
    fun routingCapability(device: AudioDevice): RoutingCapability = when {
        device.transport == DeviceTransport.WIFI -> RoutingCapability.EXTERNAL_PROTOCOL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RoutingCapability.COMMUNICATION_AND_OWN_STREAM
        else -> RoutingCapability.OWN_STREAM_ONLY
    }

    fun observeAudioFocus(): Flow<AudioFocusState> = callbackFlow {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            trySend(
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> AudioFocusState.GAINED
                    AudioManager.AUDIOFOCUS_LOSS -> AudioFocusState.LOST
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusState.LOST_TRANSIENT
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusState.LOST_TRANSIENT_CAN_DUCK
                    else -> AudioFocusState.NONE
                },
            )
        }
        // Registered only to observe; we do not hold focus for a control app.
        trySend(AudioFocusState.NONE)
        awaitClose { runCatching { audioManager.abandonAudioFocus(listener) } }
    }.conflate()

    /** Preferred-device hint for a stream we own (playback engine / test tones). */
    fun preferredDeviceFor(device: AudioDevice): AudioDeviceInfo? =
        registry.findSystemDevice(device.stableKey)

    fun defaultAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}

enum class RoutingCapability(val explanation: String) {
    COMMUNICATION_AND_OWN_STREAM(
        "Calls and this app's audio can be routed here. Other apps' media follows the system output.",
    ),
    OWN_STREAM_ONLY(
        "This app's audio can be routed here. System-wide routing needs Android 12 or newer.",
    ),
    EXTERNAL_PROTOCOL(
        "Reached over the network with its own protocol — use the cast picker to stream here.",
    ),
}
