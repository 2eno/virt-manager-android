package dev.virtmanager.android.ui.hw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.config.CharSettings
import dev.virtmanager.android.libvirt.config.Chipset
import dev.virtmanager.android.libvirt.config.ControllerSettings
import dev.virtmanager.android.libvirt.config.DeviceSettings
import dev.virtmanager.android.libvirt.config.DiskSettings
import dev.virtmanager.android.libvirt.config.FilesystemSettings
import dev.virtmanager.android.libvirt.config.GraphicsSettings
import dev.virtmanager.android.libvirt.config.HostDevice
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

/** Why [s] can't be saved yet, or null. */
fun validate(s: DeviceSettings, state: ConfigState): String? = when (s) {
    is DiskSettings -> if (s.device != "cdrom" && s.source.isNullOrBlank()) "Choose a disk image or device" else null
    is InterfaceSettings -> when {
        s.kind != "user" && s.source.isBlank() -> "Choose a network source"
        !s.mac.isNullOrBlank() && !InterfaceSettings.isValidMac(s.mac) -> "MAC address must look like 52:54:00:12:34:56"
        else -> null
    }
    is GraphicsSettings -> when {
        s.listen != "none" && s.listen != GraphicsSettings.SOCKET && !s.autoport && (s.port == null || s.port !in 5900..65535) ->
            "Port must be between 5900 and 65535"
        s.password != null && s.password.isEmpty() -> "Enter a password, or untick Password"
        s.openGl && s.listen != "none" -> "OpenGL requires listen type None"
        else -> null
    }
    is FilesystemSettings -> when {
        !s.source.startsWith("/") -> "Source must be an absolute host path"
        s.target.isBlank() -> "Enter a mount tag"
        else -> null
    }
    is RedirdevSettings -> if (s.type == "spicevmc" && state.config?.hasSpice == false) "USB redirection via SPICE needs a SPICE display" else null
    is CharSettings -> if (s.tag == "channel" && s.type == "spicevmc" && state.config?.hasSpice == false) "The SPICE channel needs a SPICE display" else null
    is SmartcardSettings -> if (s.mode == "passthrough" && state.config?.hasSpice == false) "Smartcard passthrough needs a SPICE display" else null
    is TpmSettings -> if (s.backend == "passthrough" && s.path.isNullOrBlank()) "Enter the host TPM device" else null
    is HostdevSettings -> if (s.vendorId == null && s.pciAddress == null) "Choose a host device" else null
    else -> null
}

/** [validate] for a device about to be added, which also must not be there already. */
fun validateNew(s: DeviceSettings, state: ConfigState): String? = validate(s, state) ?: when (s) {
    is HostdevSettings -> {
        val host = state.hostDevices[s.type]?.firstOrNull { s.matches(it) }
        if (host != null && attachedHostdevs(state).any { it.matches(host) }) "This device is already attached to the VM" else null
    }
    else -> null
}

/**
 * The Details form for a device. [isNew] is true in Add Hardware, where some fields (e.g. the
 * device type of a disk) are chosen up front.
 */
@Composable
fun DeviceForm(s: DeviceSettings, onChange: (DeviceSettings) -> Unit, state: ConfigState, vm: ConfigViewModel, isNew: Boolean) {
    val caps = state.caps
    when (s) {
        is DiskSettings -> DiskForm(s, onChange, state, vm, isNew)
        is InterfaceSettings -> NicForm(s, onChange, state, vm)
        is InputSettings -> {
            val arch = state.config?.arch
            Choice("Type", InputSettings.types, s.type, { t ->
                val buses = InputSettings.busesFor(t, arch)
                onChange(s.copy(type = t, bus = if (s.bus in buses) s.bus else buses.first()))
            })
            Choice("Bus", (InputSettings.busesFor(s.type, arch) + s.bus).distinct(), s.bus, { onChange(s.copy(bus = it)) },
                labels = { when (it) { "usb" -> "USB"; "ps2" -> "PS/2"; else -> "VirtIO" } })
        }
        is GraphicsSettings -> {
            Choice("Type", caps.filter(listOf("vnc", "spice"), "graphics", "type", keep = s.type), s.type, { onChange(s.copy(type = it, openGl = false)) },
                labels = { if (it == "vnc") "VNC server" else "Spice server" })
            val listenOptions = listOf("127.0.0.1", "0.0.0.0", GraphicsSettings.SOCKET, "none")
            Choice("Listen", (listenOptions + s.listen).distinct(), s.listen, { onChange(s.copy(listen = it)) },
                labels = { when (it) {
                    "127.0.0.1" -> "Localhost only"; "0.0.0.0" -> "All interfaces"; GraphicsSettings.SOCKET -> "UNIX socket"; "none" -> "None"; else -> it
                } })
            if (s.listen != "none" && s.listen != GraphicsSettings.SOCKET) {
                CheckRow("Automatic port", s.autoport, { onChange(s.copy(autoport = it)) })
                if (!s.autoport) Field("Port", s.port?.toString().orEmpty(), { onChange(s.copy(port = it.toIntOrNull())) }, number = true)
            }
            // Remembered while the box is unticked, so ticking it again restores what was typed.
            var pw by remember { mutableStateOf(s.password.orEmpty()) }
            CheckRow("Password", s.password != null, { onChange(s.copy(password = if (it) pw else null)) })
            if (s.password != null) {
                Field("Password", s.password, { pw = it; onChange(s.copy(password = it)) },
                    supporting = if (s.type == "vnc" && s.password.length > 8) "VNC only uses the first 8 characters" else null)
            }
            if (s.type == "spice") CheckRow("OpenGL", s.openGl, { onChange(s.copy(openGl = it)) }, supporting = "Needs listen type None and a virtio video with 3D")
            if (s.listen == "0.0.0.0") Note("The display will be reachable from the network, not only through SSH.", warning = true)
        }
        is SoundSettings -> Choice("Model", SoundSettings.models, s.model, { onChange(SoundSettings(it)) })
        is CharSettings -> CharForm(s, onChange, state, isNew)
        is VideoSettings -> {
            val existingVideos = state.config?.devices()?.count { it.tag == "video" } ?: 0
            val base = if (isNew && existingVideos > 0) VideoSettings.secondaryModels else VideoSettings.models
            Choice("Model", caps.filter(base, "video", "modelType", keep = s.model), s.model, { onChange(s.copy(model = it, accel3d = false)) },
                labels = { when (it) { "virtio" -> "Virtio"; "qxl" -> "QXL"; "vga" -> "VGA"; else -> it.replaceFirstChar { c -> c.uppercase() } } })
            Field("Heads", s.heads.toString(), { onChange(s.copy(heads = (it.toIntOrNull() ?: 1).coerceIn(1, 16))) }, number = true)
            if (s.model == "virtio") CheckRow("3D acceleration", s.accel3d, { onChange(s.copy(accel3d = it)) })
        }
        is ControllerSettings -> {
            if (isNew) {
                Choice("Type", ControllerSettings.modelsByType.keys.toList(), s.type, {
                    onChange(ControllerSettings(it, ControllerSettings.modelsByType[it]?.firstOrNull()))
                }, labels = { when (it) { "usb" -> "USB"; "scsi" -> "SCSI"; "sata" -> "SATA"; "virtio-serial" -> "VirtIO Serial"; else -> it.uppercase() } })
            } else ReadOnlyLine("Type", s.type)
            val models = ControllerSettings.modelsByType[s.type].orEmpty()
            if (models.isNotEmpty()) Choice("Model", models, s.model ?: models.first(), { onChange(s.copy(model = it)) })
            else if (s.type == "pci") ReadOnlyLine("Model", s.model)
        }
        is RedirdevSettings -> {
            Choice("Type", listOf("spicevmc", "tcp"), s.type, { onChange(s.copy(type = it)) },
                labels = { if (it == "spicevmc") "Spice channel" else "TCP (usbredir server)" })
            if (s.type == "tcp") {
                Field("Host", s.host, { onChange(s.copy(host = it)) })
                Field("Port", s.port.toString(), { onChange(s.copy(port = it.toIntOrNull() ?: 0)) }, number = true)
            }
        }
        is RngSettings -> Choice("Host device", (RngSettings.devices + s.device).distinct(), s.device, { onChange(RngSettings(it)) })
        is WatchdogSettings -> {
            val chipset = state.config?.chipset
            val models = WatchdogSettings.models.filter { it != "itco" || chipset == Chipset.Q35 }
            Choice("Model", (models + s.model).distinct(), s.model, { onChange(s.copy(model = it)) })
            Choice("Action", WatchdogSettings.actions, s.action, { onChange(s.copy(action = it)) })
            Note("QEMU uses one action for all watchdogs; it is applied to every watchdog of this VM.")
        }
        is TpmSettings -> {
            Choice("Model", caps.filter(TpmSettings.models, "tpm", "model", keep = s.model), s.model, { onChange(s.copy(model = it)) })
            Choice("Backend", caps.filter(listOf("emulator", "passthrough"), "tpm", "backendModel", keep = s.backend), s.backend,
                { onChange(s.copy(backend = it)) }, labels = { if (it == "emulator") "Emulated (swtpm)" else "Passthrough" })
            if (s.backend == "passthrough") Field("Device", s.path ?: "/dev/tpm0", { onChange(s.copy(path = it)) })
            if (caps.values("tpm", "backendModel")?.contains("emulator") == false) Note("Emulated TPMs need swtpm installed on the host.")
        }
        is FilesystemSettings -> {
            Choice("Driver", caps.filter(listOf("virtiofs", "path"), "filesystem", "driverType", keep = s.driver), s.driver,
                { onChange(s.copy(driver = it)) }, labels = { if (it == "virtiofs") "virtiofs" else "virtio-9p" })
            Field("Source path", s.source, { onChange(s.copy(source = it)) }, supporting = "Directory on the host")
            Field("Target path", s.target, { onChange(s.copy(target = it)) }, supporting = "Mount tag inside the guest")
            CheckRow("Export filesystem as read-only mount", s.readOnly, { onChange(s.copy(readOnly = it)) })
            if (s.driver == "virtiofs") Note("virtiofs needs shared memory; it is enabled on the Memory page automatically.")
        }
        is PanicSettings -> Choice("Model", PanicSettings.models, s.model, { onChange(PanicSettings(it)) })
        is VsockSettings -> {
            CheckRow("Automatic CID", s.autoCid, { onChange(s.copy(autoCid = it)) })
            if (!s.autoCid) Field("CID", s.cid?.toString().orEmpty(), { onChange(s.copy(cid = it.toIntOrNull())) }, number = true)
        }
        is SmartcardSettings -> Choice("Mode", listOf("passthrough", "host"), s.mode, { onChange(SmartcardSettings(it)) },
            labels = { if (it == "passthrough") "Passthrough (Spice)" else "Host" })
        is HostdevSettings -> if (isNew) HostDeviceForm(s, onChange, state, vm) else AttachedHostDevice(s, state, vm)
    }
}

@Composable
private fun DiskForm(s: DiskSettings, onChange: (DeviceSettings) -> Unit, state: ConfigState, vm: ConfigViewModel, isNew: Boolean) {
    var browse by remember { mutableStateOf(false) }
    if (isNew) {
        Choice("Device type", listOf("disk", "cdrom", "floppy"), s.device, { dev ->
            onChange(s.copy(device = dev, bus = if (dev == "disk") "virtio" else if (dev == "floppy") "fdc" else defaultCdBus(state), format = if (dev == "disk") "qcow2" else "raw", source = null))
        }, labels = { when (it) { "disk" -> "Disk device"; "cdrom" -> "CDROM device"; else -> "Floppy device" } })
    } else ReadOnlyLine("Device type", s.device)
    if (s.sourceType == "file" || s.sourceType == "block") {
        Field(
            if (s.device == "cdrom") "Source path (empty = no media)" else "Source path",
            s.source.orEmpty(), { onChange(s.copy(source = it.ifBlank { null })) },
            trailing = { IconButton(onClick = { browse = true }) { Icon(Icons.Outlined.FolderOpen, "Browse") } },
        )
    } else ReadOnlyLine("Source", s.source)
    val buses = when (s.device) {
        "floppy" -> listOf("fdc")
        "cdrom" -> listOf("sata", "scsi", "usb", "ide")
        else -> DiskSettings.buses
    }.filter { it != "ide" || state.config?.chipset == Chipset.I440FX || s.bus == "ide" }
    Choice("Disk bus", state.caps.filter(buses, "disk", "bus", keep = s.bus), s.bus, { onChange(s.copy(bus = it)) },
        labels = { when (it) { "virtio" -> "VirtIO"; "fdc" -> "Floppy"; else -> it.uppercase() } })
    if (s.bus == "scsi" && state.config?.devices()?.none { it.label == "Controller SCSI" } == true) {
        Note("A SCSI disk needs a SCSI controller: add one via Add Hardware → Controller.", warning = true)
    }
    if (s.device == "disk") {
        Choice("Format", (DiskSettings.formats + listOfNotNull(s.format)).distinct(), s.format ?: "raw", { onChange(s.copy(format = it)) })
    }
    FormSection("Performance & advanced") {
        Choice("Cache mode", listOf<String?>(null) + DiskSettings.caches, s.cache, { onChange(s.copy(cache = it)) }, labels = { it ?: "Hypervisor default" })
        Choice("Discard mode", listOf(null, "unmap", "ignore"), s.discard, { onChange(s.copy(discard = it)) }, labels = { it ?: "Hypervisor default" })
        if (s.device != "cdrom") {
            CheckRow("Read-only", s.readOnly, { onChange(s.copy(readOnly = it)) })
            CheckRow("Shareable", s.shareable, { onChange(s.copy(shareable = it)) })
        }
        Field("Serial", s.serial.orEmpty(), { onChange(s.copy(serial = it.ifBlank { null })) })
    }
    if (!isNew && state.running && s.device == "cdrom") Note("Changing the CD-ROM media takes effect immediately.")
    if (browse) VolumePicker(state, vm, isoOnly = s.device == "cdrom", onPick = { onChange(s.copy(source = it)); browse = false }, onDismiss = { browse = false })
}

private fun defaultCdBus(state: ConfigState) = if (state.config?.chipset == Chipset.I440FX) "ide" else "sata"

@Composable
fun VolumePicker(state: ConfigState, vm: ConfigViewModel, isoOnly: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPools() }
    var pool by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.pools) { if (pool == null) pool = state.pools?.firstOrNull { it.name == "default" }?.name ?: state.pools?.firstOrNull()?.name }
    LaunchedEffect(pool) { pool?.let(vm::loadVolumes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a volume") },
        text = {
            Column {
                val pools = state.pools
                if (pools == null) CircularProgressIndicator() else {
                    if (pools.size > 1) Choice("Pool", pools.map { it.name }, pool ?: "", { pool = it })
                    val vols = pool?.let { state.volumes[it] }
                    when {
                        vols == null -> CircularProgressIndicator(Modifier.padding(8.dp))
                        else -> vols.filter { !isoOnly || it.name.endsWith(".iso", true) }.ifEmpty { null }?.forEach { v ->
                            TextButton(onClick = { onPick(v.path) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(v.name)
                                    Text(listOfNotNull(v.capacity, v.path).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } ?: Text("No ${if (isoOnly) "ISO images" else "volumes"} in this pool.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NicForm(s: InterfaceSettings, onChange: (DeviceSettings) -> Unit, state: ConfigState, vm: ConfigViewModel) {
    LaunchedEffect(Unit) { vm.loadNetworks() }
    Choice("Network source", InterfaceSettings.kinds, s.kind, { k ->
        onChange(s.copy(kind = k, source = when (k) {
            "network" -> state.networks?.firstOrNull()?.name.orEmpty()
            "bridge" -> state.bridges?.firstOrNull().orEmpty()
            else -> ""
        }))
    }, labels = { when (it) { "network" -> "Virtual network"; "bridge" -> "Bridge device"; "user" -> "Usermode networking"; else -> "Macvtap device" } })
    when (s.kind) {
        "network" -> {
            val nets = state.networks
            if (nets == null) CircularProgressIndicator(Modifier.padding(8.dp))
            else Choice(null, (nets.map { it.name } + s.source).filter { it.isNotEmpty() }.distinct(), s.source, { onChange(s.copy(source = it)) },
                labels = { n -> n + if (nets.firstOrNull { it.name == n }?.active == false) " (inactive)" else "" })
        }
        "bridge" -> {
            val bridges = state.bridges
            if (!bridges.isNullOrEmpty()) Choice(null, (bridges + s.source).filter { it.isNotEmpty() }.distinct(), s.source, { onChange(s.copy(source = it)) })
            Field("Bridge name", s.source, { onChange(s.copy(source = it)) },
                error = if (bridges != null && s.source.isNotBlank() && s.source !in bridges) "Not a bridge on this host" else null)
        }
        "direct" -> {
            Field("Host device", s.source, { onChange(s.copy(source = it)) }, supporting = "e.g. eth0")
            Choice("Source mode", (InterfaceSettings.directModes + s.directMode).distinct(), s.directMode, { onChange(s.copy(directMode = it)) },
                labels = { it.replaceFirstChar { c -> c.uppercase() } })
            Note("With macvtap in bridge mode the host itself can't reach the guest over this interface.")
        }
        else -> Note("Usermode networking (SLIRP/passt): outgoing connections work without any host setup, but the guest isn't reachable from outside.")
    }
    Choice("Device model", (InterfaceSettings.models + s.model).distinct(), s.model, { onChange(s.copy(model = it)) })
    Field("MAC address", s.mac.orEmpty(), { onChange(s.copy(mac = it.ifBlank { null })) }, supporting = "Empty: generated by libvirt")
    CheckRow("Link state: active", s.linkUp, { onChange(s.copy(linkUp = it)) })
}

@Composable
private fun CharForm(s: CharSettings, onChange: (DeviceSettings) -> Unit, state: ConfigState, isNew: Boolean) {
    when (s.tag) {
        "channel" -> {
            Choice("Name", CharSettings.channels.map { it.first } + listOfNotNull(s.targetName).filter { n -> CharSettings.channels.none { it.first == n } },
                s.targetName ?: "", { name -> onChange(s.copy(targetName = name, type = CharSettings.channels.firstOrNull { it.first == name }?.second ?: s.type)) },
                labels = { when (it) { "org.qemu.guest_agent.0" -> "QEMU guest agent"; "com.redhat.spice.0" -> "Spice agent"; "org.spice-space.webdav.0" -> "Spice WebDAV"; else -> it } })
            ReadOnlyLine("Device type", s.type)
        }
        "console" -> {
            Choice("Device type", listOf("pty"), s.type, { onChange(s.copy(type = it)) }, labels = { "Pseudo TTY (pty)" })
            Choice("Target", listOf("virtio", "serial"), s.targetType ?: "serial", { onChange(s.copy(targetType = it)) })
        }
        else -> {
            Choice("Device type", listOf("pty"), s.type, { onChange(s.copy(type = it)) }, labels = { "Pseudo TTY (pty)" })
            if (!isNew) ReadOnlyLine("Target", s.targetType)
        }
    }
}

@Composable
private fun HostDeviceForm(s: HostdevSettings, onChange: (DeviceSettings) -> Unit, state: ConfigState, vm: ConfigViewModel) {
    LaunchedEffect(s.type) { vm.loadHostDevices(s.type) }
    val list = state.hostDevices[s.type]
    // Devices this VM already has; assigning one twice is refused by libvirt.
    val attached = remember(state.version) { attachedHostdevs(state) }
    var query by remember { mutableStateOf("") }
    when {
        list == null -> CircularProgressIndicator(Modifier.padding(8.dp))
        list.isEmpty() -> Text("No ${s.type.uppercase()} devices found on the host.", style = MaterialTheme.typography.bodyMedium)
        else -> {
            if (list.size > 6) {
                Field("Search", query, { query = it }, supporting = "Name, vendor, class, driver or address")
            }
            val shown = list.filter { d ->
                query.isBlank() || listOfNotNull(d.description, d.driver, d.name).any { it.contains(query.trim(), ignoreCase = true) }
            }
            Text(
                "${shown.size} of ${list.size} ${s.type.uppercase()} devices",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            val unlisted = list.count { !it.inLibvirt }
            if (unlisted > 0) {
                Note(
                    (if (unlisted == 1) "1 device is" else "$unlisted devices are") + " missing from libvirt's own device " +
                        "list and shown from the host's sysfs. libvirt skips devices whose configuration it can't read, " +
                        "typically a GPU that stopped answering after a failed reset. They can still be assigned; " +
                        "libvirt checks them when the VM starts. Restarting libvirt (virtnodedevd or libvirtd) makes " +
                        "it read them again.",
                )
            }
            shown.forEach { d ->
                val already = attached.any { it.matches(d) }
                val selected = !already && s.matches(d)
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected, enabled = !already, role = Role.RadioButton) { onChange(d.settings) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = !already)
                    Spacer(Modifier.width(12.dp))
                    HostDeviceText(d, dim = already || d.isBridge, extra = listOfNotNull(
                        "Already attached to this VM".takeIf { already },
                        "Bridge — usually not useful to assign".takeIf { d.isBridge },
                    ))
                }
            }
            val chosen = list.firstOrNull { s.matches(it) && !attached.any { a -> a.matches(it) } }
            if (chosen != null && !chosen.responding) NotRespondingNote()
            if (chosen != null && chosen.groupMembers.isNotEmpty()) {
                Note(
                    "IOMMU group ${chosen.iommuGroup} also contains ${chosen.groupMembers.joinToString()}. " +
                        "Devices in one group can usually only be passed through together.",
                    warning = true,
                )
            }
            if (s.type == "pci") Note("The device is detached from its host driver while the VM runs (managed mode).")
        }
    }
}

@Composable
private fun NotRespondingNote() = Note(
    "This device doesn't respond (its configuration reads as all ones). AMD GPUs often end up like this after " +
        "a VM used them (the \"reset bug\"): a VM can't start with it until the host is rebooted or the card is " +
        "reset, e.g. with the vendor-reset kernel module.",
    warning = true,
)

/** Two lines like lspci: what the device is, then address, driver and IOMMU group. */
@Composable
private fun HostDeviceText(d: HostDevice, dim: Boolean = false, extra: List<String> = emptyList()) {
    // Without the host's ID database there is no product name; the class says what it is then.
    val title = listOfNotNull(d.vendorName, d.productName ?: d.className).joinToString(" ").ifBlank { d.name }
    val ids = if (d.vendorId != null && d.productId != null) "${d.vendorId.removePrefix("0x")}:${d.productId.removePrefix("0x")}" else null
    val details = listOfNotNull(
        if (d.type == "pci") d.address else "Bus ${d.address.substringBefore(':')} Device ${d.address.substringAfter(':')}",
        d.className?.takeIf { d.productName != null },
        ids,
        d.driver?.let { "driver $it" },
        d.iommuGroup?.let { "IOMMU group $it" },
    ).joinToString(" · ")
    val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val problems = listOfNotNull(
            "Not in libvirt's device list".takeIf { !d.inLibvirt },
            "Not responding".takeIf { !d.responding },
        )
        (extra + problems).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
}

private fun attachedHostdevs(state: ConfigState): List<HostdevSettings> {
    val cfg = state.config ?: return emptyList()
    return cfg.devices().filter { it.tag == "hostdev" }.mapNotNull { d -> cfg.deviceElement(d.index)?.let(HostdevSettings::read) }
}

/** An attached host device: what it is on the host, if it is still there. */
@Composable
private fun AttachedHostDevice(s: HostdevSettings, state: ConfigState, vm: ConfigViewModel) {
    LaunchedEffect(s.type) { vm.loadHostDevices(s.type) }
    val host = state.hostDevices[s.type]?.firstOrNull { s.matches(it) }
    ReadOnlyLine("Type", if (s.type == "pci") "PCI host device" else "USB host device")
    when {
        host != null -> {
            Spacer(Modifier.height(4.dp))
            HostDeviceText(host)
            if (!host.responding) NotRespondingNote()
            if (host.groupMembers.isNotEmpty()) {
                Note("IOMMU group ${host.iommuGroup} also contains ${host.groupMembers.joinToString()}.")
            }
        }
        s.type == "usb" -> {
            ReadOnlyLine("Vendor", s.vendorId)
            ReadOnlyLine("Product", s.productId)
            if (s.usbBus != null) ReadOnlyLine("Address", "Bus ${s.usbBus} Device ${s.usbDevice}")
        }
        else -> ReadOnlyLine("Address", s.pciAddress)
    }
    if (state.hostDevices[s.type] != null && host == null) {
        Note("This device isn't present on the host right now; the VM won't start until it is, or remove it.", warning = true)
    }
}
