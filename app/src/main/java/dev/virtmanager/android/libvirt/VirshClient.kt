package dev.virtmanager.android.libvirt

/**
 * A thin, typed wrapper around the `virsh` CLI executed on the remote host. This is the Android
 * equivalent of virt-manager's libvirt bindings: rather than shipping libvirt for Android, all
 * management is done through the host's own virsh over an SSH connection.
 */
class VirshClient(
    private val runner: CommandRunner,
    private val uri: String,
    private val useSudo: Boolean = false,
) {
    /** @param quiet passes `-q`, which also drops table headers; table parsing needs them. */
    private fun virshCommand(args: List<String>, quiet: Boolean = true): String {
        val base = mutableListOf<String>()
        if (useSudo) base += listOf("sudo", "-n")
        base += "virsh"
        if (quiet) base += "-q"
        base += listOf("-c", uri)
        // Force the C locale so the output we parse is never translated.
        return "LC_ALL=C " + Shell.join(base + args)
    }

    suspend fun virsh(vararg args: String, stdin: String? = null): String {
        val r = runner.run(virshCommand(args.toList()), stdin)
        if (!r.ok) throw VirshException(cleanError(r.stderr.ifBlank { r.stdout }))
        return r.stdout
    }

    /** Variant that keeps table headers (needed by the table parser). */
    private suspend fun virshTable(vararg args: String): String {
        val r = runner.run(virshCommand(args.toList(), quiet = false), null)
        if (!r.ok) throw VirshException(cleanError(r.stderr.ifBlank { r.stdout }))
        return r.stdout
    }

    /**
     * Runs several virsh invocations in a single SSH round trip. Each result is either the
     * command's stdout or null if it failed.
     */
    suspend fun batch(vararg commands: List<String>): List<String?> {
        val marker = "__VMA_SECTION__"
        val script = commands.joinToString("\n") { args ->
            "${virshCommand(args, quiet = false)} 2>/dev/null; echo \"$marker \$?\""
        }
        val r = runner.run(script, null)
        val results = mutableListOf<String?>()
        val current = StringBuilder()
        for (line in r.stdout.lines()) {
            if (line.startsWith(marker)) {
                val code = line.removePrefix(marker).trim().toIntOrNull() ?: 1
                results += if (code == 0) current.toString() else null
                current.clear()
            } else {
                current.append(line).append('\n')
            }
        }
        while (results.size < commands.size) results += null
        return results
    }

    private fun cleanError(text: String): String =
        text.lines().map { it.removePrefix("error: ").trim() }.filter { it.isNotEmpty() }
            .joinToString("\n").ifBlank { "virsh failed" }

    // --- Host ------------------------------------------------------------------------------

    data class HostSnapshot(
        val domains: List<DomainStats>,
        val cpu: NodeCpuStats?,
        val mem: NodeMemStats?,
    )

    suspend fun pollHost(): HostSnapshot {
        val (stats, cpu, mem) = batch(
            listOf("domstats", "--raw", "--state", "--cpu-total", "--balloon", "--vcpu", "--interface", "--block"),
            listOf("nodecpustats"),
            listOf("nodememstats"),
        )
        if (stats == null) {
            // Surface the real error (permissions, bad URI, libvirtd down ...).
            virsh("domstats", "--raw", "--state")
        }
        return HostSnapshot(
            domains = Parsers.parseDomStats(stats.orEmpty()).sortedBy { it.name.lowercase() },
            cpu = cpu?.let(Parsers::parseNodeCpuStats),
            mem = mem?.let(Parsers::parseNodeMemStats),
        )
    }

    /** Whether libvirt can run KVM guests on this host (else only TCG emulation). */
    suspend fun supportsKvm(): Boolean =
        runCatching { virsh("capabilities").contains("<domain type='kvm'") }.getOrDefault(true)

    data class HostDetails(
        val hostname: String?,
        val node: NodeInfo?,
        val version: HypervisorVersion?,
        val uri: String?,
    )

    suspend fun hostDetails(): HostDetails {
        val (hostname, node, version, uri) = batch(
            listOf("hostname"), listOf("nodeinfo"), listOf("version"), listOf("uri"),
        )
        return HostDetails(
            hostname = hostname?.trim(),
            node = node?.let(Parsers::parseNodeInfo),
            version = version?.let(Parsers::parseVersion),
            uri = uri?.trim(),
        )
    }

    // --- Domains ---------------------------------------------------------------------------

    suspend fun domInfo(name: String) = Parsers.parseDomInfo(virsh("dominfo", name))
    suspend fun dumpXml(name: String, inactive: Boolean = false): String =
        if (inactive) virsh("dumpxml", "--inactive", name) else virsh("dumpxml", name)

    suspend fun definition(name: String) = Parsers.parseDomainXml(dumpXml(name))

    suspend fun domStats(name: String): DomainStats? =
        Parsers.parseDomStats(
            virsh("domstats", "--raw", "--state", "--cpu-total", "--balloon", "--vcpu", "--interface", "--block", name),
        ).firstOrNull()

    suspend fun start(name: String) = virsh("start", name)

    /**
     * Starts a VM like virt-manager does: any inactive libvirt network it is attached to is
     * started first, since `virsh start` would otherwise fail with "network is not active".
     * Returns the networks that had to be started.
     */
    suspend fun startWithNetworks(name: String): List<String> {
        val wanted = Parsers.parseDomainXml(dumpXml(name, inactive = true)).interfaces
            .filter { it.type == "network" }.mapNotNull { it.source }.toSet()
        val started = if (wanted.isEmpty()) emptyList() else networks().filter { it.name in wanted && !it.active }.map { it.name }
        started.forEach { startNetwork(it) }
        start(name)
        return started
    }
    suspend fun shutdown(name: String) = virsh("shutdown", name)
    suspend fun reboot(name: String) = virsh("reboot", name)
    suspend fun reset(name: String) = virsh("reset", name)
    suspend fun destroy(name: String) = virsh("destroy", name)
    suspend fun suspend(name: String) = virsh("suspend", name)
    suspend fun resume(name: String) = virsh("resume", name)
    suspend fun managedSave(name: String) = virsh("managedsave", name)
    suspend fun setAutostart(name: String, enabled: Boolean) =
        if (enabled) virsh("autostart", name) else virsh("autostart", "--disable", name)

    /**
     * Removes the VM definition. [deleteStorage] lists disk targets (e.g. "vda") whose volumes are
     * deleted too. Only the VM's own disks are passed, never `--remove-all-storage`, so a shared
     * install ISO attached as a CD-ROM is left alone.
     */
    suspend fun undefine(name: String, deleteStorage: List<String> = emptyList()) {
        val storage = if (deleteStorage.isEmpty()) emptyList() else listOf("--storage", deleteStorage.joinToString(","))
        val args = listOf("undefine", name, "--managed-save", "--snapshots-metadata", "--nvram") + storage
        val r = runner.run(virshCommand(args), null)
        if (!r.ok) {
            // Older libvirt doesn't know --nvram for BIOS guests; retry with a minimal set.
            virsh(*(listOf("undefine", name) + storage).toTypedArray())
        }
    }

    suspend fun setVcpus(name: String, count: Int) {
        virsh("setvcpus", name, count.toString(), "--maximum", "--config")
        virsh("setvcpus", name, count.toString(), "--config")
    }

    suspend fun setMemory(name: String, mib: Long) {
        val kib = (mib * 1024).toString()
        virsh("setmaxmem", name, kib, "--config")
        virsh("setmem", name, kib, "--config")
    }

    suspend fun rename(name: String, newName: String) = virsh("domrename", name, newName)

    suspend fun defineXml(xml: String) = virsh("define", "/dev/stdin", stdin = xml)

    /** Shell command that attaches to the guest's serial console; run it in a PTY. */
    fun consoleCommand(name: String): String = virshCommand(listOf("console", "--force", name))

    suspend fun vncDisplay(name: String): String? =
        runCatching { virsh("vncdisplay", name).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    suspend fun changeMedia(name: String, target: String, isoPath: String?) {
        if (isoPath.isNullOrBlank()) virsh("change-media", name, target, "--eject", "--force")
        else virsh("change-media", name, target, isoPath, "--insert", "--force")
    }

    // --- Snapshots -------------------------------------------------------------------------

    suspend fun snapshots(name: String): List<Snapshot> {
        val current = runCatching { virsh("snapshot-current", "--name", name).trim() }.getOrNull()
        return Parsers.parseSnapshotList(virshTable("snapshot-list", name), current)
    }

    suspend fun createSnapshot(name: String, snapshot: String, description: String?) {
        val args = mutableListOf("snapshot-create-as", name, snapshot)
        if (!description.isNullOrBlank()) args += listOf("--description", description)
        virsh(*args.toTypedArray())
    }

    suspend fun revertSnapshot(name: String, snapshot: String) = virsh("snapshot-revert", name, snapshot)
    suspend fun deleteSnapshot(name: String, snapshot: String) = virsh("snapshot-delete", name, snapshot)

    // --- Networks --------------------------------------------------------------------------

    suspend fun networks(): List<VirtualNetwork> = Parsers.parseNetList(virshTable("net-list", "--all"))
    suspend fun networkDetails(name: String) = Parsers.parseNetworkXml(virsh("net-dumpxml", name))
    suspend fun startNetwork(name: String) = virsh("net-start", name)
    suspend fun stopNetwork(name: String) = virsh("net-destroy", name)
    suspend fun setNetworkAutostart(name: String, enabled: Boolean) =
        if (enabled) virsh("net-autostart", name) else virsh("net-autostart", "--disable", name)

    // --- Storage ---------------------------------------------------------------------------

    suspend fun pools(): List<StoragePool> = Parsers.parsePoolList(virshTable("pool-list", "--all", "--details"))
    suspend fun volumes(pool: String): List<StorageVolume> = Parsers.parseVolList(virshTable("vol-list", pool, "--details"))
    suspend fun startPool(name: String) = virsh("pool-start", name)
    suspend fun stopPool(name: String) = virsh("pool-destroy", name)
    suspend fun refreshPool(name: String) = virsh("pool-refresh", name)
    suspend fun deleteVolume(pool: String, volume: String) = virsh("vol-delete", "--pool", pool, volume)

    suspend fun createVolume(pool: String, name: String, sizeGiB: Int, format: String = "qcow2"): String {
        virsh("vol-create-as", pool, name, "${sizeGiB}G", "--format", format)
        return virsh("vol-path", "--pool", pool, name).trim()
    }

    /** A volume name based on [base] (e.g. "vm.qcow2") that doesn't exist in [pool] yet: vm.qcow2, vm-1.qcow2, ... */
    suspend fun uniqueVolumeName(pool: String, base: String): String {
        val taken = runCatching { volumes(pool).map { it.name }.toSet() }.getOrDefault(emptySet())
        if (base !in taken) return base
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(1) { it + 1 }.map { "$stem-$it$ext" }.first { it !in taken }
    }

    // --- Host interfaces -------------------------------------------------------------------

    /**
     * Linux bridges on the host (from sysfs, so no netcf/root needed), excluding the ones libvirt
     * creates for its own virtual networks. Null if the host couldn't be queried.
     */
    suspend fun hostBridges(libvirtBridges: Set<String> = emptySet()): List<String>? {
        val r = runner.run("for d in /sys/class/net/*/bridge; do [ -d \"\$d\" ] && basename \"\$(dirname \"\$d\")\"; done; true", null)
        if (!r.ok) return null
        return r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in libvirtBridges }.sorted()
    }

    // --- Editing definitions ---------------------------------------------------------------

    /** Re-points a network card in the persistent definition (takes effect on next boot). */
    suspend fun setInterface(name: String, index: Int, source: NetworkSource, model: String? = null) {
        defineXml(DomainXmlEditor.setInterface(dumpXml(name, inactive = true), index, source, model))
    }

    /** Saves user-edited XML for [name]; refuses edits that would create or rename a VM instead. */
    suspend fun redefine(name: String, uuid: String?, xml: String) {
        val (newName, newUuid) = DomainXmlEditor.identity(xml)
        if (newName != name) throw VirshException("Changing <name> here would create a new VM. Use Rename instead.")
        if (uuid != null && newUuid != null && !newUuid.equals(uuid, ignoreCase = true)) {
            throw VirshException("The <uuid> must not change.")
        }
        defineXml(xml)
    }

    /** All ISO images in active pools, used by the New VM wizard. */
    suspend fun isoImages(): List<StorageVolume> =
        pools().filter { it.active }.flatMap { pool ->
            runCatching { volumes(pool.name) }.getOrDefault(emptyList())
        }.filter { it.name.endsWith(".iso", ignoreCase = true) || it.path.endsWith(".iso", ignoreCase = true) }
}
