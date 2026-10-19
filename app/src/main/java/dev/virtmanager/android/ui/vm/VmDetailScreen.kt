package dev.virtmanager.android.ui.vm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.DeleteVmDialog
import dev.virtmanager.android.ui.components.DomainAction
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.components.StateBadge

private val tabs = listOf("Overview", "Hardware", "Snapshots", "Settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmDetailScreen(
    connectionId: String,
    vmName: String,
    onBack: () -> Unit,
    onConsole: (String) -> Unit,
    onSerial: (String) -> Unit,
    onEditXml: (String) -> Unit,
    notice: String? = null,
    customize: Boolean = false,
) {
    val app = app()
    val vm: VmDetailViewModel = viewModel(key = "vm-$connectionId-$vmName") {
        VmDetailViewModel(app, connectionId, vmName, notice, customize)
    }
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<DomainAction?>(null) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(vm) {
        vm.startPolling()
        onPauseOrDispose { vm.stopPolling() }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    // After Begin installation, go straight to the installer's screen like virt-manager does.
    LaunchedEffect(state.openConsole, state.definition) {
        if (!state.openConsole) return@LaunchedEffect
        val d = state.definition ?: return@LaunchedEffect
        vm.consoleOpened()
        when {
            d.vnc != null -> onConsole(state.name)
            d.hasSerialConsole -> onSerial(state.name)
        }
    }

    val domainState = state.stats?.state ?: state.info?.state ?: DomainState.NO_STATE
    val active = domainState.isActive

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (active && state.definition?.hasSerialConsole == true) {
                        IconButton(onClick = { onSerial(state.name) }) { Icon(Icons.Outlined.Terminal, "Serial console") }
                    }
                    if (active && state.definition?.vnc != null) {
                        IconButton(onClick = { onConsole(state.name) }) { Icon(Icons.Outlined.DesktopWindows, "Graphical console") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                enabled = !state.busy,
                                onClick = { menu = false; deleting = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
            ErrorBanner(state.error, onDismiss = vm::dismissError)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Don't flash "No state" before the first poll has answered.
                if (state.stats != null || state.info != null) {
                    StateBadge(domainState)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    listOfNotNull(
                        state.definition?.let { d -> d.vcpus?.let { "$it vCPU" } },
                        state.definition?.memoryKiB?.let { dev.virtmanager.android.ui.components.Format.kib(it) },
                        state.definition?.arch,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.customizing && !active) {
                CustomizeBanner(busy = state.busy, onBegin = vm::beginInstallation)
            } else Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val actions = DomainAction.availableFor(domainState)
                val primary = DomainAction.primaryFor(domainState)
                actions.forEach { a ->
                    if (a == primary) {
                        Button(onClick = { if (a.confirm != null) pending = a else vm.action(a.label, a.run) }, enabled = !state.busy) {
                            Icon(a.icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(a.label)
                        }
                    } else {
                        AssistChip(
                            onClick = { if (a.confirm != null) pending = a else vm.action(a.label, a.run) },
                            label = { Text(a.label) },
                            leadingIcon = { Icon(a.icon, null, Modifier.size(18.dp)) },
                            enabled = !state.busy,
                        )
                    }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> VmOverviewTab(state)
                1 -> VmHardwareTab(state, vm)
                2 -> VmSnapshotsTab(state, vm)
                3 -> VmSettingsTab(state, vm, onEditXml = { onEditXml(state.name) })
            }
        }
    }

    if (deleting) {
        DeleteVmDialog(
            name = state.name,
            running = active,
            disks = state.definition?.disks,
            onConfirm = { vm.delete(it); deleting = false },
            onDismiss = { deleting = false },
        )
    }

    pending?.let { a ->
        ConfirmDialog(
            title = "${a.label} \"${state.name}\"?",
            message = a.confirm.orEmpty(),
            confirmLabel = a.label,
            destructive = a == DomainAction.FORCE_OFF || a == DomainAction.RESET,
            onConfirm = { vm.action(a.label, a.run); pending = null },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun CustomizeBanner(busy: Boolean, onBegin: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Customize before install", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Adjust CPUs, memory, install media and network cards on the Hardware tab, or edit the XML " +
                    "in Settings. Nothing runs until you begin the installation.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBegin, enabled = !busy) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Begin installation")
            }
        }
    }
}
