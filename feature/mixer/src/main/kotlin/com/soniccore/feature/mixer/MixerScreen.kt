package com.soniccore.feature.mixer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.soniccore.core.audio.session.TransportAction
import com.soniccore.core.model.audio.AudioStream
import com.soniccore.core.model.mixer.AppAudioSession
import com.soniccore.core.model.mixer.PlaybackState
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.component.VolumeSliderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(
    viewModel: MixerViewModel = hiltViewModel(),
    onRequestNotificationAccess: () -> Unit = {},
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.mixer_mixer)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            item { SectionHeader(title = stringResource(R.string.mixer_system_streams), subtitle = stringResource(R.string.mixer_hardware_volume_per_stream)) }
            items(AudioStream.entries.toList(), key = { it.name }) { stream ->
                state.streamVolumes[stream]?.let { volume ->
                    VolumeSliderRow(
                        volume = volume,
                        onPercentChange = { viewModel.setStreamVolume(stream, it) },
                        onToggleMute = { viewModel.toggleStreamMute(stream) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.mixer_apps),
                    subtitle = if (state.hasNotificationAccess) {
                        "${state.sessions.size} active session(s)"
                    } else {
                        "Needs notification access"
                    },
                )
            }

            if (!state.hasNotificationAccess) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.mixer_per_app_control_needs_notification_acces),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.mixer_android_only_exposes_other_apps_media_se),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onRequestNotificationAccess) {
                                Text(stringResource(R.string.mixer_grant_access))
                            }
                        }
                    }
                }
            } else if (state.sessions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.mixer_no_apps_are_playing_audio_right_now),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(state.sessions, key = { it.packageName + (it.sessionTag ?: "") }) { session ->
                    AppSessionRow(
                        session = session,
                        onVolumeChange = { viewModel.setSessionVolume(session, it) },
                        onTransport = { viewModel.transport(session, it) },
                    )
                }
            }

            item {
                LimitationNotice(
                    text = stringResource(R.string.mixer_android_does_not_allow_one_app_to_re_rou),
                )
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun AppSessionRow(
    session: AppAudioSession,
    onVolumeChange: (Float) -> Unit,
    onTransport: (TransportAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.appLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    session.trackTitle?.let { title ->
                        Text(
                            text = listOfNotNull(title, session.artist).joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (session.isActive) InfoChip(text = stringResource(R.string.mixer_playing))
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onTransport(TransportAction.PREVIOUS) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = {
                        onTransport(
                            if (session.playbackState == PlaybackState.PLAYING) {
                                TransportAction.PAUSE
                            } else {
                                TransportAction.PLAY
                            },
                        )
                    },
                ) {
                    Icon(
                        imageVector = if (session.playbackState == PlaybackState.PLAYING) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = "Play/pause",
                    )
                }
                IconButton(onClick = { onTransport(TransportAction.NEXT) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }

                val sessionVolume = session.volumePercent
                if (session.canControlVolume && sessionVolume != null) {
                    Slider(
                        value = sessionVolume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.mixer_no_independent_volume),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
