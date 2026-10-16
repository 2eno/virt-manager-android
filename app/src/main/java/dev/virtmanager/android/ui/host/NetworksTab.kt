package dev.virtmanager.android.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.ui.components.EmptyState
import dev.virtmanager.android.ui.components.InfoRow
import dev.virtmanager.android.ui.components.ListPadding
import dev.virtmanager.android.ui.components.SectionCard
import dev.virtmanager.android.ui.components.StateBadge
import dev.virtmanager.android.libvirt.DomainState

@Composable
fun NetworksTab(state: HostUiState, vm: HostViewModel) {
    LaunchedEffect(Unit) { vm.loadNetworks() }
    val list = state.networks
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> EmptyState(Icons.Outlined.Hub, "No virtual networks", "This host has no libvirt networks defined.")
        else -> LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.first.name }) { (net, details) ->
                SectionCard(
                    title = net.name,
                    icon = Icons.Outlined.Hub,
                    action = { StateBadge(if (net.active) DomainState.RUNNING else DomainState.SHUTOFF) },
                ) {
                    InfoRow("Mode", details?.forwardMode?.uppercase())
                    InfoRow("Bridge", details?.bridge)
                    InfoRow(
                        "IPv4",
                        details?.ipAddress?.let { ip -> ip + (details.prefix?.let { "/$it" } ?: details.netmask?.let { " / $it" } ?: "") },
                    )
                    if (details?.dhcpStart != null) InfoRow("DHCP", "${details.dhcpStart} – ${details.dhcpEnd}")
                    InfoRow("Persistent", if (net.persistent) "Yes" else "No")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Autostart", Modifier.weight(1f))
                        Switch(checked = net.autostart, onCheckedChange = { on ->
                            vm.networkAction(net.name) { setNetworkAutostart(net.name, on) }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxSize()) {
                        if (net.active) {
                            OutlinedButton(onClick = { vm.networkAction(net.name) { stopNetwork(net.name) } }) { Text("Stop") }
                        } else {
                            FilledTonalButton(onClick = { vm.networkAction(net.name) { startNetwork(net.name) } }) { Text("Start") }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}
