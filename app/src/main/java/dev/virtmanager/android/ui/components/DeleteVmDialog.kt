package dev.virtmanager.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.ui.theme.MonoStyle

/**
 * Confirms deleting a VM, like virt-manager's delete dialog: the VM's disk images are listed
 * with a checkbox each. Images that another VM also uses start unchecked and say which VM.
 * [disks] is null while they are being looked up, [lookupError] says why that failed;
 * [onConfirm] gets the paths to delete.
 */
@Composable
fun DeleteVmDialog(
    name: String,
    running: Boolean,
    disks: List<VirshClient.DiskCandidate>?,
    onConfirm: (deletePaths: List<String>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Delete \"$name\"?",
    confirmLabel: String = "Delete",
    deleteStorageByDefault: Boolean = false,
    lookupError: String? = null,
) {
    var removeStorage by remember { mutableStateOf(deleteStorageByDefault) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(disks) {
        if (disks != null && selected == null) selected = disks.filter { it.sharedWith.isEmpty() }.map { it.path }.toSet()
    }
    val chosen = selected.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (if (running) "The VM is running and will be forced off first. " else "") +
                        "Its definition, snapshots and saved state are removed from the host. This can't be undone.",
                )
                Spacer(Modifier.height(12.dp))
                when {
                    disks == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Looking up disks…", style = MaterialTheme.typography.bodyMedium)
                    }
                    lookupError != null -> Text(
                        "Couldn't look up the disk images ($lookupError). Only the VM definition is deleted; " +
                            "its disk images are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    disks.isEmpty() -> Text(
                        "This VM has no disk images to delete. Install media (ISO files) are never deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        CheckLine("Delete associated storage files", removeStorage, { removeStorage = it })
                        if (removeStorage) {
                            disks.forEach { d ->
                                CheckLine(
                                    label = "${d.target}: ${d.path}",
                                    checked = d.path in chosen,
                                    onChange = { on -> selected = if (on) chosen + d.path else chosen - d.path },
                                    mono = true,
                                    warning = if (d.sharedWith.isNotEmpty()) "Also used by ${d.sharedWith.joinToString()}" else null,
                                )
                            }
                            Text(
                                "Install media (ISO files) are never deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                            )
                        } else {
                            Text(
                                "Disk images are kept and can be reused for a new VM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(if (removeStorage) disks.orEmpty().map { it.path }.filter { it in chosen } else emptyList()) },
                enabled = disks != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, mono: Boolean = false, warning: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column {
            Text(
                label,
                style = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge,
                color = if (mono && checked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (warning != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text(" $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

/** Shown after a deletion that couldn't remove every selected file. */
@Composable
fun DeleteReportDialog(report: VirshClient.DeleteReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Warning, null) },
        title = { Text("VM deleted, some files kept") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("The VM was deleted, but these files are still on the host and have to be removed there:")
                Spacer(Modifier.height(8.dp))
                report.notDeleted.forEach { (path, why) ->
                    Text(path, style = MonoStyle)
                    Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
