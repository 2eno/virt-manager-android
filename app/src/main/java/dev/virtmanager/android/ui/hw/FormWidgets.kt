package dev.virtmanager.android.ui.hw

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.libvirt.config.DeviceSettings
import dev.virtmanager.android.libvirt.config.DiskSettings
import dev.virtmanager.android.libvirt.config.InputSettings
import org.w3c.dom.Element

@Composable
fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** A single-choice row of chips, virt-manager's combo boxes. [labels] maps values to text. */
@Composable
fun <T> Choice(
    label: String?,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    labels: (T) -> String = { it.toString() },
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(labels(o)) }, enabled = enabled)
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, supporting: String? = null) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    error: String? = null,
    supporting: String? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (number) it.filter(Char::isDigit) else it) },
        label = { Text(label) },
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        trailingIcon = trailing,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
fun ReadOnlyLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun Note(text: String, warning: Boolean = false) {
    Surface(
        color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (warning) Icons.Outlined.Warning else Icons.Outlined.Info, null, Modifier.padding(end = 12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Icons for the hardware list, after virt-manager's device icons. */
fun deviceIcon(el: Element?): ImageVector {
    val s = el?.let(DeviceSettings::read)
    return when (el?.tagName) {
        "disk" -> if ((s as? DiskSettings)?.device == "cdrom") Icons.Outlined.Album else Icons.Outlined.Save
        "interface" -> Icons.Outlined.Lan
        "input" -> when ((s as? InputSettings)?.type) {
            "tablet" -> Icons.Outlined.TouchApp
            "keyboard" -> Icons.Outlined.Keyboard
            else -> Icons.Outlined.Mouse
        }
        "graphics" -> Icons.Outlined.DesktopWindows
        "video" -> Icons.Outlined.Monitor
        "sound" -> Icons.Outlined.Speaker
        "serial", "parallel", "console", "channel" -> Icons.Outlined.Terminal
        "controller" -> Icons.Outlined.DeveloperBoard
        "redirdev", "hostdev", "hub" -> Icons.Outlined.Usb
        "rng" -> Icons.Outlined.Shuffle
        "watchdog" -> Icons.Outlined.RestartAlt
        "tpm" -> Icons.Outlined.Security
        "filesystem" -> Icons.Outlined.Folder
        "panic" -> Icons.Outlined.Notifications
        "vsock" -> Icons.Outlined.Cable
        "smartcard" -> Icons.Outlined.SimCard
        "iommu", "shmem" -> Icons.Outlined.ViewInAr
        null -> Icons.Outlined.Computer
        else -> Icons.Outlined.Widgets
    }
}

val PageIcons = mapOf(
    "overview" to Icons.Outlined.Computer,
    "os" to Icons.Outlined.Info,
    "cpus" to Icons.Outlined.Memory,
    "memory" to Icons.Outlined.SettingsInputComponent,
    "boot" to Icons.Outlined.RestartAlt,
)
