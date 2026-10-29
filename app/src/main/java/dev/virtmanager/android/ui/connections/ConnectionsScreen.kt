package dev.virtmanager.android.ui.connections

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.data.ConnectionProfile
import dev.virtmanager.android.data.ConnectionStatus
import dev.virtmanager.android.data.ThemeMode
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.EmptyState
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.components.ListPadding
import dev.virtmanager.android.ui.components.PasswordDialog
import dev.virtmanager.android.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val app = app()
    val vm: ConnectionsViewModel = viewModel { ConnectionsViewModel(app) }
    val profiles by vm.profiles.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val passwordFor by vm.passwordRequest.collectAsState()
    val theme by vm.themeMode.collectAsState()
    var deleting by remember { mutableStateOf<ConnectionProfile?>(null) }
    var themeMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.opened.collect { onOpen(it) } }
    LaunchedEffect(profiles) { profiles?.let(vm::autoConnect) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Virtual Machine Manager") },
                actions = {
                    Box {
                        IconButton(onClick = { themeMenu = true }) { Icon(Icons.Outlined.BrightnessMedium, "Theme") }
                        DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    leadingIcon = { RadioButton(selected = theme == mode, onClick = null) },
                                    onClick = { vm.setTheme(mode); themeMenu = false },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add connection") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ErrorBanner(error, onDismiss = { vm.error.value = null })
            val list = profiles
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Dns,
                    title = "No connections yet",
                    message = "Add a libvirt host reachable over SSH. Your phone talks to the host's virsh, " +
                        "just like virt-manager's qemu+ssh:// connections.",
                    action = { FilledTonalButton(onClick = onAdd) { Text("Add a host") } },
                )
                else -> LazyColumn(
                    contentPadding = ListPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { profile ->
                        ConnectionCard(
                            profile = profile,
                            via = profile.jumpHostId?.let { id -> list.firstOrNull { it.id == id }?.name ?: "missing jump host" },
                            status = status[profile.id] ?: ConnectionStatus.Disconnected,
                            onOpen = { vm.open(profile) },
                            onDisconnect = { vm.disconnect(profile.id) },
                            onEdit = { onEdit(profile.id) },
                            onDelete = { deleting = profile },
                        )
                    }
                }
            }
        }
    }

    passwordFor?.let { req ->
        val hop = req.hop
        PasswordDialog(
            title = "Connect to ${req.profile.name}",
            message = (if (hop.id != req.profile.id) "Jump host ${hop.name}: " else "") + "${hop.username}@${hop.host}",
            onConfirm = { vm.passwordEntered(req, it) },
            onDismiss = { vm.passwordRequest.value = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete connection?",
            message = "\"${p.name}\" and its stored credentials will be removed from this device. Nothing on the host is changed.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(p); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ConnectionCard(
    profile: ConnectionProfile,
    via: String?,
    status: ConnectionStatus,
    onOpen: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    profile.displayUri + (via?.let { " via $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val (label, color) = when (status) {
                    ConnectionStatus.Connected -> "Connected" to colors.running
                    ConnectionStatus.Connecting -> "Connecting…" to colors.paused
                    is ConnectionStatus.Failed -> status.message to colors.crashed
                    ConnectionStatus.Disconnected -> "Not connected" to colors.stopped
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (status == ConnectionStatus.Connecting) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (status == ConnectionStatus.Connected) {
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                            onClick = { menu = false; onDisconnect() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
