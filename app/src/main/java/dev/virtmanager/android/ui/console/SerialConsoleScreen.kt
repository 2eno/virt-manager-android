package dev.virtmanager.android.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ErrorBanner
import dev.virtmanager.android.ui.theme.MonoStyle

private val keys = listOf(
    "Enter" to "\r",
    "Tab" to "\t",
    "Esc" to "\u001b",
    "^C" to "\u0003",
    "^D" to "\u0004",
    "^Z" to "\u001a",
    "↑" to "\u001b[A",
    "↓" to "\u001b[B",
    "←" to "\u001b[D",
    "→" to "\u001b[C",
    "^L" to "\u000c",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialConsoleScreen(connectionId: String, vmName: String, onBack: () -> Unit) {
    val app = app()
    val vm: SerialViewModel = viewModel(key = "serial-$connectionId-$vmName") { SerialViewModel(app, connectionId, vmName) }
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vmName)
                        Text(
                            if (state.connected) "Serial console" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::clear) { Icon(Icons.Outlined.ClearAll, "Clear") }
                    if (!state.connected) IconButton(onClick = { vm.connect() }) { Icon(Icons.Outlined.Refresh, "Reconnect") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            ErrorBanner(state.error, onDismiss = {})
            Surface(color = Color(0xFF0F1115), modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
                ) {
                    itemsIndexed(state.lines) { _, line ->
                        Text(line.ifEmpty { " " }, style = MonoStyle, color = Color(0xFFD8DEE9), softWrap = false)
                    }
                    if (state.lines.isEmpty()) {
                        item {
                            Text(
                                "Connected to the guest's serial port. Press Enter if nothing appears.",
                                style = MonoStyle,
                                color = Color(0xFF8A93A6),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keys.forEach { (label, seq) -> AssistChip(onClick = { vm.send(seq) }, label = { Text(label) }) }
            }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Type a command") },
                    singleLine = true,
                    textStyle = MonoStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { vm.send(input + "\r"); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { vm.send(input + "\r"); input = "" }, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
