package com.wax.module.ui.targets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wax.module.R
import com.wax.module.platform.TargetApp
import com.wax.module.settings.SettingKeyRegistry
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.TriState
import com.wax.module.ui.theme.WaXTheme

/**
 * Per-target settings, one scope at a time.
 *
 * Global is the default for both WhatsApp builds. A target is either following Global or
 * has diverged, and this screen exists to make that distinction visible and editable,
 * because with one APK serving two builds a setting that silently means the wrong thing
 * in one of them has no other symptom.
 */
class TargetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaXTheme {
                TargetSettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSettingsScreen(viewModel: TargetSettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.per_target_settings)) },
                actions = {
                    IconButton(onClick = { viewModel.reloadFromUi() }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.reload),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            ScopeSelector(
                scope = state.scope,
                overrideCount = state.overrideCount,
                totalOverrideCount = state.totalOverrideCount,
                onSelect = {
                    query = ""
                    viewModel.selectScope(it)
                },
            )

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.search_settings)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val visible =
                state.rows.filter { row ->
                    query.isBlank() ||
                        row.entry.key.contains(query, ignoreCase = true) ||
                        row.entry.category.contains(query, ignoreCase = true)
                }

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.no_settings_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.entry.key }) { row ->
                        SettingRow(
                            row = row,
                            scope = state.scope,
                            onTriState = { viewModel.setTriState(row.entry, it) },
                            onPin = { viewModel.pinGlobalValue(row.entry) },
                            onClearOverride = { viewModel.clearOverride(row.entry) },
                            onGlobalToggle = { viewModel.setGlobalToggle(row.entry, it) },
                        )
                    }
                }
            }

            if (state.scope is SettingsScope.Target) {
                TargetActions(
                    onResetTarget = { viewModel.resetTarget() },
                    onCopyGlobal = { viewModel.copyGlobalToTarget() },
                    onResetAll = { viewModel.resetEverything() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSelector(
    scope: SettingsScope,
    overrideCount: Int,
    totalOverrideCount: Int,
    onSelect: (SettingsScope) -> Unit,
) {
    val scopes = remember { listOf(SettingsScope.Global) + TargetApp.entries.map { SettingsScope.Target(it) } }
    val labels =
        mapOf(
            SettingsScope.Global.code to stringResource(R.string.scope_global),
            TargetApp.WHATSAPP.code to TargetApp.WHATSAPP.displayName,
            TargetApp.WHATSAPP_BUSINESS.code to TargetApp.WHATSAPP_BUSINESS.displayName,
        )

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            scopes.forEachIndexed { index, candidate ->
                SegmentedButton(
                    selected = candidate == scope,
                    onClick = { onSelect(candidate) },
                    shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                ) {
                    Text(labels[candidate.code].orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                if (scope is SettingsScope.Global) {
                    stringResource(R.string.scope_global_explainer)
                } else {
                    stringResource(R.string.scope_target_explainer, overrideCount, totalOverrideCount)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRow(
    row: TargetSettingsViewModel.Row,
    scope: SettingsScope,
    onTriState: (TriState) -> Unit,
    onPin: () -> Unit,
    onClearOverride: () -> Unit,
    onGlobalToggle: (Boolean) -> Unit,
) {
    val entry = row.entry
    // 0 means the screen declared no title, in which case the key is the honest label.
    val title = if (entry.titleRes != 0) stringResource(entry.titleRes) else entry.key

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (row.overridden && scope is SettingsScope.Target) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = entry.key,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            if (scope is SettingsScope.Global) {
                GlobalControl(entry = entry, triState = row.triState, onChange = onGlobalToggle)
            } else {
                TargetControl(
                    entry = entry,
                    row = row,
                    onTriState = onTriState,
                    onPin = onPin,
                    onClearOverride = onClearOverride,
                )
            }
        }
    }
}

@Composable
private fun GlobalControl(
    entry: SettingKeyRegistry.Entry,
    triState: TriState,
    onChange: (Boolean) -> Unit,
) {
    if (entry.isToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.default_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = triState == TriState.ENABLED,
                onCheckedChange = { onChange(it) },
                modifier =
                    Modifier.semantics {
                        contentDescription = ""
                    },
            )
        }
    } else {
        Text(
            text = stringResource(R.string.edit_on_original_screen, entry.screen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetControl(
    entry: SettingKeyRegistry.Entry,
    row: TargetSettingsViewModel.Row,
    onTriState: (TriState) -> Unit,
    onPin: () -> Unit,
    onClearOverride: () -> Unit,
) {
    if (entry.isToggle) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TriState.entries.forEach { state ->
                FilterChip(
                    selected = row.triState == state,
                    onClick = { onTriState(state) },
                    label = { Text(stringResource(state.labelRes())) },
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (row.overridden) {
                            stringResource(R.string.pinned_to, row.effective.orEmpty())
                        } else {
                            stringResource(R.string.follows_global)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPin) { Text(stringResource(R.string.pin_global)) }
            TextButton(onClick = onClearOverride) { Text(stringResource(R.string.follow_global)) }
        }
    }
}

@Composable
private fun TargetActions(
    onResetTarget: () -> Unit,
    onCopyGlobal: () -> Unit,
    onResetAll: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onCopyGlobal, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.copy_global_to_target))
        }
        TextButton(onClick = onResetTarget, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reset_this_target))
        }
        TextButton(onClick = onResetAll, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reset_all_targets))
        }
    }
}

/** Label for each tri-state, resolved once so the row does not branch on strings. */
private fun TriState.labelRes(): Int =
    when (this) {
        TriState.INHERIT -> R.string.tri_state_inherit
        TriState.ENABLED -> R.string.tri_state_enabled
        TriState.DISABLED -> R.string.tri_state_disabled
    }
