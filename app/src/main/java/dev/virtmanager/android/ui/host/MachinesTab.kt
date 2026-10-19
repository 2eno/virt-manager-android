package dev.virtmanager.android.ui.host

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.data.DomainMetrics
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.DomainStats
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.DeleteVmDialog
import dev.virtmanager.android.ui.components.DomainAction
import dev.virtmanager.android.ui.components.EmptyState
import dev.virtmanager.android.ui.components.Format
import dev.virtmanager.android.ui.components.ListPadding
import dev.virtmanager.android.ui.components.Sparkline
import dev.virtmanager.android.ui.components.StateBadge
import dev.virtmanager.android.ui.components.stateColor
import dev.virtmanager.android.ui.theme.LocalStatusColors

private enum class Filter(val label: String) { ALL("All"), RUNNING("Running"), STOPPED("Stopped") }

@Composable
fun MachinesTab(
    state: HostUiState,
    vm: HostViewModel,
    onOpenVm: (String) -> Unit,
    onConsole: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var pending by remember { mutableStateOf<Pair<String, DomainAction>?>(null) }

    if (state.loading && state.domains.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val shown = state.domains.filter { d ->
        (query.isBlank() || d.name.contains(query, ignoreCase = true)) && when (filter) {
            Filter.ALL -> true
            Filter.RUNNING -> d.state.isActive
            Filter.STOPPED -> !d.state.isActive
        }
    }

    fun act(name: String, action: DomainAction) {
        if (action.confirm != null) pending = name to action
        else vm.domainAction(name, action.label, action = action.run)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search virtual machines") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") } } } else null,
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                val count = when (f) {
                    Filter.ALL -> state.domains.size
                    Filter.RUNNING -> state.domains.count { it.state.isActive }
                    Filter.STOPPED -> state.domains.count { !it.state.isActive }
                }
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} · $count") })
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Computer,
                title = if (state.domains.isEmpty()) "No virtual machines" else "Nothing matches",
                message = if (state.domains.isEmpty()) "Create your first VM with the New VM button." else "Try a different search or filter.",
            )
        } else {
            LazyColumn(
                state = rememberLazyListState(),
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.name }) { d ->
                    VmCard(
                        domain = d,
                        metrics = state.metrics[d.name] ?: DomainMetrics(),
                        busy = d.name in state.busy,
                        onClick = { onOpenVm(d.name) },
                        onConsole = { onConsole(d.name) },
                        onAction = { act(d.name, it) },
                        onDelete = { vm.requestDelete(d) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    state.deleteRequest?.let { req ->
        DeleteVmDialog(
            name = req.name,
            running = req.running,
            disks = req.disks,
            onConfirm = vm::confirmDelete,
            onDismiss = vm::cancelDelete,
        )
    }

    pending?.let { (name, action) ->
        ConfirmDialog(
            title = "${action.label} \"$name\"?",
            message = action.confirm.orEmpty(),
            confirmLabel = action.label,
            destructive = action == DomainAction.FORCE_OFF || action == DomainAction.RESET,
            onConfirm = { vm.domainAction(name, action.label, action = action.run); pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun VmCard(
    domain: DomainStats,
    metrics: DomainMetrics,
    busy: Boolean,
    onClick: () -> Unit,
    onConsole: () -> Unit,
    onAction: (DomainAction) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current
    val active = domain.state.isActive
    val running = domain.state == DomainState.RUNNING || domain.state == DomainState.BLOCKED
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = stateColor(domain.state).copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DesktopWindows, null, tint = stateColor(domain.state))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(domain.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    StateBadge(domain.state)
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    DomainAction.primaryFor(domain.state)?.let { primary ->
                        FilledTonalIconButton(onClick = { onAction(primary) }) { Icon(primary.icon, primary.label) }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (active) {
                            DropdownMenuItem(
                                text = { Text("Open console") },
                                leadingIcon = { Icon(Icons.Outlined.DesktopWindows, null) },
                                onClick = { menu = false; onConsole() },
                            )
                            HorizontalDivider()
                        }
                        DomainAction.availableFor(domain.state).forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                leadingIcon = { Icon(a.icon, null) },
                                onClick = { menu = false; onAction(a) },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            enabled = !busy,
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            if (running) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CPU ${Format.percent(metrics.cpuPercent)}", style = MaterialTheme.typography.labelMedium, color = colors.cpu)
                        Sparkline(metrics.cpuHistory, colors.cpu, Modifier.fillMaxWidth().height(28.dp), capacity = 30)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Memory ${Format.kib(domain.memoryKiB)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.memory,
                            maxLines = 1,
                        )
                        Sparkline(metrics.memoryHistory, colors.memory, Modifier.fillMaxWidth().height(28.dp), capacity = 30)
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        domain.maxVcpus?.let { "$it vCPU" },
                        domain.maxMemoryKiB?.let { Format.kib(it) },
                        domain.blocks.size.takeIf { it > 0 }?.let { "$it disk" + if (it > 1) "s" else "" },
                    ).joinToString("  ·  ").ifEmpty { "Not running" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 58.dp),
                )
            }
        }
    }
}
