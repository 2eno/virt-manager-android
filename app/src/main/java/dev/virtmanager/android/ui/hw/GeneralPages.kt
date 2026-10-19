package dev.virtmanager.android.ui.hw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.config.BootEntry
import dev.virtmanager.android.libvirt.config.Chipset
import dev.virtmanager.android.libvirt.config.CpuTopology
import dev.virtmanager.android.libvirt.config.FirmwareKind

/** What a page offers to the screen's Apply button: null while there is nothing (valid) to save. */
typealias ApplyAction = (() -> Unit)?

@Composable
fun OverviewPage(state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    var title by remember(state.version) { mutableStateOf(cfg.title.orEmpty()) }
    var description by remember(state.version) { mutableStateOf(cfg.description.orEmpty()) }
    var chipset by remember(state.version) { mutableStateOf(cfg.chipset) }
    var firmware by remember(state.version) { mutableStateOf(cfg.firmware) }
    val off = !state.running

    val dirty = title != cfg.title.orEmpty() || description != cfg.description.orEmpty() ||
        chipset != cfg.chipset || firmware != cfg.firmware
    setApply(if (!dirty) null else { {
        vm.apply("Overview saved") {
            this.title = title.ifBlank { null }
            this.description = description.ifBlank { null }
            if (chipset != this.chipset) setChipset(chipset)
            if (firmware != this.firmware) this.firmware = firmware
        }
    } })

    FormSection("Basic details") {
        ReadOnlyLine("Name", cfg.name)
        ReadOnlyLine("UUID", cfg.uuid)
        ReadOnlyLine("Status", state.state.label)
        Field("Title", title, { title = it })
        Field("Description", description, { description = it }, singleLine = false, minLines = 3)
    }
    FormSection("Hypervisor details") {
        ReadOnlyLine("Hypervisor", cfg.domainType?.uppercase())
        ReadOnlyLine("Architecture", cfg.arch)
        ReadOnlyLine("Emulator", cfg.emulator)
        val chipsets = if (cfg.chipset == Chipset.VIRT) listOf(Chipset.VIRT) else listOf(Chipset.Q35, Chipset.I440FX)
        Choice("Chipset", chipsets, chipset, { chipset = it }, enabled = off, labels = { it.label })
        val firmwares = if (state.caps.firmwareEfi == false) listOf(FirmwareKind.BIOS) else FirmwareKind.entries
        Choice("Firmware", firmwares, firmware, { firmware = it }, enabled = off, labels = { it.label })
        if (!off) Note("Shut the VM down to change the chipset or firmware.")
        else if (chipset != cfg.chipset || firmware != cfg.firmware) {
            Note("Changing the chipset or firmware after an OS is installed can make it unbootable.", warning = true)
        }
    }
}

@Composable
fun OsInfoPage(state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    LaunchedEffect(Unit) { vm.loadOsVariants() }
    var selected by remember(state.version) { mutableStateOf(cfg.osInfoId) }
    var query by remember { mutableStateOf("") }
    setApply(if (selected == cfg.osInfoId) null else { { vm.apply("Operating system saved") { osInfoId = selected } } })

    val variants = state.osVariants
    FormSection("Operating system") {
        ReadOnlyLine("Current", variants?.firstOrNull { it.id == selected }?.name ?: selected ?: "Unknown")
        Field("Search", query, { query = it }, supporting = "e.g. AlmaLinux, Debian, Windows")
        when {
            variants == null -> CircularProgressIndicator(Modifier.padding(8.dp))
            else -> {
                val q = query.trim().lowercase()
                variants.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.shortId.contains(q) }
                    .take(if (q.isEmpty()) 40 else 200)
                    .forEach { os ->
                        Row(
                            Modifier.fillMaxWidth().selectable(os.id == selected, role = Role.RadioButton) { selected = os.id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = os.id == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(os.name)
                                Text(os.shortId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
            }
        }
    }
}

private val cpuModes = listOf<String?>(null, "host-passthrough", "host-model", "maximum", "custom")
private fun cpuModeLabel(m: String?) = when (m) {
    null -> "Hypervisor default"
    "host-passthrough" -> "Copy host CPU (host-passthrough)"
    "host-model" -> "host-model"
    "maximum" -> "maximum"
    else -> "Named model"
}

@Composable
fun CpusPage(state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    LaunchedEffect(Unit) { vm.loadCpuModels() }
    var current by remember(state.version) { mutableStateOf(cfg.currentVcpus.toString()) }
    var max by remember(state.version) { mutableStateOf(cfg.maxVcpus.toString()) }
    var mode by remember(state.version) { mutableStateOf(cfg.cpuMode) }
    var model by remember(state.version) { mutableStateOf(cfg.cpuModel.orEmpty()) }
    val topo = cfg.topology
    var manualTopology by remember(state.version) { mutableStateOf(topo != null) }
    var sockets by remember(state.version) { mutableStateOf((topo?.sockets ?: 1).toString()) }
    var cores by remember(state.version) { mutableStateOf((topo?.cores ?: cfg.maxVcpus).toString()) }
    var threads by remember(state.version) { mutableStateOf((topo?.threads ?: 1).toString()) }

    val maxN = max.toIntOrNull() ?: 0
    val curN = current.toIntOrNull() ?: 0
    val newTopo = if (manualTopology) CpuTopology(sockets.toIntOrNull() ?: 0, 1, cores.toIntOrNull() ?: 0, threads.toIntOrNull() ?: 0) else null
    val vcpuError = when {
        maxN < 1 -> "At least 1"
        curN !in 1..maxN -> "Between 1 and the maximum ($maxN)"
        else -> null
    }
    val topoError = if (newTopo != null && newTopo.total != maxN) "Sockets × cores × threads = ${newTopo.total}, must equal $maxN" else null
    val modelError = if (mode == "custom" && model.isBlank()) "Choose a CPU model" else null
    val dirty = curN != cfg.currentVcpus || maxN != cfg.maxVcpus || mode != cfg.cpuMode ||
        (mode == "custom" && model != cfg.cpuModel.orEmpty()) || newTopo != topo
    setApply(if (!dirty || vcpuError != null || topoError != null || modelError != null) null else { {
        vm.apply("CPUs saved") {
            topology = null
            setVcpus(curN, maxN)
            cpuMode = mode
            if (mode == "custom") cpuModel = model
            topology = newTopo
        }
    } })

    FormSection("vCPU allocation") {
        Field("Current allocation", current, { current = it }, number = true, error = vcpuError)
        Field("Maximum allocation", max, { max = it }, number = true, supporting = "vCPUs above the current allocation can be hot-plugged later")
    }
    FormSection("Configuration") {
        Choice(null, cpuModes, mode, { mode = it }, labels = ::cpuModeLabel)
        if (mode == "custom") {
            Field("Model", model, { model = it }, error = modelError)
            val models = state.cpuModels.orEmpty().filter { model.isBlank() || it.contains(model, ignoreCase = true) }.take(30)
            if (models.isNotEmpty()) Choice(null, models, model, { model = it })
        }
    }
    FormSection("Topology") {
        CheckRow("Manually set CPU topology", manualTopology, { manualTopology = it })
        if (manualTopology) {
            Row {
                Column(Modifier.weight(1f)) { Field("Sockets", sockets, { sockets = it }, number = true) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Field("Cores", cores, { cores = it }, number = true) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Field("Threads", threads, { threads = it }, number = true) }
            }
            if (topoError != null) Text(topoError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MemoryPage(state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    var current by remember(state.version) { mutableStateOf(cfg.currentMemoryMiB.toString()) }
    var max by remember(state.version) { mutableStateOf(cfg.maxMemoryMiB.toString()) }
    var shared by remember(state.version) { mutableStateOf(cfg.sharedMemory) }
    val cur = current.toLongOrNull() ?: 0
    val mx = max.toLongOrNull() ?: 0
    val error = when {
        mx < 64 -> "At least 64 MiB"
        cur !in 64..mx -> "Between 64 MiB and the maximum"
        else -> null
    }
    val dirty = cur != cfg.currentMemoryMiB || mx != cfg.maxMemoryMiB || shared != cfg.sharedMemory
    setApply(if (!dirty || error != null) null else { { vm.apply("Memory saved") { setMemory(cur, mx); sharedMemory = shared } } })

    FormSection("Memory") {
        Field("Current allocation (MiB)", current, { current = it }, number = true, error = error)
        Field("Maximum allocation (MiB)", max, { max = it }, number = true)
        CheckRow("Enable shared memory", shared, { shared = it }, supporting = "Required for virtiofs filesystem sharing")
    }
}

@Composable
fun BootPage(state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    val initial = remember(state.version) { cfg.bootEntries() }
    var entries by remember(state.version) { mutableStateOf(initial) }
    var menu by remember(state.version) { mutableStateOf(cfg.bootMenu) }
    var autostart by remember(state.version, state.autostart) { mutableStateOf(state.autostart) }
    val enabled = entries.filter { it.enabled }.map { it.index }
    val dirty = entries != initial || menu != cfg.bootMenu || autostart != state.autostart
    setApply(if (!dirty) null else { {
        val start = autostart
        vm.apply("Boot options saved", after = { if (start != state.autostart) setAutostart(cfg.name, start) }) {
            setBootOrder(enabled)
            bootMenu = menu
        }
    } })

    FormSection("Autostart") {
        CheckRow("Start virtual machine on host boot up", autostart, { autostart = it })
    }
    FormSection("Boot device order") {
        CheckRow("Enable boot menu", menu, { menu = it })
        if (entries.isEmpty()) Text("No bootable devices.", style = MaterialTheme.typography.bodySmall)
        entries.forEachIndexed { i, e ->
            BootRow(
                e,
                onToggle = { on -> entries = reorder(entries.toMutableList().also { it[i] = e.copy(enabled = on) }) },
                onUp = if (i > 0 && e.enabled) { { entries = entries.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } } } else null,
                onDown = if (i < entries.lastIndex && entries[i + 1].enabled && e.enabled) {
                    { entries = entries.toMutableList().also { java.util.Collections.swap(it, i, i + 1) } }
                } else null,
            )
        }
        if (enabled.isEmpty()) Note("No boot device is enabled; the VM won't find anything to boot.", warning = true)
    }
}

/** Keeps enabled entries first (in their order), like virt-manager's list. */
private fun reorder(list: List<BootEntry>) = list.filter { it.enabled } + list.filterNot { it.enabled }

@Composable
private fun BootRow(e: BootEntry, onToggle: (Boolean) -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = e.enabled, onCheckedChange = onToggle)
        Text(e.label, Modifier.weight(1f))
        IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
        IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
    }
}
