package dev.virtmanager.android.ui.connections

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.data.AuthMethod
import dev.virtmanager.android.data.ConnectionProfile
import dev.virtmanager.android.data.SecretCipher
import dev.virtmanager.android.ssh.SshCrypto
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val uriPresets = listOf(
    "qemu:///system" to "QEMU/KVM (system)",
    "qemu:///session" to "QEMU/KVM user session",
    "lxc:///system" to "LXC containers",
    "xen:///system" to "Xen",
    "ch:///system" to "Cloud Hypervisor",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(connectionId: String?, onDone: () -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loaded by remember { mutableStateOf(connectionId == null) }
    var original by remember { mutableStateOf<ConnectionProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var uri by remember { mutableStateOf("qemu:///system") }
    var auth by remember { mutableStateOf(AuthMethod.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(true) }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var sudo by remember { mutableStateOf(false) }
    var compression by remember { mutableStateOf(true) }
    var autoConnect by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var uriMenu by remember { mutableStateOf(false) }

    LaunchedEffect(connectionId) {
        if (connectionId == null) return@LaunchedEffect
        val p = app.repository.get(connectionId) ?: return@LaunchedEffect
        original = p
        name = p.name; host = p.host; port = p.port.toString(); username = p.username
        uri = p.libvirtUri; auth = p.authMethod; sudo = p.useSudo; compression = p.compression
        autoConnect = p.autoConnect
        withContext(Dispatchers.Default) {
            password = SecretCipher.decrypt(p.encryptedPassword).orEmpty()
            privateKey = SecretCipher.decrypt(p.encryptedPrivateKey).orEmpty()
            passphrase = SecretCipher.decrypt(p.encryptedKeyPassphrase).orEmpty()
        }
        savePassword = p.encryptedPassword != null || p.authMethod == AuthMethod.KEY
        loaded = true
    }

    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { privateKey = it.readBytes().toString(Charsets.UTF_8).trim() + "\n" }
        }
    }

    val portValue = port.toIntOrNull()
    val valid = host.isNotBlank() && username.isNotBlank() && portValue != null && portValue in 1..65535 &&
        uri.contains(":/") && (auth == AuthMethod.PASSWORD || privateKey.contains("PRIVATE KEY"))

    fun save() {
        scope.launch {
            val profile = withContext(Dispatchers.Default) {
                (original ?: ConnectionProfile(name = "", host = "")).copy(
                    name = name.ifBlank { host.trim() },
                    host = host.trim(),
                    port = portValue ?: 22,
                    username = username.trim(),
                    libvirtUri = uri.trim(),
                    authMethod = auth,
                    useSudo = sudo,
                    compression = compression,
                    autoConnect = autoConnect,
                    encryptedPassword = if (auth == AuthMethod.PASSWORD && savePassword) SecretCipher.encrypt(password) else null,
                    encryptedPrivateKey = if (auth == AuthMethod.KEY) SecretCipher.encrypt(privateKey.trim() + "\n") else null,
                    encryptedKeyPassphrase = if (auth == AuthMethod.KEY) SecretCipher.encrypt(passphrase) else null,
                )
            }
            original?.let { app.connections.disconnect(it.id) }
            app.repository.upsert(profile)
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (connectionId == null) "Add connection" else "Edit connection") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = ::save, enabled = valid && loaded) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hypervisor", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ExposedDropdownMenuBox(expanded = uriMenu, onExpandedChange = { uriMenu = it }) {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("libvirt URI on the host") },
                    supportingText = { Text(uriPresets.firstOrNull { it.first == uri }?.second ?: "Custom URI") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(uriMenu) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                )
                ExposedDropdownMenu(expanded = uriMenu, onDismissRequest = { uriMenu = false }) {
                    uriPresets.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Column { Text(label); Text(value, style = MaterialTheme.typography.bodySmall) } },
                            onClick = { uri = value; uriMenu = false },
                        )
                    }
                }
            }

            Text("SSH", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                OutlinedTextField(
                    host, { host = it }, label = { Text("Host") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = auth == AuthMethod.PASSWORD,
                    onClick = { auth = AuthMethod.PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Password, null) },
                ) { Text("Password") }
                SegmentedButton(
                    selected = auth == AuthMethod.KEY,
                    onClick = { auth = AuthMethod.KEY },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.Key, null) },
                ) { Text("SSH key") }
            }

            val visual = if (showSecret) VisualTransformation.None else PasswordVisualTransformation()
            val eye: @Composable () -> Unit = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show")
                }
            }
            if (auth == AuthMethod.PASSWORD) {
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = visual, trailingIcon = eye,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Remember password", "Encrypted with a hardware-backed key on this device", savePassword) { savePassword = it }
            } else {
                OutlinedTextField(
                    privateKey, { privateKey = it },
                    label = { Text("Private key (OpenSSH / PEM)") },
                    textStyle = MonoStyle,
                    minLines = 4, maxLines = 8,
                    visualTransformation = if (showSecret || privateKey.isEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = eye,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val key = withContext(Dispatchers.Default) { SshCrypto.generateEd25519("virt-manager-android") }
                            privateKey = key.privateKeyPem
                            passphrase = ""
                            publicKey = key.publicKeyLine
                        }
                    }) {
                        Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Generate")
                    }
                    if (privateKey.contains("PRIVATE KEY")) {
                        TextButton(onClick = {
                            scope.launch {
                                publicKey = withContext(Dispatchers.Default) {
                                    SshCrypto.publicKeyFor(privateKey, passphrase, "virt-manager-android")
                                } ?: "Could not read the key (wrong passphrase?)"
                            }
                        }) { Text("Public key") }
                    }
                }
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("Key passphrase (if any)") }, singleLine = true,
                    visualTransformation = visual,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Options", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SwitchRow("Use sudo", "Run virsh through \"sudo -n\" (needs passwordless sudo)", sudo) { sudo = it }
            SwitchRow("SSH compression", "Faster consoles on slow links, slightly more CPU", compression) { compression = it }
            SwitchRow("Connect automatically", "Open this connection when the app starts", autoConnect) { autoConnect = it }
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::save, enabled = valid && loaded, modifier = Modifier.fillMaxWidth()) { Text("Save connection") }
            Spacer(Modifier.height(24.dp))
        }
    }

    publicKey?.let { pub ->
        AlertDialog(
            onDismissRequest = { publicKey = null },
            icon = { Icon(Icons.Outlined.Key, null) },
            title = { Text("Public key") },
            text = {
                Column {
                    Text("Append this line to ~/.ssh/authorized_keys of $username on the host:")
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer { Text(pub, style = MonoStyle) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ssh public key", pub))
                    publicKey = null
                }) {
                    Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy")
                }
            },
            dismissButton = { TextButton(onClick = { publicKey = null }) { Text("Close") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    )
}
