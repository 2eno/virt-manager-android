package dev.virtmanager.android.ui.vm

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.DeleteVmDialog
import dev.virtmanager.android.ui.components.DomainAction
import dev.virtmanager.android.ui.hw.Note
import dev.virtmanager.android.ui.components.Format
import dev.virtmanager.android.ui.components.InfoRow
import dev.virtmanager.android.ui.components.MetricTile
import dev.virtmanager.android.ui.components.SectionCard
import dev.virtmanager.android.ui.components.TextInputDialog
import dev.virtmanager.android.ui.theme.LocalStatusColors
import dev.virtmanager.android.ui.theme.MonoStyle

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content(); Spacer(Modifier.height(32.dp)) }
}

@Composable
fun VmOverviewTab(state: VmUiState) {
    val colors = LocalStatusColors.current
    val m = state.metrics
    val s = state.stats
    TabColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("CPU", Format.percent(m.cpuPercent), colors.cpu, m.cpuHistory, Modifier.weight(1f),
                subtitle = s?.vcpus?.let { "$it vCPU" })
            MetricTile("Memory", Format.percent(m.memoryFraction * 100), colors.memory, m.memoryHistory, Modifier.weight(1f),
                subtitle = s?.memoryKiB?.let { Format.kib(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Disk I/O", Format.rate(m.diskReadRate + m.diskWriteRate), colors.disk, m.diskHistory, Modifier.weight(1f),
                subtitle = "R ${Format.rate(m.diskReadRate)} · W ${Format.rate(m.diskWriteRate)}")
            MetricTile("Network", Format.rate(m.netRxRate + m.netTxRate), colors.network, m.netHistory, Modifier.weight(1f),
                subtitle = "↓ ${Format.rate(m.netRxRate)} · ↑ ${Format.rate(m.netTxRate)}")
        }
        val d = state.definition
        val i = state.info
        if (d != null && d.vnc == null && d.graphics.any { it.type == "spice" }) {
            Note(
                "This VM's display is Spice, which this app can't show yet. Change the display to VNC on the " +
                    "Hardware tab (Display Spice → Type), or add a second display with Add Hardware → Graphics.",
            )
        }
        SectionCard("Basic details", icon = Icons.Outlined.Info) {
            InfoRow("Name", state.name)
            InfoRow("UUID", d?.uuid ?: i?.uuid)
            if (d?.title != null) InfoRow("Title", d.title)
            if (d?.description != null) InfoRow("Description", d.description)
            InfoRow("Hypervisor", d?.domainType?.uppercase())
            InfoRow("Architecture", d?.arch)
            InfoRow("Machine", d?.machine)
            InfoRow("Firmware", d?.firmware?.uppercase())
            InfoRow("Emulator", d?.emulator)
            InfoRow("Persistent", i?.let { if (it.persistent) "Yes" else "No (transient)" })
            InfoRow("Autostart", i?.let { if (it.autostart) "On" else "Off" })
            InfoRow("Guest agent channel", d?.let { if (it.hasGuestAgentChannel) "Present" else "None" })
        }
    }
}

@Composable
fun VmSnapshotsTab(state: VmUiState, vm: VmDetailViewModel) {
    LaunchedEffect(state.name) { vm.loadSnapshots() }
    var creating by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    TabColumn {
        FilledTonalButton(onClick = { creating = true }, enabled = !state.busy) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Take snapshot")
        }
        val snaps = state.snapshots
        when {
            snaps == null -> CircularProgressIndicator()
            snaps.isEmpty() -> Text(
                "No snapshots. Snapshots capture the disk (and, for running VMs, memory) state so you can roll back later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> snaps.forEach { s ->
                SectionCard(
                    title = s.name,
                    icon = if (s.current) Icons.Outlined.Star else Icons.Outlined.History,
                    action = {
                        Row {
                            IconButton(onClick = { reverting = s.name }) { Icon(Icons.Outlined.Restore, "Revert") }
                            IconButton(onClick = { deleting = s.name }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                    },
                ) {
                    InfoRow("Created", s.creationTime)
                    InfoRow("VM state", s.state)
                    InfoRow("Type", if (s.external) "External (overlay files next to the disks)" else "Internal")
                    if (s.current) InfoRow("Current", "Yes")
                }
            }
        }
    }
    if (creating) {
        TextInputDialog(
            title = "New snapshot",
            label = "Name",
            initial = "snapshot-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()),
            confirmLabel = "Create",
            validate = { DomainXmlBuilder.isValidName(it) },
            onConfirm = { vm.createSnapshot(it, null); creating = false },
            onDismiss = { creating = false },
        )
    }
    reverting?.let { s ->
        val running = (state.stats?.state ?: state.info?.state)?.isActive == true
        val passthrough = state.definition?.pciHostdevs.orEmpty()
        val diskOnly = state.snapshots?.firstOrNull { it.name == s }?.state == "disk-snapshot"
        if (running && passthrough.isNotEmpty()) {
            // Reverting a running VM stops its QEMU at once, which makes the host reset the
            // passed-through devices; AMD GPUs often fail that and can hang the whole host.
            ConfirmDialog(
                title = "Shut down \"${state.name}\" first",
                message = "The VM has passed-through PCI devices (${passthrough.joinToString()}). Reverting it while it runs " +
                    "forces it off, and resetting the devices that way can hang the host (common with AMD GPUs). " +
                    "Shut the guest down, then revert.",
                confirmLabel = "Shut down",
                onConfirm = { vm.action("Shut down", DomainAction.SHUTDOWN.run); reverting = null },
                onDismiss = { reverting = null },
            )
        } else {
            ConfirmDialog(
                title = "Revert to \"$s\"?",
                message = "The VM's current state will be discarded and replaced by the snapshot." +
                    if (running && diskOnly) " This snapshot has no memory, so the VM is forced off first, like pulling the power cord." else "",
                confirmLabel = "Revert",
                destructive = true,
                onConfirm = { vm.revertSnapshot(s); reverting = null },
                onDismiss = { reverting = null },
            )
        }
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete snapshot \"$s\"?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.deleteSnapshot(s); deleting = null },
            onDismiss = { deleting = null },
        )
    }
    state.snapshotNeedsShutdown?.let { s ->
        ConfirmDialog(
            title = "Delete \"$s\" with the VM shut down?",
            message = "While the VM runs, its disks can't be merged back on this host (QEMU may only read the image " +
                "below the snapshot, as the host's AppArmor policy allows). Shut down, the merge works. The guest " +
                "is shut down normally and the snapshot deleted once it is off.",
            confirmLabel = "Shut down and delete",
            onConfirm = { vm.shutDownAndDeleteSnapshot(s) },
            onDismiss = vm::dismissSnapshotNeedsShutdown,
        )
    }
}

@Composable
fun VmSettingsTab(state: VmUiState, vm: VmDetailViewModel, onEditXml: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showXml by remember { mutableStateOf(false) }
    val active = state.stats?.state?.isActive == true
    TabColumn {
        SectionCard("Boot options", icon = Icons.Outlined.Settings) {
            val canAutostart = state.info?.persistent == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = state.info?.autostart == true,
                    enabled = canAutostart,
                    role = Role.Switch,
                    onValueChange = vm::setAutostart,
                ),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Start on host boot")
                    Text("Autostart this VM with libvirtd", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.info?.autostart == true, onCheckedChange = null, enabled = canAutostart)
            }
        }
        SectionCard("Manage", icon = Icons.Outlined.Edit) {
            TextButton(onClick = { renaming = true }, enabled = !active) { Text("Rename…") }
            if (active) {
                Text("Shut the VM down to rename it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                TextButton(onClick = onEditXml) {
                    Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit XML…")
                }
                TextButton(onClick = { showXml = !showXml }) {
                    Icon(Icons.Outlined.Code, null); Spacer(Modifier.width(8.dp)); Text(if (showXml) "Hide live XML" else "Show live XML")
                }
            }
            if (showXml) {
                SelectionContainer {
                    Text(
                        state.xml ?: "",
                        style = MonoStyle,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
        SectionCard("Danger zone", icon = Icons.Outlined.DeleteForever) {
            Text(
                "Deleting removes the VM definition from libvirt. Disk images are kept unless you choose to remove them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { deleting = true; vm.loadDeleteCandidates() }) { Text("Delete virtual machine", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (renaming) {
        TextInputDialog(
            title = "Rename VM",
            label = "New name",
            initial = state.name,
            confirmLabel = "Rename",
            validate = { DomainXmlBuilder.isValidName(it) && it != state.name },
            onConfirm = { vm.rename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        DeleteVmDialog(
            name = state.name,
            running = active,
            disks = state.deleteCandidates,
            lookupError = state.deleteLookupError,
            onConfirm = { vm.delete(it); deleting = false },
            onDismiss = { deleting = false },
        )
    }
}
