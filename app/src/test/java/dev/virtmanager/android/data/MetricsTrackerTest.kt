package dev.virtmanager.android.data

import dev.virtmanager.android.libvirt.BlockStats
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.DomainStats
import dev.virtmanager.android.libvirt.InterfaceStats
import dev.virtmanager.android.libvirt.NodeCpuStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTrackerTest {
    private fun stats(cpuNs: Long, rx: Long = 0, rd: Long = 0, state: DomainState = DomainState.RUNNING) = DomainStats(
        name = "vm", state = state, cpuTimeNs = cpuNs, vcpus = 2, maxVcpus = 2,
        memoryKiB = 1000, maxMemoryKiB = 1000, rssKiB = 250, unusedKiB = null, availableKiB = null,
        interfaces = listOf(InterfaceStats("vnet0", rx, 0)),
        blocks = listOf(BlockStats("vda", rd, 0, null, null)),
    )

    @Test fun computesRates() {
        val t = MetricsTracker()
        t.update(listOf(stats(0)), 0)
        // 1 s wall time, 1 s of CPU time across 2 vCPUs = 50 %
        val m = t.update(listOf(stats(1_000_000_000, rx = 2048, rd = 4096)), 1_000_000_000)["vm"]!!
        assertEquals(50.0, m.cpuPercent, 0.001)
        assertEquals(2048.0, m.netRxRate, 0.001)
        assertEquals(4096.0, m.diskReadRate, 0.001)
        assertEquals(0.25, m.memoryFraction, 0.001)
        assertEquals(1, m.cpuHistory.size)
    }

    @Test fun guestReportedMemoryWins() {
        val s = stats(0).copy(availableKiB = 1000, unusedKiB = 600)
        assertEquals(0.4, MetricsTracker.memoryFraction(s), 0.001)
    }

    @Test fun stoppedDomainsResetToZero() {
        val t = MetricsTracker()
        t.update(listOf(stats(0)), 0)
        t.update(listOf(stats(500_000_000)), 1_000_000_000)
        val m = t.update(listOf(stats(0, state = DomainState.SHUTOFF)), 2_000_000_000)["vm"]!!
        assertEquals(0.0, m.cpuPercent, 0.0)
        assertEquals(0f, m.cpuHistory.last())
    }

    @Test fun historyIsBounded() {
        val t = MetricsTracker(capacity = 5)
        repeat(20) { i -> t.update(listOf(stats(i * 100_000_000L)), i * 1_000_000_000L) }
        assertEquals(5, t.update(listOf(stats(2_100_000_000)), 21_000_000_000)["vm"]!!.cpuHistory.size)
    }

    @Test fun hostCpu() {
        val h = HostCpuTracker()
        h.update(NodeCpuStats(100, 100, 800, 0))
        h.update(NodeCpuStats(300, 200, 1300, 0))
        // busy delta 300 of total delta 800
        assertEquals(37.5, h.percent, 0.001)
        assertTrue(h.history.isNotEmpty())
    }
}
