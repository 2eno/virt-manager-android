package dev.virtmanager.android.data

import dev.virtmanager.android.libvirt.DomainStats
import dev.virtmanager.android.libvirt.NodeCpuStats

data class DomainMetrics(
    val cpuPercent: Double = 0.0,
    val memoryFraction: Double = 0.0,
    val diskReadRate: Double = 0.0,
    val diskWriteRate: Double = 0.0,
    val netRxRate: Double = 0.0,
    val netTxRate: Double = 0.0,
    val cpuHistory: List<Float> = emptyList(),
    val memoryHistory: List<Float> = emptyList(),
    val diskHistory: List<Float> = emptyList(),
    val netHistory: List<Float> = emptyList(),
)

/**
 * Turns cumulative libvirt counters into rates and keeps short histories for the sparklines,
 * like virt-manager's performance graphs.
 */
class MetricsTracker(private val capacity: Int = 60) {
    private data class Sample(val atNs: Long, val stats: DomainStats)

    private val previous = mutableMapOf<String, Sample>()
    private val metrics = mutableMapOf<String, DomainMetrics>()
    private var peakDisk = 1.0
    private var peakNet = 1.0

    private fun List<Float>.push(v: Float) = (this + v).takeLast(capacity)

    fun update(domains: List<DomainStats>, nowNs: Long): Map<String, DomainMetrics> {
        val names = domains.map { it.name }.toSet()
        previous.keys.retainAll(names)
        metrics.keys.retainAll(names)

        for (d in domains) {
            val prev = previous[d.name]
            val old = metrics[d.name] ?: DomainMetrics()
            if (prev == null || !d.state.isActive) {
                previous[d.name] = Sample(nowNs, d)
                metrics[d.name] = if (!d.state.isActive) DomainMetrics(
                    cpuHistory = old.cpuHistory.push(0f),
                    memoryHistory = old.memoryHistory.push(0f),
                    diskHistory = old.diskHistory.push(0f),
                    netHistory = old.netHistory.push(0f),
                ) else old
                continue
            }
            val dtNs = (nowNs - prev.atNs).coerceAtLeast(1)
            val dtSec = dtNs / 1e9
            val vcpus = (d.vcpus ?: 1).coerceAtLeast(1)
            val cpuDelta = ((d.cpuTimeNs ?: 0) - (prev.stats.cpuTimeNs ?: 0)).coerceAtLeast(0)
            val cpu = (cpuDelta.toDouble() / (dtNs.toDouble() * vcpus) * 100.0).coerceIn(0.0, 100.0)

            val mem = memoryFraction(d)
            val rd = ((d.diskReadBytes - prev.stats.diskReadBytes).coerceAtLeast(0)) / dtSec
            val wr = ((d.diskWriteBytes - prev.stats.diskWriteBytes).coerceAtLeast(0)) / dtSec
            val rx = ((d.netRxBytes - prev.stats.netRxBytes).coerceAtLeast(0)) / dtSec
            val tx = ((d.netTxBytes - prev.stats.netTxBytes).coerceAtLeast(0)) / dtSec
            peakDisk = maxOf(peakDisk * 0.995, rd + wr, 1.0)
            peakNet = maxOf(peakNet * 0.995, rx + tx, 1.0)

            metrics[d.name] = DomainMetrics(
                cpuPercent = cpu,
                memoryFraction = mem,
                diskReadRate = rd,
                diskWriteRate = wr,
                netRxRate = rx,
                netTxRate = tx,
                cpuHistory = old.cpuHistory.push((cpu / 100).toFloat()),
                memoryHistory = old.memoryHistory.push(mem.toFloat()),
                diskHistory = old.diskHistory.push(((rd + wr) / peakDisk).toFloat()),
                netHistory = old.netHistory.push(((rx + tx) / peakNet).toFloat()),
            )
            previous[d.name] = Sample(nowNs, d)
        }
        return metrics.toMap()
    }

    companion object {
        /** Guest-reported usage when the balloon driver provides it, else resident set size. */
        fun memoryFraction(d: DomainStats): Double {
            val current = d.memoryKiB ?: return 0.0
            if (current <= 0) return 0.0
            val used = when {
                d.availableKiB != null && d.unusedKiB != null -> d.availableKiB - d.unusedKiB
                d.rssKiB != null -> d.rssKiB
                else -> current
            }
            return (used.toDouble() / current).coerceIn(0.0, 1.0)
        }
    }
}

class HostCpuTracker(private val capacity: Int = 60) {
    private var prev: NodeCpuStats? = null
    var history: List<Float> = emptyList()
        private set
    var percent: Double = 0.0
        private set

    fun update(stats: NodeCpuStats) {
        val p = prev
        prev = stats
        if (p == null) return
        val total = stats.total - p.total
        if (total <= 0) return
        percent = ((stats.busy - p.busy).toDouble() / total * 100).coerceIn(0.0, 100.0)
        history = (history + (percent / 100).toFloat()).takeLast(capacity)
    }
}
