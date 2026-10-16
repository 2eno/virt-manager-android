package dev.virtmanager.android.ui.vm

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
import androidx.compose.material3.Checkbox
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
fun VmHardwareTab(state: VmUiState, vm: VmDetailViewModel) {
    val d = state.definition
    var editCpu by remember { mutableStateOf(false) }
    var editMem by remember { mutableStateOf(false) }
    var mediaTarget by remember { mutableStateOf<String?>(null) }
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    TabColumn {
        SectionCard("Processor & memory", icon = Icons.Outlined.Memory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    InfoRow("vCPUs", d.vcpus?.toString())
                    InfoRow("CPU mode", d.cpuMode)
                }
                IconButton(onClick = { editCpu = true }) { Icon(Icons.Outlined.Edit, "Edit vCPUs") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    InfoRow("Maximum", Format.kib(d.memoryKiB))
                    InfoRow("Current", Format.kib(d.currentMemoryKiB))
                }
                IconButton(onClick = { editMem = true }) { Icon(Icons.Outlined.Edit, "Edit memory") }
            }
            Row {
                InfoRow("Boot order", d.bootDevices.joinToString(" → ").ifEmpty { null }, Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
        }

        d.disks.forEach { disk ->
            val isCd = disk.device == "cdrom"
            SectionCard(
                title = if (isCd) "CD-ROM ${disk.target ?: ""}" else "Disk ${disk.target ?: ""}",
                icon = if (isCd) Icons.Outlined.Album else Icons.Outlined.Save,
            ) {
                InfoRow("Source", disk.source ?: if (isCd) "(empty)" else null)
                InfoRow("Bus", disk.bus)
                InfoRow("Format", disk.driverType)
                InfoRow("Type", disk.type)
                if (disk.readOnly) InfoRow("Read-only", "Yes")
                state.stats?.blocks?.firstOrNull { it.name == disk.target }?.let { b ->
                    b.capacity?.let { InfoRow("Capacity", Format.bytes(it)) }
                    b.allocation?.let { InfoRow("Allocated", Format.bytes(it)) }
                }
                if (isCd && disk.target != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        if (disk.source != null) OutlinedButton(onClick = { vm.changeMedia(disk.target, null) }) { Text("Eject") }
                        FilledTonalButton(onClick = { mediaTarget = disk.target }) { Text(if (disk.source == null) "Insert" else "Change") }
                    }
                }
            }
        }

        d.interfaces.forEachIndexed { idx, nic ->
            SectionCard("NIC ${nic.mac ?: idx.toString()}", icon = Icons.Outlined.Lan) {
                InfoRow("Type", nic.type)
                InfoRow("Source", nic.source)
                InfoRow("Model", nic.model)
                InfoRow("MAC", nic.mac)
                if (nic.target != null) InfoRow("Host device", nic.target)
                state.stats?.interfaces?.firstOrNull { it.name == nic.target }?.let { n ->
                    InfoRow("Received", Format.bytes(n.rxBytes))
                    InfoRow("Sent", Format.bytes(n.txBytes))
                }
            }
        }

        d.graphics.forEach { g ->
            SectionCard("Display ${g.type.uppercase()}", icon = Icons.Outlined.DesktopWindows) {
                InfoRow("Port", g.port?.toString() ?: if (g.autoport) "Auto" else null)
                InfoRow("Listen", g.listen ?: g.socket)
                InfoRow("Password", if (g.hasPassword) "Set" else "None")
                InfoRow("Video", d.videoModel)
                if (g.type == "spice") {
                    Text(
                        "SPICE isn't supported by the built-in viewer; add a VNC display to use the graphical console.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (editCpu) {
        TextInputDialog(
            title = "Virtual CPUs",
            label = "Count",
            initial = d.vcpus?.toString().orEmpty(),
            confirmLabel = "Apply",
            keyboardType = KeyboardType.Number,
            validate = { (it.toIntOrNull() ?: 0) in 1..512 },
            onConfirm = { vm.setVcpus(it.toInt()); editCpu = false },
            onDismiss = { editCpu = false },
        )
    }
    if (editMem) {
        TextInputDialog(
            title = "Memory (MiB)",
            label = "MiB",
            initial = d.memoryKiB?.let { (it / 1024).toString() }.orEmpty(),
            confirmLabel = "Apply",
            keyboardType = KeyboardType.Number,
            validate = { (it.toLongOrNull() ?: 0) >= 64 },
            onConfirm = { vm.setMemory(it.toLong()); editMem = false },
            onDismiss = { editMem = false },
        )
    }
    mediaTarget?.let { target ->
        TextInputDialog(
            title = "Insert media",
            label = "ISO path on the host",
            initial = "/var/lib/libvirt/images/",
            confirmLabel = "Insert",
            onConfirm = { vm.changeMedia(target, it); mediaTarget = null },
            onDismiss = { mediaTarget = null },
        )
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
        ConfirmDialog(
            title = "Revert to \"$s\"?",
            message = "The VM's current state will be discarded and replaced by the snapshot.",
            confirmLabel = "Revert",
            destructive = true,
            onConfirm = { vm.revertSnapshot(s); reverting = null },
            onDismiss = { reverting = null },
        )
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
}

@Composable
fun VmSettingsTab(state: VmUiState, vm: VmDetailViewModel) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var removeStorage by remember { mutableStateOf(false) }
    var showXml by remember { mutableStateOf(false) }
    val active = state.stats?.state?.isActive == true
    TabColumn {
        SectionCard("Boot options", icon = Icons.Outlined.Settings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start on host boot")
                    Text("Autostart this VM with libvirtd", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.info?.autostart == true, onCheckedChange = vm::setAutostart, enabled = state.info?.persistent == true)
            }
        }
        SectionCard("Manage", icon = Icons.Outlined.Edit) {
            TextButton(onClick = { renaming = true }, enabled = !active) { Text("Rename…") }
            if (active) {
                Text("Shut the VM down to rename it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showXml = !showXml }) {
                Icon(Icons.Outlined.Code, null); Spacer(Modifier.width(8.dp)); Text(if (showXml) "Hide XML" else "Show XML")
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
            OutlinedButton(onClick = { deleting = true }) { Text("Delete virtual machine", color = MaterialTheme.colorScheme.error) }
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
        ConfirmDialog(
            title = "Delete \"${state.name}\"?",
            message = if (active) "The VM is running and will be forced off first." else "This removes the VM from the host.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(removeStorage); deleting = false },
            onDismiss = { deleting = false },
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeStorage, onCheckedChange = { removeStorage = it })
                    Text("Also delete storage volumes")
                }
            },
        )
    }
}
