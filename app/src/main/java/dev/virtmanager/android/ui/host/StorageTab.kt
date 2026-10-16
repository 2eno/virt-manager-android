package dev.virtmanager.android.ui.host

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.StorageVolume
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.EmptyState
import dev.virtmanager.android.ui.components.InfoRow
import dev.virtmanager.android.ui.components.ListPadding
import dev.virtmanager.android.ui.components.SectionCard
import dev.virtmanager.android.ui.components.StateBadge
import dev.virtmanager.android.ui.components.UsageBar
import dev.virtmanager.android.ui.theme.LocalStatusColors

/** "100.00 GiB" → bytes, for the usage bar. */
private fun parseSize(s: String?): Double? {
    if (s == null) return null
    val parts = s.trim().split(" ")
    val v = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val mult = when (parts.getOrNull(1)?.lowercase()) {
        "kib" -> 1024.0
        "mib" -> 1024.0 * 1024
        "gib" -> 1024.0 * 1024 * 1024
        "tib" -> 1024.0 * 1024 * 1024 * 1024
        "pib" -> 1024.0 * 1024 * 1024 * 1024 * 1024
        else -> 1.0
    }
    return v * mult
}

@Composable
fun StorageTab(state: HostUiState, vm: HostViewModel) {
    LaunchedEffect(Unit) { vm.loadPools() }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var deleting by remember { mutableStateOf<Pair<String, StorageVolume>?>(null) }
    val colors = LocalStatusColors.current
    val pools = state.pools
    when {
        pools == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        pools.isEmpty() -> EmptyState(Icons.Outlined.Storage, "No storage pools", "This host has no libvirt storage pools defined.")
        else -> LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pools, key = { it.name }) { pool ->
                val open = pool.name in expanded
                SectionCard(
                    title = pool.name,
                    icon = Icons.Outlined.Storage,
                    action = { StateBadge(if (pool.active) DomainState.RUNNING else DomainState.SHUTOFF) },
                ) {
                    val cap = parseSize(pool.capacity)
                    val alloc = parseSize(pool.allocation)
                    if (cap != null && alloc != null && cap > 0) {
                        UsageBar((alloc / cap).toFloat(), colors.disk)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${pool.allocation} used of ${pool.capacity} · ${pool.available} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    InfoRow("Autostart", if (pool.autostart) "Yes" else "No")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (pool.active) {
                            TextButton(onClick = {
                                expanded = if (open) expanded - pool.name else expanded + pool.name
                                if (!open) vm.loadVolumes(pool.name)
                            }) {
                                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Volumes")
                            }
                            OutlinedButton(onClick = { vm.poolAction(pool.name) { refreshPool(pool.name) } }) { Text("Refresh") }
                            OutlinedButton(onClick = { vm.poolAction(pool.name) { stopPool(pool.name) } }) { Text("Stop") }
                        } else {
                            FilledTonalButton(onClick = { vm.poolAction(pool.name) { startPool(pool.name) } }) { Text("Start") }
                        }
                    }
                    AnimatedVisibility(open) {
                        Column {
                            val vols = state.volumes[pool.name]
                            when {
                                vols == null -> CircularProgressIndicator(Modifier.padding(8.dp))
                                vols.isEmpty() -> Text("No volumes", style = MaterialTheme.typography.bodySmall)
                                else -> vols.forEach { v ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (v.name.endsWith(".iso", true)) Icons.Outlined.Album else Icons.Outlined.InsertDriveFile,
                                            null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(v.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                listOfNotNull(v.capacity, v.allocation?.let { "$it allocated" }).joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { deleting = pool.name to v }) { Icon(Icons.Outlined.Delete, "Delete volume") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { (pool, vol) ->
        ConfirmDialog(
            title = "Delete volume?",
            message = "${vol.path} will be permanently deleted from the host.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.poolAction(pool) { deleteVolume(pool, vol.name) }; deleting = null },
            onDismiss = { deleting = null },
        )
    }
}
