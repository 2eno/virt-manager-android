package dev.virtmanager.android.ui.vm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.NetworkInterface
import dev.virtmanager.android.libvirt.NetworkSource

private val nicModels = listOf("virtio", "e1000e", "e1000", "rtl8139")

/** Re-points a network card at a libvirt network or a host bridge (virt-manager's "Network source"). */
@Composable
fun NetworkEditDialog(
    current: NetworkInterface,
    choices: NetworkChoices?,
    running: Boolean,
    onConfirm: (NetworkSource, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // "net:<name>" or "br:<name>", mirroring the New VM wizard.
    var selected by remember {
        mutableStateOf(
            when (current.type) {
                "network" -> "net:${current.source.orEmpty()}"
                "bridge" -> "br:${current.source.orEmpty()}"
                else -> "net:"
            },
        )
    }
    var bridge by remember { mutableStateOf(if (current.type == "bridge") current.source.orEmpty() else "") }
    var model by remember { mutableStateOf(current.model ?: "virtio") }
    val bridges = choices?.bridges

    val bridgeError = if (!selected.startsWith("br:")) null else when {
        bridge.isBlank() -> "Enter a bridge name"
        bridges != null && bridge.trim() !in bridges -> "Not a bridge on this host"
        else -> null
    }
    val valid = choices != null && bridgeError == null && selected != "net:"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices == null) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    return@Column
                }
                choices.networks.forEach { n ->
                    Choice(
                        "Virtual network \"${n.name}\"",
                        if (n.active) "Active" else "Inactive · started with the VM",
                        selected == "net:${n.name}",
                    ) { selected = "net:${n.name}" }
                }
                Choice(
                    "Bridge device",
                    when {
                        bridges == null -> "Attach to a host bridge"
                        bridges.isEmpty() -> "No bridges found on the host"
                        else -> bridges.joinToString()
                    },
                    selected.startsWith("br:"),
                ) {
                    selected = "br:"
                    if (bridge.isBlank()) bridge = bridges?.firstOrNull().orEmpty()
                }
                if (selected.startsWith("br:")) {
                    if (!bridges.isNullOrEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            bridges.forEach { b -> FilterChip(bridge == b, { bridge = b }, { Text(b) }) }
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
                Spacer(Modifier.height(12.dp))
                Text("Device model", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    nicModels.forEach { m -> FilterChip(model == m, { model = m }, { Text(m) }) }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The VM is running; the change takes effect the next time it starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val source = if (selected.startsWith("br:")) NetworkSource.Bridge(bridge.trim())
                    else NetworkSource.Virtual(selected.removePrefix("net:"))
                    onConfirm(source, model.takeIf { it != current.model })
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
