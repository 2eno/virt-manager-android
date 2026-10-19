package dev.virtmanager.android.ui.create

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.libvirt.Firmware
import dev.virtmanager.android.libvirt.NetworkSource
import dev.virtmanager.android.libvirt.NewVmSpec
import dev.virtmanager.android.libvirt.OsFamily
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.components.InfoRow
import dev.virtmanager.android.ui.components.SectionCard
import dev.virtmanager.android.ui.connections.SwitchRow
import dev.virtmanager.android.ui.hw.CheckRow
import kotlin.math.roundToInt

private val stepTitles = listOf("Install media", "Memory & CPU", "Storage", "Network & finish")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewVmScreen(connectionId: String, onDone: (Created?) -> Unit) {
    val app = app()
    val vm: NewVmViewModel = viewModel(key = "new-$connectionId") { NewVmViewModel(app, connectionId) }
    val env by vm.env.collectAsState()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var os by rememberSaveable { mutableStateOf(OsFamily.LINUX) }
    var media by rememberSaveable { mutableStateOf("") }        // "" = none
    var memMiB by rememberSaveable { mutableFloatStateOf(2048f) }
    var cpus by rememberSaveable { mutableFloatStateOf(2f) }
    var createDisk by rememberSaveable { mutableStateOf(true) }
    var diskGiB by rememberSaveable { mutableFloatStateOf(20f) }
    var pool by rememberSaveable { mutableStateOf<String?>(null) }
    var network by rememberSaveable { mutableStateOf<String?>(null) } // "net:<name>", "br:<bridge>" or "none"
    var bridge by rememberSaveable { mutableStateOf("") }
    var uefi by rememberSaveable { mutableStateOf(false) }
    var startAfter by rememberSaveable { mutableStateOf(true) }
    var customize by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(env.loading) {
        if (!env.loading) {
            if (pool == null) pool = env.pools.firstOrNull { it.name == "default" }?.name ?: env.pools.firstOrNull()?.name
            if (network == null) network = when {
                env.session -> "user"
                else -> env.networks.firstOrNull { it.name == "default" }?.let { "net:${it.name}" }
                    ?: env.networks.firstOrNull()?.let { "net:${it.name}" } ?: "user"
            }
            if (bridge.isEmpty()) bridge = env.bridges?.firstOrNull().orEmpty()
            if (env.arch == "aarch64") uefi = true
        }
    }
    LaunchedEffect(env.created) { env.created?.let { onDone(it) } }
    // While the VM is being created, stay here so the outcome (or its error) is shown.
    BackHandler(enabled = env.progress != null) {}
    LaunchedEffect(os) {
        if (os == OsFamily.WINDOWS) {
            if (memMiB < 4096) memMiB = 4096f
            if (diskGiB < 64) diskGiB = 64f
            uefi = true
        }
    }

    val nameError = when {
        name.isBlank() -> null
        !DomainXmlBuilder.isValidName(name) -> "Use letters, digits, '.', '-', '_' or '+'"
        name in env.existingNames -> "A VM with this name already exists"
        else -> null
    }
    val bridgeError = when {
        bridge.isBlank() -> "Enter the name of a bridge on the host"
        env.bridges != null && bridge.trim() !in env.bridges!! ->
            if (env.bridges!!.isEmpty()) "No bridges found on the host; use a virtual network instead"
            else "Not a bridge on this host"
        else -> null
    }
    val stepValid = when (step) {
        0 -> name.isNotBlank() && nameError == null
        2 -> !createDisk || pool != null
        3 -> network?.startsWith("br:") != true || bridgeError == null
        else -> true
    }

    fun buildSpec() = NewVmSpec(
        name = name.trim(),
        memoryMiB = memMiB.roundToInt().toLong(),
        vcpus = cpus.roundToInt(),
        arch = env.arch,
        firmware = if (uefi) Firmware.UEFI else Firmware.BIOS,
        installMediaPath = media.ifBlank { null },
        network = when {
            network == "none" -> NetworkSource.None
            network == "user" -> NetworkSource.User
            network?.startsWith("br:") == true -> NetworkSource.Bridge(bridge.trim())
            network?.startsWith("net:") == true -> NetworkSource.Virtual(network!!.removePrefix("net:"))
            else -> NetworkSource.None
        },
        osFamily = os,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New virtual machine")
                        Text(
                            "Step ${step + 1} of ${stepTitles.size} · ${stepTitles[step]}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (step > 0) step-- else onDone(null) }, enabled = env.progress == null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { if (step > 0) step-- else onDone(null) }, enabled = env.progress == null, modifier = Modifier.weight(1f)) {
                        Text(if (step == 0) "Cancel" else "Back")
                    }
                    if (step < stepTitles.lastIndex) {
                        Button(onClick = { step++ }, enabled = stepValid && !env.loading, modifier = Modifier.weight(1f)) { Text("Next") }
                    } else {
                        Button(
                            onClick = {
                                vm.create(buildSpec(), if (createDisk) diskGiB.roundToInt() else null, pool, startAfter, customize)
                            },
                            enabled = env.progress == null && stepValid,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (customize) "Customize" else "Create") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            StepIndicator(step)
            ErrorBanner(env.error, onDismiss = vm::dismissError)
            if (env.loading || env.progress != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(env.progress ?: "Querying host…")
                    }
                }
                return@Column
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
                },
                label = "wizard",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (s) {
                        0 -> {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.replace(" ", "-") },
                                label = { Text("Name") },
                                isError = nameError != null,
                                supportingText = { Text(nameError ?: "Shown in the VM list and used for the disk image name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Operating system", style = MaterialTheme.typography.titleSmall)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                OsFamily.entries.forEachIndexed { i, f ->
                                    SegmentedButton(
                                        selected = os == f,
                                        onClick = { os = f },
                                        shape = SegmentedButtonDefaults.itemShape(i, OsFamily.entries.size),
                                    ) { Text(f.label, maxLines = 1) }
                                }
                            }
                            Text("Install media", style = MaterialTheme.typography.titleSmall)
                            ChoiceRow("No install media", "Boot from the network or an existing disk", media.isBlank()) { media = "" }
                            env.isos.forEach { iso ->
                                ChoiceRow(iso.name, iso.path, media == iso.path) { media = iso.path }
                            }
                            OutlinedTextField(
                                value = if (env.isos.any { it.path == media }) "" else media,
                                onValueChange = { media = it },
                                label = { Text("Or enter an ISO path on the host") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (env.isos.isEmpty()) {
                                Text(
                                    "No ISO images were found in the host's storage pools. Copy one to /var/lib/libvirt/images and refresh the pool.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        1 -> {
                            SectionCard("Memory") {
                                val maxMem = env.hostMemMiB.coerceAtLeast(1024).toFloat()
                                Text("${memMiB.roundToInt()} MiB", style = MaterialTheme.typography.headlineSmall)
                                Slider(
                                    value = memMiB.coerceIn(256f, maxMem),
                                    onValueChange = { memMiB = (it / 256).roundToInt() * 256f },
                                    valueRange = 256f..maxMem,
                                )
                                Text("Up to ${env.hostMemMiB} MiB available on the host", style = MaterialTheme.typography.bodySmall)
                            }
                            SectionCard("CPUs") {
                                val maxCpu = env.hostCpus.coerceAtLeast(1).toFloat()
                                Text("${cpus.roundToInt()} vCPU", style = MaterialTheme.typography.headlineSmall)
                                if (maxCpu > 1) {
                                    Slider(
                                        value = cpus.coerceIn(1f, maxCpu),
                                        onValueChange = { cpus = it.roundToInt().toFloat() },
                                        valueRange = 1f..maxCpu,
                                        steps = (maxCpu - 2).toInt().coerceAtLeast(0),
                                    )
                                }
                                Text("${env.hostCpus} logical CPUs on the host", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        2 -> {
                            SwitchRow("Create a disk image", "A new qcow2 volume is allocated on demand", createDisk) { createDisk = it }
                            if (createDisk) {
                                SectionCard("Size") {
                                    Text("${diskGiB.roundToInt()} GiB", style = MaterialTheme.typography.headlineSmall)
                                    Slider(value = diskGiB, onValueChange = { diskGiB = it.roundToInt().toFloat() }, valueRange = 1f..1024f)
                                }
                                Text("Storage pool", style = MaterialTheme.typography.titleSmall)
                                if (env.pools.isEmpty()) {
                                    Text("No active storage pools on this host.", color = MaterialTheme.colorScheme.error)
                                }
                                env.pools.forEach { p ->
                                    ChoiceRow(p.name, listOfNotNull(p.available?.let { "$it free" }, p.capacity?.let { "of $it" }).joinToString(" "), pool == p.name) { pool = p.name }
                                }
                            }
                        }
                        3 -> {
                            Text("Network", style = MaterialTheme.typography.titleSmall)
                            env.networks.forEach { n ->
                                ChoiceRow(
                                    "Virtual network \"${n.name}\"",
                                    if (n.active) "Active" else "Inactive · started together with the VM",
                                    network == "net:${n.name}",
                                ) { network = "net:${n.name}" }
                            }
                            val bridges = env.bridges
                            ChoiceRow(
                                "Bridge device",
                                when {
                                    bridges == null -> "Attach to a host bridge"
                                    bridges.isEmpty() -> "No bridges found on the host"
                                    else -> "Host bridges: ${bridges.joinToString()}"
                                },
                                network?.startsWith("br:") == true,
                            ) { network = "br:" }
                            if (network?.startsWith("br:") == true) {
                                if (!bridges.isNullOrEmpty()) {
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        bridges.forEach { b ->
                                            FilterChip(selected = bridge.trim() == b, onClick = { bridge = b }, label = { Text(b) })
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    bridge, { bridge = it },
                                    label = { Text("Bridge name") },
                                    isError = bridgeError != null,
                                    supportingText = bridgeError?.let { { Text(it) } },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            ChoiceRow(
                                "Usermode networking",
                                "Outgoing access through QEMU; works without host setup" + if (env.session) " (recommended for session connections)" else "",
                                network == "user",
                            ) { network = "user" }
                            ChoiceRow("No network", null, network == "none") { network = "none" }

                            if (env.arch != "aarch64") {
                                SwitchRow("UEFI firmware", "Required for Secure Boot, Windows 11 and most modern OSes", uefi) { uefi = it }
                            }
                            if (!customize) SwitchRow("Start after creation", null, startAfter) { startAfter = it }
                            CheckRow(
                                "Customize configuration before install",
                                customize,
                                { customize = it },
                                supporting = "Review and change the virtual hardware first; installation begins with Begin installation",
                            )

                            val spec = buildSpec()
                            SectionCard("Summary") {
                                InfoRow("Name", spec.name)
                                InfoRow("OS", spec.osFamily.label)
                                InfoRow("Install", spec.installMediaPath ?: "None")
                                InfoRow("Memory", "${spec.memoryMiB} MiB")
                                InfoRow("CPUs", spec.vcpus.toString())
                                InfoRow("Storage", if (createDisk) "${diskGiB.roundToInt()} GiB in $pool" else "None")
                                InfoRow("Network", when (val n = spec.network) {
                                    is NetworkSource.Virtual -> n.network
                                    is NetworkSource.Bridge -> "Bridge ${n.bridge}"
                                    NetworkSource.User -> "Usermode networking"
                                    NetworkSource.None -> "None"
                                })
                                InfoRow("Firmware", spec.firmware.name)
                                if (spec.osFamily == OsFamily.WINDOWS) InfoRow("TPM", if (env.tpmEmulator) "Emulated TPM 2.0" else "None (swtpm not installed on the host)")
                                InfoRow("Architecture", spec.arch)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        stepTitles.indices.forEach { i ->
            val done = i < step
            val current = i == step
            Surface(
                shape = CircleShape,
                color = if (done || current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    else Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (i < stepTitles.lastIndex) {
                Box(
                    Modifier.weight(1f).height(2.dp).padding(horizontal = 6.dp)
                        .then(Modifier),
                ) {
                    Surface(
                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
