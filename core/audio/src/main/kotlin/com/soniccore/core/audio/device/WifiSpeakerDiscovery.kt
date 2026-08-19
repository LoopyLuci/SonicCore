package com.soniccore.core.audio.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.device.WifiProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers network speakers over mDNS/DNS-SD.
 *
 * `AudioManager.getDevices()` never reports Chromecast / AirPlay / Sonos / DLNA
 * endpoints, so they must be found here and modelled as first-class devices.
 * Playing *to* them requires each vendor's protocol (Cast SDK, RAOP, UPnP) —
 * discovery and metadata is what the platform alone can give us.
 */
@Singleton
class WifiSpeakerDiscovery @Inject constructor(
    private val context: Context,
) {
    private val nsdManager: NsdManager? =
        ContextCompat.getSystemService(context, NsdManager::class.java)

    private val discovered = ConcurrentHashMap<String, AudioDevice>()

    /** Discover every supported protocol at once; emits the merged, growing set. */
    fun discover(protocols: List<WifiProtocol> = DEFAULT_PROTOCOLS): Flow<List<AudioDevice>> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val listeners = mutableListOf<Pair<String, NsdManager.DiscoveryListener>>()

        protocols.forEach { protocol ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String?) = Unit
                override fun onDiscoveryStopped(serviceType: String?) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    val info = serviceInfo ?: return
                    resolve(manager, info, protocol) { device ->
                        discovered[device.stableKey] = device
                        trySend(discovered.values.sortedBy { it.displayName })
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    val name = serviceInfo?.serviceName ?: return
                    discovered.entries.removeAll { it.value.displayName == name }
                    trySend(discovered.values.sortedBy { it.displayName })
                }
            }

            runCatching {
                manager.discoverServices(
                    protocol.serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
                listeners += protocol.serviceType to listener
            }
        }

        trySend(discovered.values.toList())

        awaitClose {
            listeners.forEach { (_, listener) ->
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
    }.conflate()

    private fun resolve(
        manager: NsdManager,
        info: NsdServiceInfo,
        protocol: WifiProtocol,
        onResolved: (AudioDevice) -> Unit,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                val resolved = serviceInfo ?: return
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress
                onResolved(toDevice(resolved.serviceName, host, protocol))
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(info, listener)
        }
    }

    private fun toDevice(name: String, ipAddress: String?, protocol: WifiProtocol): AudioDevice {
        val cleanName = name.replace(Regex("\\._.*$"), "").replace('-', ' ').trim()
        return AudioDevice(
            stableKey = AudioDevice.buildStableKey(
                transport = DeviceTransport.WIFI,
                address = ipAddress,
                productName = cleanName,
                direction = DeviceDirection.OUTPUT,
            ),
            systemId = null,
            displayName = cleanName.ifBlank { protocol.displayName },
            productName = cleanName,
            address = ipAddress,
            transport = DeviceTransport.WIFI,
            kind = if (protocol == WifiProtocol.SONOS) DeviceKind.SPEAKER else DeviceKind.SPEAKER,
            direction = DeviceDirection.OUTPUT,
            capabilities = DeviceCapabilities(
                supportsOutput = true,
                supportsInput = false,
                channelCounts = listOf(2),
                sampleRates = listOf(44_100, 48_000),
                hasHardwareVolume = true,
                supportsCodecSelection = false,
                supportsBatteryReporting = false,
                supportsLowLatencyMode = false,
            ),
            connectionState = ConnectionState.DISCONNECTED,
            wifiProtocol = protocol,
            ipAddress = ipAddress,
            measuredLatencyMs = protocol.defaultLatencyMs(),
            lastSeenEpochMs = System.currentTimeMillis(),
        )
    }

    private fun WifiProtocol.defaultLatencyMs(): Int = when (this) {
        WifiProtocol.AIRPLAY -> 2_000
        WifiProtocol.CHROMECAST -> 1_500
        WifiProtocol.SONOS -> 1_000
        WifiProtocol.DLNA -> 2_500
        WifiProtocol.SPOTIFY_CONNECT -> 1_200
        WifiProtocol.GENERIC -> 2_000
    }

    val isSupported: Boolean get() = nsdManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    companion object {
        val DEFAULT_PROTOCOLS = listOf(
            WifiProtocol.CHROMECAST,
            WifiProtocol.AIRPLAY,
            WifiProtocol.SONOS,
            WifiProtocol.SPOTIFY_CONNECT,
        )
    }
}
