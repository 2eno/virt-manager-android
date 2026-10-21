package dev.virtmanager.android.libvirt

/** Mirrors libvirt's virDomainState enum (the numeric values reported by `virsh domstats`). */
enum class DomainState(val code: Int, val label: String) {
    NO_STATE(0, "No state"),
    RUNNING(1, "Running"),
    BLOCKED(2, "Blocked"),
    PAUSED(3, "Paused"),
    SHUTTING_DOWN(4, "Shutting down"),
    SHUTOFF(5, "Shut off"),
    CRASHED(6, "Crashed"),
    PM_SUSPENDED(7, "Suspended");

    val isActive: Boolean get() = this == RUNNING || this == BLOCKED || this == PAUSED || this == SHUTTING_DOWN || this == PM_SUSPENDED

    companion object {
        fun fromCode(code: Int?): DomainState = entries.firstOrNull { it.code == code } ?: NO_STATE

        /** Parses the human readable state printed by `virsh dominfo` / `virsh list`. */
        fun fromText(text: String): DomainState = when (text.trim().lowercase()) {
            "running" -> RUNNING
            "idle", "blocked" -> BLOCKED
            "paused" -> PAUSED
            "in shutdown", "shutting down" -> SHUTTING_DOWN
            "shut off", "shutoff" -> SHUTOFF
            "crashed" -> CRASHED
            "pmsuspended" -> PM_SUSPENDED
            else -> NO_STATE
        }
    }
}

data class InterfaceStats(val name: String, val rxBytes: Long, val txBytes: Long)
data class BlockStats(val name: String, val readBytes: Long, val writeBytes: Long, val capacity: Long?, val allocation: Long?)

/** One entry of `virsh domstats --raw`. */
data class DomainStats(
    val name: String,
    val state: DomainState,
    val cpuTimeNs: Long?,
    val vcpus: Int?,
    val maxVcpus: Int?,
    val memoryKiB: Long?,
    val maxMemoryKiB: Long?,
    val rssKiB: Long?,
    val unusedKiB: Long?,
    val availableKiB: Long?,
    val interfaces: List<InterfaceStats>,
    val blocks: List<BlockStats>,
) {
    val netRxBytes: Long get() = interfaces.sumOf { it.rxBytes }
    val netTxBytes: Long get() = interfaces.sumOf { it.txBytes }
    val diskReadBytes: Long get() = blocks.sumOf { it.readBytes }
    val diskWriteBytes: Long get() = blocks.sumOf { it.writeBytes }
}

data class DomainInfo(
    val id: String?,
    val name: String,
    val uuid: String?,
    val osType: String?,
    val state: DomainState,
    val cpus: Int?,
    val maxMemoryKiB: Long?,
    val usedMemoryKiB: Long?,
    val persistent: Boolean,
    val autostart: Boolean,
)

data class DiskDevice(
    val device: String,            // disk, cdrom, floppy, lun
    val type: String,              // file, block, volume, network
    val driverType: String?,       // qcow2, raw ...
    val source: String?,           // path / pool+volume / network name
    val target: String?,           // vda, sda ...
    val bus: String?,
    val readOnly: Boolean,
)

data class NetworkInterface(
    val type: String,              // network, bridge, direct, user
    val mac: String?,
    val source: String?,
    val model: String?,
    val target: String?,
)

data class GraphicsDevice(
    val type: String,              // vnc, spice
    val port: Int?,
    val autoport: Boolean,
    val listen: String?,
    val hasPassword: Boolean,
    val socket: String?,
)

data class DomainDefinition(
    val name: String,
    val uuid: String?,
    val title: String?,
    val description: String?,
    val domainType: String?,
    val memoryKiB: Long?,
    val currentMemoryKiB: Long?,
    val vcpus: Int?,
    val arch: String?,
    val machine: String?,
    val firmware: String?,
    val bootDevices: List<String>,
    val cpuMode: String?,
    val emulator: String?,
    val disks: List<DiskDevice>,
    val interfaces: List<NetworkInterface>,
    val graphics: List<GraphicsDevice>,
    val videoModel: String?,
    val hasSerialConsole: Boolean,
    val hasGuestAgentChannel: Boolean,
    /** Created with "Customize configuration before install" and never started since. */
    val setupPending: Boolean = false,
    /** Addresses of passed-through PCI devices (VFIO), e.g. a GPU. */
    val pciHostdevs: List<String> = emptyList(),
) {
    val vnc: GraphicsDevice? get() = graphics.firstOrNull { it.type == "vnc" }
}

data class NodeInfo(
    val cpuModel: String?,
    val cpus: Int?,
    val cpuFrequencyMHz: Int?,
    val sockets: Int?,
    val coresPerSocket: Int?,
    val threadsPerCore: Int?,
    val numaCells: Int?,
    val memoryKiB: Long?,
)

data class NodeMemStats(val totalKiB: Long, val freeKiB: Long, val buffersKiB: Long, val cachedKiB: Long) {
    val usedKiB: Long get() = (totalKiB - freeKiB - buffersKiB - cachedKiB).coerceAtLeast(0)
}

/** Cumulative CPU counters from `virsh nodecpustats` (nanoseconds). */
data class NodeCpuStats(val user: Long, val system: Long, val idle: Long, val iowait: Long) {
    val total: Long get() = user + system + idle + iowait
    val busy: Long get() = user + system
}

data class HypervisorVersion(val library: String?, val api: String?, val hypervisor: String?)

data class VirtualNetwork(
    val name: String,
    val active: Boolean,
    val autostart: Boolean,
    val persistent: Boolean,
)

data class NetworkDetails(
    val name: String,
    val uuid: String?,
    val bridge: String?,
    val forwardMode: String?,
    val ipAddress: String?,
    val netmask: String?,
    val prefix: String?,
    val dhcpStart: String?,
    val dhcpEnd: String?,
)

data class StoragePool(
    val name: String,
    val state: String,
    val autostart: Boolean,
    val persistent: Boolean,
    val capacity: String?,
    val allocation: String?,
    val available: String?,
) {
    val active: Boolean get() = state.equals("running", ignoreCase = true) || state.equals("active", ignoreCase = true)
}

data class StorageVolume(
    val name: String,
    val path: String,
    val type: String?,
    val capacity: String?,
    val allocation: String?,
)

data class Snapshot(
    val name: String,
    val creationTime: String,
    val state: String,
    val current: Boolean = false,
    /** Stored in overlay files (and a memory file) next to the disks instead of inside them. */
    val external: Boolean = false,
)

/** The files an external snapshot consists of, from `virsh snapshot-dumpxml`. */
data class SnapshotFiles(
    val external: Boolean,
    val memory: String?,
    /** Disk target → overlay created by the snapshot. */
    val overlays: Map<String, String>,
    /** Disk target → the image the VM used when the snapshot was taken (the overlay's backing). */
    val base: Map<String, String>,
    /** CD-ROM/floppy target → the media inserted when the snapshot was taken. */
    val media: Map<String, String> = emptyMap(),
)

class VirshException(message: String) : Exception(message)

/**
 * Marks a VM whose creation isn't finished: defined by the New VM wizard for "Customize
 * configuration before install" and not started yet. Stored in the domain's libvirt metadata so
 * the app (on any device) can offer Begin/Cancel installation later.
 */
object SetupMarker {
    const val NS = "https://github.com/2eno/virt-manager-android/xmlns/setup/1.0"
    const val XML = "<vma:setup xmlns:vma=\"$NS\" state=\"customizing\"/>"
}
