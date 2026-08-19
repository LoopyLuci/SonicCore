package com.soniccore.feature.automation

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.RuleTrigger
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.LoadingRow
import com.soniccore.core.ui.component.EmptyState
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(viewModel: AutomationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<AutomationRule?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.automation_automation)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New rule")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            item {
                SectionHeader(
                    title = stringResource(R.string.automation_rules),
                    subtitle = stringResource(R.string.format_rules_enabled, state.rules.count { it.enabled }, state.rules.size),
                )
            }

            if (state.isLoading) {
                item { LoadingRow() }
            } else if (state.rules.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.empty_rules_title),
                        body = stringResource(R.string.empty_rules),
                    )
                }
            }

            items(state.rules, key = { it.id }) { rule ->
                RuleRow(
                    rule = rule,
                    summary = viewModel.describe(rule),
                    onToggle = { viewModel.setEnabled(rule, it) },
                    onEdit = { viewModel.startEditing(rule) },
                    onDelete = { pendingDelete = rule },
                )
            }

            item {
                LimitationNotice(
                    text = stringResource(R.string.automation_rules_run_inside_soniccore_s_foreground),
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.automation_new_rule)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.automation_rule_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createRule(newName, RuleTrigger.Manual())
                        newName = ""
                        showCreate = false
                    },
                ) { Text(stringResource(R.string.automation_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.automation_cancel)) } },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.format_delete_named, rule.name)) },
            text = { Text(stringResource(R.string.automation_this_rule_will_stop_running_immediately)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(rule)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.automation_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.automation_cancel)) } },
        )
    }
}

@Composable
private fun RuleRow(
    rule: AutomationRule,
    summary: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (rule.enabled) 0.45f else 0.2f,
            ),
        ),
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoChip(text = pluralStringResource(R.plurals.count_actions, rule.actions.size, rule.actions.size))
                    if (rule.conditions.isNotEmpty()) {
                        InfoChip(text = pluralStringResource(R.plurals.count_conditions, rule.conditions.size, rule.conditions.size))
                    }
                    if (rule.fireCount > 0) {
                        InfoChip(text = stringResource(R.string.format_fired_times, rule.fireCount), mono = true)
                    }
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}
