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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.common.diagnostics.FailureSummary
import com.soniccore.core.ui.R

/**
 * Opt-in failure reporting.
 *
 * Privacy by design:
 * - Everything stays on-device until the user explicitly shares it.
 * - No device identifiers, no personal data, no network access.
 * - The report contains only failure category counts and anonymised messages —
 *   enough to spot "codec selection fails on Android 15" trends without tracking.
 */
@Composable
fun FailureReportScreen(
    onShareText: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val summary by viewModel.failureSummary.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshFailureSummary()
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {

        Text(
            text = "Failure report",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Helps the developer spot platform-specific failures. Nothing leaves your device unless you share it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share failure data", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (viewModel.sharingEnabled) "On — recompute will update summary" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = viewModel.sharingEnabled,
                onCheckedChange = { viewModel.sharingEnabled = it },
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!summary.hasFailures) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    text = "No failures recorded. If something stops working, check back here — the report helps get it fixed.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    text = "${summary.totalFailures} failures across ${summary.byCategory.size} categories",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(summary.byCategory.entries.sortedByDescending { it.value }) { (category, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(category, style = MaterialTheme.typography.bodyMedium)
                        Text("$count", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { onShareText(viewModel.buildFailureReport()) },
            enabled = summary.hasFailures,
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text("Share report", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun FailureSummary.hasFailures(): Boolean = totalFailures > 0
