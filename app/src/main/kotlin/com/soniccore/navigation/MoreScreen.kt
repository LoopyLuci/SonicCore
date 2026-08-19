package com.soniccore.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.feature.settings.SettingsScreen

/**
 * "More" tab: links to the secondary feature screens, with the full app settings
 * beneath them.
 *
 * PITFALL (caught by an instrumented test on a real device): the tool links used to
 * live in a `Column(verticalScroll(...))` with [SettingsScreen] — which owns a
 * `LazyColumn` — nested inside it. A lazy list inside an infinite-height scroll
 * container throws:
 *
 *   IllegalStateException: Vertically scrollable component was measured with an
 *   infinity maximum height constraints
 *
 * so the tab crashed on open. Exactly one scrollable owns the vertical axis now:
 * the links are a fixed-height header and [SettingsScreen] does the scrolling.
 */
@Composable
fun MoreScreen(
    onNavigate: (SonicDestination) -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onRequestDndAccess: () -> Unit,
    onShareText: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Fixed-height header — NOT scrollable, so the LazyColumn below gets
        // bounded constraints.
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            SectionHeader(title = stringResource(R.string.more_audio_tools))
            ToolLinks(onNavigate = onNavigate)

            // Troubleshooting entry point. The README, CONTRIBUTING guide and
            // bug-report template all tell users to come here, so it must exist.
            //
            // A compact Row, NOT a ListItem: this Column is the fixed-height header that
            // gives the LazyColumn below its bounded constraints. A tall ListItem here
            // pushed the tool links and the bottom bar off screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDiagnostics)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.more_diagnostics),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SettingsScreen(
            onShareBackup = onShareText,
            onOpenNotificationAccess = onRequestNotificationAccess,
            onOpenDndAccess = onRequestDndAccess,
        )
    }
}

@Composable
private fun ToolLinks(onNavigate: (SonicDestination) -> Unit) {
    val links = listOf(
        SonicDestination.MIXER to "Per-app volume and media sessions",
        SonicDestination.EFFECTS to "Spatial audio, reverb, crossfeed, dynamics",
        SonicDestination.MICROPHONE to "Input gain, noise gate, monitoring",
        SonicDestination.AUTOMATION to "Rules that switch profiles for you",
    )
    links.forEach { (destination, subtitle) ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clickable { onNavigate(destination) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(destination.icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
