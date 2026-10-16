package dev.virtmanager.android.ui.host

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.ErrorBanner

private enum class HostTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    MACHINES("Machines", Icons.Outlined.Computer, Icons.Filled.Computer),
    OVERVIEW("Host", Icons.Outlined.Insights, Icons.Filled.Insights),
    NETWORKS("Networks", Icons.Outlined.Hub, Icons.Filled.Hub),
    STORAGE("Storage", Icons.Outlined.Storage, Icons.Filled.Storage),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    connectionId: String,
    onBack: () -> Unit,
    onOpenVm: (String) -> Unit,
    onNewVm: () -> Unit,
    onConsole: (String) -> Unit,
) {
    val app = app()
    val vm: HostViewModel = viewModel(key = "host-$connectionId") { HostViewModel(app, connectionId) }
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOfTab() }
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(vm) {
        vm.startPolling()
        onPauseOrDispose { vm.stopPolling() }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.uri,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        vm.refresh()
                        when (HostTab.entries[tab]) {
                            HostTab.NETWORKS -> vm.loadNetworks()
                            HostTab.STORAGE -> vm.loadPools()
                            else -> Unit
                        }
                    }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                    IconButton(onClick = { vm.disconnect(); onBack() }) { Icon(Icons.Outlined.LinkOff, "Disconnect") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                HostTab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (HostTab.entries[tab] == HostTab.MACHINES) {
                ExtendedFloatingActionButton(
                    onClick = onNewVm,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New VM") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ErrorBanner(state.error, onDismiss = vm::dismissError)
            when (HostTab.entries[tab]) {
                HostTab.MACHINES -> MachinesTab(state, vm, onOpenVm, onConsole)
                HostTab.OVERVIEW -> OverviewTab(state)
                HostTab.NETWORKS -> NetworksTab(state, vm)
                HostTab.STORAGE -> StorageTab(state, vm)
            }
        }
    }
}

private fun mutableStateOfTab() = androidx.compose.runtime.mutableIntStateOf(0)
