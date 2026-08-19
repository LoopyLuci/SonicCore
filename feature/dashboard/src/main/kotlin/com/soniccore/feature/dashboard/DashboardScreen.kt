package com.soniccore.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedFilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.audio.engine.TestSignal
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.DeviceCard
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.component.SpectrumVisualizer
import com.soniccore.core.ui.component.VolumeSliderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onOpenEqualizer: () -> Unit = {},
    onOpenDevice: (AudioDevice) -> Unit = {},
    onOpenProfiles: () -> Unit = {},
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_soniccore)) },
                actions = {
                    IconButton(onClick = onOpenEqualizer) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = "Equalizer")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Live spectrum.
            if (state.spectrum.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        ),
                    ) {
                        SpectrumVisualizer(
                            magnitudesDb = state.spectrum,
                            style = state.settings.visualizationStyle,
                            showPeakHold = state.settings.showPeakHold,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                        )
                    }
                }
            }

            // Active output.
            item { SectionHeader(title = stringResource(R.string.dashboard_output)) }
            state.activeOutput?.let { device ->
                item {
                    DeviceCard(
                        device = device,
                        isActive = true,
                        modifier = Modifier.clickable { onOpenDevice(device) },
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
                item {
                    val capability = viewModel.routingCapability(device)
                    LimitationNotice(text = capability.explanation)
                }
            } ?: item {
                Text(
                    text = stringResource(R.string.dashboard_no_output_device_detected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Volume mixer.
            item { SectionHeader(title = stringResource(R.string.dashboard_volume), subtitle = stringResource(R.string.dashboard_per_stream_hardware_control)) }
            items(
                items = listOf(
                    AudioStream.MUSIC,
                    AudioStream.VOICE_CALL,
                    AudioStream.NOTIFICATION,
                    AudioStream.RING,
                    AudioStream.ALARM,
                    AudioStream.SYSTEM,
                ),
                key = { it.name },
            ) { stream ->
                val volume = state.volumes[stream]
                if (volume != null) {
                    VolumeSliderRow(
                        volume = volume,
                        onPercentChange = { viewModel.setVolumePercent(stream, it) },
                        onToggleMute = { viewModel.toggleMute(stream) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            // Profiles.
            item {
                SectionHeader(
                    title = stringResource(R.string.dashboard_profiles),
                    subtitle = state.activeProfile?.let { "Active: ${it.name}" }
                        ?: "${state.profiles.size} available",
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.profiles, key = { it.id }) { profile ->
                        ElevatedFilterChip(
                            selected = profile.isActive,
                            onClick = { viewModel.activateProfile(profile) },
                            label = { Text(profile.name) },
                        )
                    }
                }
            }

            // Apply report — shows exactly what applied and what the platform refused.
            state.lastReport?.let { report ->
                if (!report.fullySucceeded) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.format_applied_with_limitations, report.profileName),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                (report.volumeFailures + report.warnings).distinct().forEach { note ->
                                    LimitationNotice(text = note)
                                }
                            }
                        }
                    }
                }
            }

            // Test signals.
            item { SectionHeader(title = stringResource(R.string.dashboard_test_signals), subtitle = stringResource(R.string.dashboard_verify_a_device_or_audition_the_eq)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            TestSignal.PINK_NOISE,
                            TestSignal.SINE_SWEEP,
                            TestSignal.SINE_1K,
                            TestSignal.LEFT_RIGHT,
                            TestSignal.PHASE_CHECK,
                            TestSignal.WHITE_NOISE,
                        ),
                    ) { signal ->
                        val isPlaying = state.isTestPlaying && state.testSignal == signal
                        AssistChip(
                            onClick = {
                                if (isPlaying) viewModel.stopTestSignal() else viewModel.playTestSignal(signal)
                            },
                            label = { Text(signal.displayName) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }

            // Input.
            item { SectionHeader(title = stringResource(R.string.dashboard_input)) }
            state.activeInput?.let { device ->
                item { DeviceCard(device = device, isActive = true) }
            }

            // All devices.
            item {
                SectionHeader(
                    title = stringResource(R.string.dashboard_all_devices),
                    subtitle = stringResource(R.string.format_io_summary, state.outputDevices.size, state.inputDevices.size),
                )
            }
            items(state.outputDevices, key = { it.stableKey }) { device ->
                DeviceCard(
                    device = device,
                    isActive = device.stableKey == state.activeOutput?.stableKey,
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .clickable { viewModel.routeTo(device) },
                )
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}
