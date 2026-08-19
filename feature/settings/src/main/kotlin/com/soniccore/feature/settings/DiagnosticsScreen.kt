package com.soniccore.feature.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.common.diagnostics.DiagEvent
import com.soniccore.core.common.diagnostics.DiagLevel
// Strings live in core:ui so every feature module shares one translatable catalogue.
import com.soniccore.core.ui.R

/**
 * Diagnostic log viewer.
 *
 * This is the screen the README, CONTRIBUTING guide and bug-report template all point at
 * ("Settings → Diagnostics → Export log"). SonicCore swallows ~150 platform failures to
 * stay alive on hostile OEM audio stacks; without a way to read that record, a report
 * like "codec switching doesn't work on my phone" cannot be acted on.
 */
@Composable
fun DiagnosticsScreen(
    onShareText: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val events by viewModel.diagnosticEvents.collectAsStateWithLifecycle()
    val summary by viewModel.diagnosticSummary.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(16.dp)) {

        Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.diagnostics_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // Summary: counts make it obvious whether there is anything worth reporting.
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.diagnostics_event_count,
                            summary.total,
                            summary.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.diagnostics_counts,
                            summary.errors,
                            summary.warnings,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!summary.hasSomethingToReport) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.diagnostics_all_clear),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onShareText(viewModel.exportDiagnostics()) },
                enabled = events.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = stringResource(R.string.diagnostics_export),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = viewModel::clearDiagnostics,
                enabled = events.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(
                    text = stringResource(R.string.diagnostics_clear),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            // Empty here is GOOD news, so say so rather than showing a blank pane.
            Text(
                text = stringResource(R.string.diagnostics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(events, key = { it.timestampMs.toString() + it.message }) { event ->
                    DiagnosticRow(event)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(event: DiagEvent) {
    val color = when (event.level) {
        DiagLevel.ERROR -> MaterialTheme.colorScheme.error
        DiagLevel.WARN -> MaterialTheme.colorScheme.tertiary
        DiagLevel.INFO -> MaterialTheme.colorScheme.onSurface
        DiagLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = event.format(),
            style = MaterialTheme.typography.bodySmall,
            // Monospace: these are log lines, and aligned timestamps are far easier to scan.
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
