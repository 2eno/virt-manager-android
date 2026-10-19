package dev.virtmanager.android.ui.hw

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.libvirt.config.CharSettings
import dev.virtmanager.android.libvirt.config.ControllerSettings
import dev.virtmanager.android.libvirt.config.Device
import dev.virtmanager.android.libvirt.config.DeviceSettings
import dev.virtmanager.android.libvirt.config.DomainConfig
import dev.virtmanager.android.libvirt.config.DiskSettings
import dev.virtmanager.android.libvirt.config.FilesystemSettings
import dev.virtmanager.android.libvirt.config.GraphicsSettings
import dev.virtmanager.android.libvirt.config.HostdevSettings
import dev.virtmanager.android.libvirt.config.InputSettings
import dev.virtmanager.android.libvirt.config.InterfaceSettings
import dev.virtmanager.android.libvirt.config.PanicSettings
import dev.virtmanager.android.libvirt.config.RedirdevSettings
import dev.virtmanager.android.libvirt.config.RngSettings
import dev.virtmanager.android.libvirt.config.SmartcardSettings
import dev.virtmanager.android.libvirt.config.SoundSettings
import dev.virtmanager.android.libvirt.config.TpmSettings
import dev.virtmanager.android.libvirt.config.VideoSettings
import dev.virtmanager.android.libvirt.config.VsockSettings
import dev.virtmanager.android.libvirt.config.WatchdogSettings
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.theme.MonoStyle

/**
 * Page ids used in navigation: the fixed pages, "dev:<index>:<label>" for a device, or "add".
 * The label (unique within a VM) is what identifies the device: the index can be stale if the
 * list was tapped before it reloaded after an earlier change.
 */
object HwPages {
    val general = listOf("overview" to "Overview", "os" to "OS information", "cpus" to "CPUs", "memory" to "Memory", "boot" to "Boot options")
    fun device(index: Int, label: String) = "dev:$index:$label"
    const val ADD = "add"

    fun resolve(cfg: DomainConfig, page: String): Device? {
        if (!page.startsWith("dev:")) return null
        val rest = page.removePrefix("dev:")
        val index = rest.substringBefore(':').toIntOrNull()
        val label = rest.substringAfter(':', "")
        val devices = cfg.devices()
        return devices.firstOrNull { it.label == label } ?: devices.firstOrNull { it.index == index && label.isEmpty() }
    }
}

@Composable
private fun configViewModel(connectionId: String, vmName: String, key: String): ConfigViewModel {
    val app = app()
    return viewModel(key = "cfg-$connectionId-$vmName-$key") { ConfigViewModel(app, connectionId, vmName) }
}

/** virt-manager's left-hand list: general pages, every device, then "Add Hardware". */
@Composable
fun HardwareList(connectionId: String, vmName: String, onOpen: (String) -> Unit) {
    val vm = configViewModel(connectionId, vmName, "list")
    val state by vm.state.collectAsState()
    LifecycleResumeEffect(vm) {
        vm.reload() // pick up edits made on a page
        onPauseOrDispose { }
    }
    val cfg = state.config
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        ErrorBanner(state.error, onDismiss = vm::dismissError)
        if (cfg == null) {
            if (state.error == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (state.running) {
            Note("The VM is running: changes are saved to its configuration and take effect after the next shutdown and start.")
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            HwPages.general.forEachIndexed { i, (id, label) ->
                val subtitle = when (id) {
                    "overview" -> "${cfg.chipset.label} · ${cfg.firmware.label}"
                    "os" -> cfg.osInfoId?.substringAfter("://")
                    "cpus" -> "${cfg.currentVcpus} vCPU" + (cfg.cpuMode?.let { " · $it" } ?: "")
                    "memory" -> "${cfg.currentMemoryMiB} MiB"
                    "boot" -> cfg.bootEntries().filter { it.enabled }.joinToString(" → ") { it.label }.ifEmpty { null }
                    else -> null
                }
                HwRow(PageIcons.getValue(id), label, subtitle) { onOpen(id) }
                if (i < HwPages.general.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        val devices = cfg.devices()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            devices.forEachIndexed { i, d ->
                val el = cfg.deviceElement(d.index)
                HwRow(deviceIcon(el), d.label, deviceSubtitle(el?.let(DeviceSettings::read))) { onOpen(HwPages.device(d.index, d.label)) }
                if (i < devices.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { onOpen(HwPages.ADD) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Hardware")
        }
        Spacer(Modifier.height(48.dp))
    }
}

private fun deviceSubtitle(s: DeviceSettings?): String? = when (s) {
    is DiskSettings -> s.source ?: if (s.device == "cdrom") "No media" else null
    is InterfaceSettings -> (if (s.kind == "user") "Usermode" else "${s.kind}: ${s.source}") + " · ${s.model}" + if (!s.linkUp) " · link down" else ""
    is GraphicsSettings -> when (s.listen) {
        "none" -> "Listen: none"
        GraphicsSettings.SOCKET -> "UNIX socket"
        else -> "Listen ${s.listen}" + (s.port?.let { ":$it" } ?: "")
    }
    is VideoSettings -> "${s.heads} head" + if (s.accel3d) " · 3D" else ""
    is ControllerSettings -> s.model
    is InputSettings -> s.bus.uppercase()
    is FilesystemSettings -> "${s.source} → ${s.target}"
    is HostdevSettings -> null // the label already names the address or vendor:product
    else -> null
}

@Composable
private fun HwRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Holds the page's pending Apply action; [enabled] drives the button. */
@Stable
private class ApplyHolder {
    var enabled by mutableStateOf(false)
    var action: (() -> Unit)? = null
    fun set(a: ApplyAction) {
        action = a
        if (enabled != (a != null)) enabled = a != null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HwPageScreen(connectionId: String, vmName: String, page: String, onBack: () -> Unit) {
    val vm = configViewModel(connectionId, vmName, page)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val holder = remember { ApplyHolder() }
    var tab by remember { mutableIntStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    val isDevice = page.startsWith("dev:")
    val cfg = state.config
    val device = cfg?.let { HwPages.resolve(it, page) }
    val deviceIndex = device?.index
    val title = HwPages.general.firstOrNull { it.first == page }?.second ?: device?.label ?: "Device"

    LaunchedEffect(state.done) {
        if (state.done) {
            state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            vm.consumeDone(); vm.consumeMessage()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(vmName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (device?.removable == true) IconButton(onClick = { confirmRemove = true }, enabled = !state.busy) { Icon(Icons.Outlined.Delete, "Remove") }
                    TextButton(onClick = { holder.action?.invoke() }, enabled = holder.enabled && !state.busy) { Text("Apply") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(state.error, onDismiss = vm::dismissError)
            if (cfg == null) {
                if (state.error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            if (isDevice) {
                if (device == null) { Text("This device no longer exists.", Modifier.padding(16.dp)); return@Column }
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0; holder.set(null) }, text = { Text("Details") })
                    Tab(tab == 1, { tab = 1; holder.set(null) }, text = { Text("XML") })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (state.running) Note("Changes take effect after the VM is shut down and started again.")
                when {
                    page == "overview" -> OverviewPage(state, vm, holder::set)
                    page == "os" -> OsInfoPage(state, vm, holder::set)
                    page == "cpus" -> CpusPage(state, vm, holder::set)
                    page == "memory" -> MemoryPage(state, vm, holder::set)
                    page == "boot" -> BootPage(state, vm, holder::set)
                    deviceIndex != null && tab == 0 -> DeviceDetails(deviceIndex, state, vm, holder::set)
                    deviceIndex != null -> DeviceXml(deviceIndex, state, vm, holder::set)
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }

    if (confirmRemove && device != null) {
        ConfirmDialog(
            title = "Remove ${device.label}?",
            message = "The device is removed from the VM's configuration." +
                if (device.tag == "disk") " The disk image itself is kept." else "",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { confirmRemove = false; vm.apply("${device.label} removed") { removeDevice(device.index) } },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun DeviceDetails(index: Int, state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val cfg = state.config ?: return
    val el = cfg.deviceElement(index) ?: return
    val original = remember(state.version) { DeviceSettings.read(el) }
    if (original == null) {
        Text("This device has no settings form; edit it on the XML tab.", style = MaterialTheme.typography.bodyMedium)
        setApply(null)
        return
    }
    var draft by remember(state.version) { mutableStateOf(original) }
    val error = validate(draft, state)
    val wasCdrom = (original as? DiskSettings)?.device == "cdrom"
    val target = el.getElementsByTagName("target").item(0)?.attributes?.getNamedItem("dev")?.nodeValue
    setApply(if (draft == original || error != null) null else { {
        val d = draft
        vm.apply(
            "Saved",
            after = {
                // Like virt-manager, swap CD-ROM media in a running VM right away.
                if (state.running && wasCdrom && d is DiskSettings && target != null && d.source != (original as DiskSettings).source) {
                    changeMedia(cfg.name, target, d.source)
                }
            },
        ) { d.apply(deviceElement(index) ?: error("Device is gone"), this) }
    } })
    DeviceForm(draft, { draft = it }, state, vm, isNew = false)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DeviceXml(index: Int, state: ConfigState, vm: ConfigViewModel, setApply: (ApplyAction) -> Unit) {
    val original = remember(state.version) { state.config?.deviceXml(index).orEmpty() }
    var xml by remember(state.version) { mutableStateOf(original) }
    setApply(if (xml == original) null else { { vm.apply("XML saved") { replaceDevice(index, xml) } } })
    OutlinedTextField(xml, { xml = it }, textStyle = MonoStyle, modifier = Modifier.fillMaxWidth(), minLines = 8)
    Note("Edits only this device. Full VM XML is in Settings → Edit XML.")
}

// --- Add Hardware ----------------------------------------------------------------------------

private data class HwType(val label: String, val make: (ConfigState) -> DeviceSettings)

private val hwTypes = listOf(
    HwType("Storage") { DiskSettings("disk", source = null) },
    HwType("Controller") { ControllerSettings("usb", "qemu-xhci") },
    HwType("Network") { s -> InterfaceSettings("network", s.networks?.firstOrNull()?.name ?: "default") },
    HwType("Input") { InputSettings("tablet", "usb") },
    HwType("Graphics") { s -> GraphicsSettings(if (s.caps.values("graphics", "type")?.contains("spice") == true) "spice" else "vnc") },
    HwType("Sound") { SoundSettings("ich9") },
    HwType("Serial") { CharSettings("serial") },
    HwType("Parallel") { CharSettings("parallel") },
    HwType("Console") { CharSettings("console", targetType = "virtio") },
    HwType("Channel") { CharSettings("channel", "unix", targetName = "org.qemu.guest_agent.0") },
    HwType("USB Host Device") { HostdevSettings("usb") },
    HwType("PCI Host Device") { HostdevSettings("pci") },
    HwType("Video") { VideoSettings("virtio") },
    HwType("Watchdog") { s ->
        val existing = s.config?.devices()?.firstOrNull { it.tag == "watchdog" }?.let { s.config.deviceElement(it.index)?.getAttribute("action") }
        WatchdogSettings("i6300esb", existing?.ifEmpty { null } ?: "reset")
    },
    HwType("Filesystem") { FilesystemSettings() },
    HwType("Smartcard") { s -> SmartcardSettings(if (s.config?.hasSpice == true) "passthrough" else "host") },
    HwType("USB Redirection") { s -> RedirdevSettings(if (s.config?.hasSpice == true) "spicevmc" else "tcp") },
    HwType("TPM") { s -> TpmSettings(backend = if (s.caps.values("tpm", "backendModel")?.contains("emulator") == false) "passthrough" else "emulator") },
    HwType("RNG") { RngSettings() },
    HwType("Panic Notifier") { PanicSettings("isa") },
    HwType("VirtIO VSOCK") { VsockSettings() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHardwareScreen(connectionId: String, vmName: String, onBack: () -> Unit) {
    val vm = configViewModel(connectionId, vmName, HwPages.ADD)
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var type by remember { mutableStateOf<HwType?>(null) }
    var draft by remember { mutableStateOf<DeviceSettings?>(null) }
    // Storage: create a new image (virt-manager's default) or use an existing one.
    var createImage by remember { mutableStateOf(true) }
    var sizeGiB by remember { mutableStateOf("20") }
    var pool by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.done) {
        if (state.done) {
            state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            vm.consumeDone(); vm.consumeMessage()
            onBack()
        }
    }
    LaunchedEffect(Unit) { vm.loadNetworks(); vm.loadPools() }
    LaunchedEffect(state.pools) { if (pool == null) pool = state.pools?.firstOrNull { it.name == "default" }?.name ?: state.pools?.firstOrNull()?.name }

    val d = draft
    val newDisk = d is DiskSettings && d.device == "disk" && createImage
    val error = when {
        d == null -> null
        newDisk && ((sizeGiB.toIntOrNull() ?: 0) < 1 || pool == null) -> "Enter a size and choose a storage pool"
        newDisk -> null
        else -> validateNew(d, state)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(type?.let { "Add ${it.label}" } ?: "Add Hardware"); Text(vmName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { if (type != null) { type = null; draft = null } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (d != null) TextButton(onClick = {
                        val cfg = state.config ?: return@TextButton
                        if (newDisk) {
                            var created: Pair<String, String>? = null
                            var path = ""
                            vm.apply(
                                "Storage added",
                                before = {
                                    val p = pool!!
                                    val format = (d as DiskSettings).format ?: "qcow2"
                                    val volName = uniqueVolumeName(p, "${cfg.name}." + if (format == "qcow2") "qcow2" else "img")
                                    path = createVolume(p, volName, sizeGiB.toInt(), format)
                                    created = p to volName
                                },
                                rollback = { created?.let { (p, v) -> deleteVolume(p, v) } },
                            ) { addDevice((d as DiskSettings).copy(source = path).toXml(this)) }
                        } else {
                            vm.apply("${type!!.label} added") { addDevice(d.toXml(this)) }
                        }
                    }, enabled = error == null && !state.busy && state.config != null) { Text("Finish") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(state.error, onDismiss = vm::dismissError)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (type == null) {
                    if (state.running) Note("Hardware is added to the VM's configuration and appears after the next shutdown and start.")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        hwTypes.forEachIndexed { i, t ->
                            HwRow(typeIcon(t.label), t.label, null) { type = t; draft = t.make(state) }
                            if (i < hwTypes.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                    }
                } else if (d != null) {
                    if (d is DiskSettings && d.device == "disk") {
                        Choice(null, listOf(true, false), createImage, { createImage = it },
                            labels = { if (it) "Create a disk image for the virtual machine" else "Select or create custom storage" })
                        if (createImage) {
                            Field("Size (GiB)", sizeGiB, { sizeGiB = it }, number = true)
                            state.pools?.let { pools -> if (pools.isNotEmpty()) Choice("Pool", pools.map { it.name }, pool ?: "", { pool = it }) }
                        }
                    }
                    DeviceForm(
                        if (newDisk) (d as DiskSettings).copy(source = "(new image)") else d,
                        { draft = if (newDisk && it is DiskSettings) it.copy(source = null) else it },
                        state, vm, isNew = true,
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

private fun typeIcon(label: String) = when (label) {
    "Storage" -> deviceIconFor("disk")
    "Controller" -> deviceIconFor("controller")
    "Network" -> deviceIconFor("interface")
    "Input" -> deviceIconFor("input")
    "Graphics" -> deviceIconFor("graphics")
    "Sound" -> deviceIconFor("sound")
    "Serial", "Parallel", "Console", "Channel" -> deviceIconFor("serial")
    "USB Host Device", "PCI Host Device", "USB Redirection" -> deviceIconFor("hostdev")
    "Video" -> deviceIconFor("video")
    "Watchdog" -> deviceIconFor("watchdog")
    "Filesystem" -> deviceIconFor("filesystem")
    "Smartcard" -> deviceIconFor("smartcard")
    "TPM" -> deviceIconFor("tpm")
    "RNG" -> deviceIconFor("rng")
    "Panic Notifier" -> deviceIconFor("panic")
    else -> deviceIconFor("vsock")
}

private fun deviceIconFor(tag: String) = deviceIcon(
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement(tag),
)
