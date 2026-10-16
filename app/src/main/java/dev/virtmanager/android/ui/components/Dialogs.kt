package dev.virtmanager.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.data.HostKeyPrompt
import dev.virtmanager.android.ui.theme.MonoStyle

@Composable
fun HostKeyDialog(prompt: HostKeyPrompt) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(if (prompt.changed) Icons.Outlined.GppMaybe else Icons.Outlined.Fingerprint, null) },
        title = { Text(if (prompt.changed) "Host key changed!" else "Unknown host") },
        text = {
            Column {
                Text(
                    if (prompt.changed)
                        "The host key for ${prompt.host}:${prompt.port} differs from the one saved earlier. " +
                            "Someone could be intercepting the connection, or the host was reinstalled."
                    else
                        "The authenticity of ${prompt.host}:${prompt.port} can't be established. " +
                            "Compare the fingerprint with the output of \"ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub\" on the host.",
                )
                Spacer(Modifier.height(12.dp))
                Text(prompt.keyType, style = MaterialTheme.typography.labelLarge)
                Text(prompt.fingerprint, style = MonoStyle)
            }
        },
        confirmButton = {
            Button(
                onClick = { prompt.respond(true) },
                colors = if (prompt.changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(if (prompt.changed) "Trust new key" else "Trust") }
        },
        dismissButton = { TextButton(onClick = { prompt.respond(false) }) { Text("Cancel") } },
    )
}

@Composable
fun PasswordDialog(title: String, message: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotEmpty()) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                extra?.invoke()
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> Boolean = { it.isNotBlank() },
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value.trim()) }, enabled = validate(value)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
