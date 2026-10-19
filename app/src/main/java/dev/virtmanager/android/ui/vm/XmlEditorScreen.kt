package dev.virtmanager.android.ui.vm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.libvirt.DomainXmlEditor
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ConfirmDialog
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.theme.MonoStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class XmlEditorState(
    val original: String? = null,
    val draft: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = original != null && draft != original
}

class XmlEditorViewModel(
    private val app: VirtManagerApp,
    private val connectionId: String,
    private val name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(XmlEditorState())
    val state = _state.asStateFlow()
    private var uuid: String? = null

    init {
        viewModelScope.launch {
            try {
                // The persistent definition, not the live one: runtime details (ports, aliases,
                // tap devices) must not be written back into the config.
                val xml = app.connections.connect(connectionId).virsh.dumpXml(name, inactive = true)
                uuid = runCatching { DomainXmlEditor.identity(xml).second }.getOrNull()
                _state.update { it.copy(original = xml, draft = xml) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = ConnectionManager.friendly(t)) }
            }
        }
    }

    fun edit(text: String) = _state.update { it.copy(draft = text) }

    fun save() = viewModelScope.launch {
        _state.update { it.copy(saving = true, error = null) }
        try {
            app.connections.connect(connectionId).virsh.redefine(name, uuid, _state.value.draft)
            _state.update { it.copy(saving = false, saved = true) }
        } catch (t: Throwable) {
            // Keep the draft so the user can fix whatever libvirt rejected.
            _state.update { it.copy(saving = false, error = t.message ?: ConnectionManager.friendly(t)) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XmlEditorScreen(connectionId: String, vmName: String, onDone: () -> Unit) {
    val app = app()
    val vm: XmlEditorViewModel = viewModel(key = "xml-$connectionId-$vmName") { XmlEditorViewModel(app, connectionId, vmName) }
    val state by vm.state.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Edit XML")
                        Text(vmName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) confirmDiscard = true else onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = vm::save, enabled = state.dirty && !state.saving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(state.error, onDismiss = vm::dismissError)
            Text(
                "Changes are saved to the VM's configuration and take effect the next time it starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.original == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.error == null) CircularProgressIndicator()
                }
            } else {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::edit,
                    textStyle = MonoStyle,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your edits to the XML haven't been saved.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = { confirmDiscard = false; onDone() },
            onDismiss = { confirmDiscard = false },
        )
    }
}
