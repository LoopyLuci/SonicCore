package com.soniccore.feature.profiles

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.EmptyState
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LoadingRow
import com.soniccore.core.ui.component.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = hiltViewModel(),
    onEditProfile: (String) -> Unit = {},
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<AudioProfile?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Undo for a deleted profile. The ViewModel retains the full object (EQ, effects,
    // device bindings) so restore is complete — an id alone would not be enough.
    LaunchedEffect(state.recentlyDeleted) {
        val deleted = state.recentlyDeleted
        if (deleted != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Deleted “${deleted.name}”",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.clearRecentlyDeleted()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.profiles_profiles)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New profile")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            if (state.isLoading) {
                item { LoadingRow() }
            } else if (profiles.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.empty_profiles_title),
                        body = stringResource(R.string.empty_profiles),
                        actionLabel = stringResource(R.string.profiles_new_profile),
                        onAction = { showCreateDialog = true },
                    )
                }
            } else {
            item {
                SectionHeader(
                    title = stringResource(R.string.profiles_your_profiles),
                    subtitle = stringResource(R.string.format_profiles_saved, profiles.size),
                )
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileRow(
                    profile = profile,
                    onActivate = { viewModel.activate(profile) },
                    onEdit = { onEditProfile(profile.id) },
                    onDuplicate = { viewModel.duplicate(profile) },
                    onDelete = { pendingDelete = profile },
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.profiles_new_profile)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.profiles_profile_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.create(newName)
                        newName = ""
                        showCreateDialog = false
                    },
                ) { Text(stringResource(R.string.profiles_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.profiles_cancel)) }
            },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.format_delete_named, profile.name)) },
            text = { Text(stringResource(R.string.profiles_this_removes_the_profile_and_all_of_its)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // requestDelete + confirmDelete keeps the deleted profile in the
                        // ViewModel so the Snackbar can offer Undo — a plain delete()
                        // would discard it and make the action unrecoverable.
                        viewModel.requestDelete(profile)
                        viewModel.confirmDelete()
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.profiles_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.profiles_cancel)) }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: AudioProfile,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) {
                Color(profile.colorArgb).copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(profile.colorArgb), CircleShape),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Medium,
                    )
                    if (profile.isBuiltIn) {
                        Spacer(Modifier.width(6.dp))
                        InfoChip(text = stringResource(R.string.profiles_built_in))
                    }
                    if (profile.isActive) {
                        Spacer(Modifier.width(6.dp))
                        InfoChip(text = stringResource(R.string.profiles_active), tint = Color(profile.colorArgb))
                    }
                }
                profile.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (profile.eq.enabled) InfoChip(text = profile.eq.mode.displayName)
                    if (profile.boundDeviceKeys.isNotEmpty()) {
                        InfoChip(text = pluralStringResource(R.plurals.count_devices, profile.boundDeviceKeys.size, profile.boundDeviceKeys.size))
                    }
                    if (profile.appOverrides.isNotEmpty()) {
                        InfoChip(text = pluralStringResource(R.plurals.count_app_rules, profile.appOverrides.size, profile.appOverrides.size))
                    }
                    if (profile.activationCount > 0) {
                        InfoChip(text = stringResource(R.string.format_used_times, profile.activationCount), mono = true)
                    }
                }
            }

            IconButton(onClick = onActivate) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Activate")
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
            }
            if (!profile.isBuiltIn) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}
