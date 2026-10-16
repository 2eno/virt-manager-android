package dev.virtmanager.android.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.virtmanager.android.ui.components.Format
import dev.virtmanager.android.ui.components.InfoRow
import dev.virtmanager.android.ui.components.MetricTile
import dev.virtmanager.android.ui.components.SectionCard
import dev.virtmanager.android.ui.components.UsageBar
import dev.virtmanager.android.ui.theme.LocalStatusColors

@Composable
fun OverviewTab(state: HostUiState) {
    val colors = LocalStatusColors.current
    val d = state.details
    val node = d?.node
    val mem = state.hostMem
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                label = "Host CPU",
                value = Format.percent(state.hostCpuPercent),
                subtitle = node?.cpus?.let { "$it logical CPUs" },
                color = colors.cpu,
                history = state.hostCpuHistory,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Host memory",
                value = mem?.let { Format.kib(it.usedKiB) } ?: "—",
                subtitle = mem?.let { "of ${Format.kib(it.totalKiB)}" },
                color = colors.memory,
                history = state.hostMemHistory,
                modifier = Modifier.weight(1f),
            )
        }

        val running = state.domains.count { it.state.isActive }
        SectionCard("Guests", icon = Icons.Outlined.Memory) {
            InfoRow("Running", "$running of ${state.domains.size}")
            val allocated = state.domains.filter { it.state.isActive }.sumOf { it.memoryKiB ?: 0 }
            InfoRow("Memory allocated", Format.kib(allocated))
            val vcpus = state.domains.filter { it.state.isActive }.sumOf { it.vcpus ?: 0 }
            InfoRow("vCPUs in use", node?.cpus?.let { "$vcpus / $it" } ?: vcpus.toString())
            if (mem != null && mem.totalKiB > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Committed memory", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                UsageBar(allocated.toFloat() / mem.totalKiB, colors.memory)
            }
        }

        SectionCard("Host", icon = Icons.Outlined.Dns) {
            InfoRow("Hostname", d?.hostname)
            InfoRow("Hypervisor", d?.version?.hypervisor)
            InfoRow("libvirt", d?.version?.library)
            InfoRow("Connection", d?.uri)
            InfoRow("Architecture", node?.cpuModel)
            InfoRow("CPUs", node?.cpus?.toString())
            InfoRow(
                "Topology",
                if (node?.sockets != null) "${node.sockets} socket · ${node.coresPerSocket} cores · ${node.threadsPerCore} threads" else null,
            )
            InfoRow("Frequency", node?.cpuFrequencyMHz?.let { "$it MHz" })
            InfoRow("NUMA cells", node?.numaCells?.toString())
            InfoRow("Memory", Format.kib(node?.memoryKiB))
        }
    }
}
