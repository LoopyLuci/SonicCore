package com.soniccore.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.DeviceCard
import com.soniccore.core.ui.component.EmptyState
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.LoadingRow
import com.soniccore.core.ui.component.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.devices_devices)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            if (state.isLoading) {
                item { LoadingRow(text = stringResource(R.string.loading_devices)) }
            } else if (state.devices.isEmpty() && state.knownDevices.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.empty_devices_title),
                        body = stringResource(R.string.empty_devices),
                    )
                }
            } else {
            if (!state.bluetoothPermissionGranted) {
                item {
                    LimitationNotice(
                        text = stringResource(R.string.devices_bluetooth_permission_not_granted_battery),
                    )
                }
            }

            DeviceTransport.entries
                .filter { transport -> state.devices.any { it.transport == transport } }
                .forEach { transport ->
                    val group = state.devices.filter { it.transport == transport }
                    item(key = "header_${transport.name}") {
                        SectionHeader(
                            title = transportTitle(transport),
                            subtitle = pluralStringResource(R.plurals.count_devices, group.size, group.size),
                        )
                    }
                    items(group, key = { it.stableKey }) { device ->
                        DeviceCard(
                            device = device,
                            isActive = device.stableKey == state.activeOutput?.stableKey ||
                                device.stableKey == state.activeInput?.stableKey,
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .clickable { viewModel.routeTo(device) },
                            trailing = {
                                IconButton(onClick = { viewModel.toggleFavorite(device) }) {
                                    Icon(
                                        imageVector = if (device.isFavorite) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Filled.StarBorder
                                        },
                                        contentDescription = "Favourite",
                                    )
                                }
                            },
                        )
                    }
                    item(key = "caps_${transport.name}") {
                        val device = group.first()
                        CapabilityDetails(
                            sampleRates = device.capabilities.sampleRates,
                            channelCounts = device.capabilities.channelCounts,
                            encodings = device.capabilities.encodings.map { it.displayName },
                        )
                    }
                }

            if (state.knownDevices.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.devices_remembered),
                        subtitle = stringResource(R.string.devices_not_connected_right_now_settings_are_kep),
                    )
                }
                items(state.knownDevices, key = { "known_${it.stableKey}" }) { device ->
                    DeviceCard(device = device, modifier = Modifier.padding(vertical = 3.dp))
                }
            }

            if (!state.wifiDiscoverySupported) {
                item {
                    LimitationNotice(
                        text = stringResource(R.string.devices_network_speaker_discovery_needs_android),
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun CapabilityDetails(
    sampleRates: List<Int>,
    channelCounts: List<Int>,
    encodings: List<String>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
    ) {
        Column(Modifier.padding(11.dp)) {
            Text(
                text = stringResource(R.string.devices_reported_capabilities),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                sampleRates.take(5).forEach { rate ->
                    InfoChip(text = stringResource(R.string.format_khz, (rate / 1000).toString()), mono = true)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                channelCounts.take(4).forEach { count ->
                    InfoChip(text = stringResource(R.string.format_channels, count), mono = true)
                }
                encodings.take(3).forEach { encoding ->
                    InfoChip(text = encoding)
                }
            }
        }
    }
}

private fun transportTitle(transport: DeviceTransport): String = when (transport) {
    DeviceTransport.ANALOG_35MM -> "3.5 mm analog"
    DeviceTransport.USB -> "USB audio"
    DeviceTransport.BLUETOOTH_CLASSIC -> "Bluetooth"
    DeviceTransport.BLUETOOTH_LE -> "LE Audio"
    DeviceTransport.WIFI -> "Network speakers"
    DeviceTransport.HDMI -> "HDMI / eARC"
    DeviceTransport.BUILTIN -> "Built-in"
    DeviceTransport.UNKNOWN -> "Other"
}
