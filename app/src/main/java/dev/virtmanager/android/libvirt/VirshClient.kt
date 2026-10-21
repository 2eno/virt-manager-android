package dev.virtmanager.android.libvirt

import dev.virtmanager.android.libvirt.config.DomainCapabilities
import dev.virtmanager.android.libvirt.config.DomainConfig
import dev.virtmanager.android.libvirt.config.HostDevice
import dev.virtmanager.android.libvirt.config.HostInventory
import dev.virtmanager.android.libvirt.config.OsVariant
import dev.virtmanager.android.libvirt.config.SnapshotRepair

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

    /**
     * Runs many virsh commands in one virsh process (one libvirt connection), which is much
     * faster than one process per command on hosts with many devices or VMs. Each result is the
     * command's output, or null if it failed or printed nothing.
     */
    suspend fun script(commands: List<List<String>>): List<String?> {
        val marker = "__VMA_SEP__"
        val results = mutableListOf<String?>()
        // Keep each virsh argument well below the kernel's 128 KiB limit for one argument.
        for (chunk in commands.chunked(150)) {
            val text = chunk.joinToString(" ; ") { cmd -> cmd.joinToString(" ") { virshQuote(it) } + " ; echo $marker" }
            val r = runner.run(virshCommand(listOf(text)), null)
            // virsh's echo doesn't end the line, so split on the marker rather than by line.
            val parts = r.stdout.split(marker)
            for (i in chunk.indices) results += parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return results
    }

    /** Quoting for virsh's own command parser (used inside [script]). */
    private fun virshQuote(arg: String): String =
        if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "_-.:/@+=,%" }) arg
        else "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
    /**
     * The domain XML. The persistent ([inactive]) form is what edits are based on, so it includes
     * secrets such as VNC/SPICE passwords: without `--security-info` they would be silently lost
     * when the edited XML is defined again.
     */
    suspend fun dumpXml(name: String, inactive: Boolean = false): String =
        if (inactive) virsh("dumpxml", "--inactive", "--security-info", name) else virsh("dumpxml", name)

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
        val def = Parsers.parseDomainXml(dumpXml(name, inactive = true))
        val wanted = def.interfaces.filter { it.type == "network" }.mapNotNull { it.source }.toSet()
        val started = if (wanted.isEmpty()) emptyList() else networks().filter { it.name in wanted && !it.active }.map { it.name }
        started.forEach { startNetwork(it) }
        start(name)
        // The first start is the installation beginning: the VM is no longer "being set up".
        if (def.setupPending) runCatching { clearSetupMarker(name) }
        return started
    }

    /**
     * Folders above the VM's image files that QEMU's user can't pass through, which makes the
     * start fail with "Permission denied"; null if there are none or it can't be told.
     */
    suspend fun qemuAccessProblem(name: String): QemuAccess.Problem? {
        val def = Parsers.parseDomainXml(dumpXml(name, inactive = true))
        val paths = def.disks.filter { it.type == "file" }.mapNotNull { it.source }.filter { it.startsWith("/") }.distinct()
        if (paths.isEmpty()) return null
        val (uid, gid) = runCatching { QemuAccess.qemuIdentity(virsh("capabilities"), def.domainType) }.getOrNull() ?: return null
        val r = runner.run(QemuAccess.script(uid, paths), null)
        return if (r.ok) QemuAccess.parse(r.stdout, uid, gid) else null
    }

    /**
     * Why QEMU was refused [path] when its folders are fine: the file's own permissions, or
     * AppArmor. Null if QEMU's user can't be told.
     */
    suspend fun qemuFileProblem(name: String, path: String): QemuAccess.FileProblem? {
        val def = Parsers.parseDomainXml(dumpXml(name, inactive = true))
        val (uid, gid) = runCatching { QemuAccess.qemuIdentity(virsh("capabilities"), def.domainType) }.getOrNull() ?: return null
        val ids = runner.run("u=\$(getent passwd $uid | cut -d: -f1); echo \"\$u\"; [ -n \"\$u\" ] && id -G \"\$u\"", null).stdout.lines()
        val user = ids.getOrNull(0)?.trim()?.ifEmpty { null } ?: uid.toString()
        val gids = ids.getOrNull(1).orEmpty().split(' ').mapNotNull { it.trim().toIntOrNull() }.toSet() + gid
        val r = runner.run(QemuAccess.fileCheckScript(path), null)
        return QemuAccess.parseFileCheck(r.stdout, path, user, uid, gids)
    }

    /**
     * Lets QEMU's user pass through the folders of [problem] (search permission only, as
     * virt-manager does). Returns the folders that couldn't be changed, with the reason.
     */
    suspend fun grantQemuSearch(problem: QemuAccess.Problem): List<Pair<String, String>> = problem.dirs.mapNotNull { dir ->
        val cmd = "setfacl -m u:${problem.uid}:x -- ${Shell.quote(dir)}"
        var r = runner.run(cmd, null)
        // Folders the SSH user doesn't own need root.
        if (!r.ok && useSudo) r = runner.run("sudo -n $cmd", null)
        if (r.ok) null else dir to cleanError(r.stderr.ifBlank { r.stdout }).let {
            if (it.contains("not found", true)) "setfacl isn't installed on the host (package \"acl\")" else it
        }
    }

    suspend fun clearSetupMarker(name: String) = virsh("metadata", name, SetupMarker.NS, "--config", "--remove")

    /** Which of the (shut off) VMs [names] still have their setup pending; one virsh call. */
    suspend fun pendingSetups(names: List<String>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val out = script(names.map { listOf("metadata", it, SetupMarker.NS, "--config") })
        return names.zip(out).filter { it.second != null }.map { it.first }.toSet()
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

    /** What [deleteDomain] did; [notDeleted] lists files that are still on the host and why. */
    data class DeleteReport(val deleted: List<String>, val notDeleted: List<Pair<String, String>>)

    /**
     * Deletes a VM like virt-manager does, step by step so a failure part-way is reported
     * accurately: force it off, remove the definition (with managed save, snapshot/checkpoint
     * metadata, NVRAM and TPM state), then delete each file in [deletePaths] through libvirt.
     * Files that libvirt doesn't manage, or that are in a stopped pool, are reported rather than
     * silently left behind.
     */
    suspend fun deleteDomain(name: String, deletePaths: List<String> = emptyList()): DeleteReport {
        if (runCatching { domInfo(name).state.isActive }.getOrDefault(false)) runCatching { destroy(name) }
        undefine(name)
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        var refreshed = false
        for (path in deletePaths.distinct()) {
            var r = runner.run(virshCommand(listOf("vol-delete", path)), null)
            // Snapshot overlays and memory files aren't pool volumes until the pool is refreshed.
            if (!r.ok && !refreshed && fileExists(path)) {
                refreshed = true
                runCatching { pools().filter { it.active }.forEach { refreshPool(it.name) } }
                r = runner.run(virshCommand(listOf("vol-delete", path)), null)
            }
            when {
                r.ok -> deleted += path
                !fileExists(path) -> Unit // already gone
                else -> failed += path to cleanError(r.stderr).let { err ->
                    if (err.contains("no storage vol", true) || err.contains("not found", true)) "not in an active storage pool" else err
                }
            }
        }
        return DeleteReport(deleted, failed)
    }

    private suspend fun fileExists(path: String): Boolean =
        runner.run("test -e ${Shell.quote(path)}", null).ok

    /**
     * Removes the definition. Newer flags are dropped one step at a time for older libvirt, and
     * after every failure we check whether the VM is actually gone (a later step can fail after
     * the definition was removed).
     */
    suspend fun undefine(name: String) {
        val flagSets = listOf(
            listOf("--managed-save", "--snapshots-metadata", "--checkpoints-metadata", "--nvram", "--tpm"),
            listOf("--managed-save", "--snapshots-metadata", "--nvram"),
            listOf("--nvram"),
            emptyList(),
        )
        var lastError = "undefine failed"
        for (flags in flagSets) {
            val r = runner.run(virshCommand(listOf("undefine", name) + flags), null)
            if (r.ok || !exists(name)) return
            lastError = cleanError(r.stderr.ifBlank { r.stdout })
            val unsupported = lastError.contains("doesn't support option", true) || lastError.contains("unsupported flags", true) ||
                lastError.contains("unknown option", true)
            if (!unsupported) throw VirshException(lastError)
        }
        throw VirshException(lastError)
    }

    suspend fun exists(name: String): Boolean = runner.run(virshCommand(listOf("domuuid", name)), null).ok

    /** A disk image that deleting the VM can remove; [sharedWith] lists other VMs using it. */
    data class DiskCandidate(val target: String, val path: String, val sharedWith: List<String>)

    /**
     * The VM's own disk images (never CD-ROM or floppy media such as install ISOs), read from the
     * saved configuration at the moment of asking, with the other VMs that use each one.
     */
    suspend fun deletionCandidates(name: String): List<DiskCandidate> {
        val disks = Parsers.parseDomainXml(dumpXml(name, inactive = true)).disks
            .filter { it.device == "disk" && it.target != null && it.source != null && it.type in setOf("file", "block", "volume") }
        // A <source pool= volume=> disk is offered by its path, which is what vol-delete takes;
        // other VMs may refer to it either way.
        val resolved = disks.mapNotNull { d ->
            val path = if (d.type == "volume") volumePath(d.source!!) else d.source!!
            path?.let { Triple(d.target!!, it, setOf(it, d.source)) }
        }
        // External snapshots leave the older images and memory files behind the current disk.
        val diskTargets = resolved.map { it.first }.toSet()
        val snapshotNames = runCatching { virsh("snapshot-list", "--name", name).lines().map { it.trim() }.filter { it.isNotEmpty() } }
            .getOrDefault(emptyList())
        val extra = snapshotFiles(name, snapshotNames).flatMap { (snap, f) ->
            (f.overlays + f.base).filterKeys { it in diskTargets }.map { (t, p) -> Triple(t, p, setOf(p)) } +
                listOfNotNull(f.memory?.let { Triple("memory of $snap", it, setOf(it)) })
        }
        val all = (resolved + extra).distinctBy { it.second }
        val users = diskUsers(name, all.flatMap { it.third })
        return all.map { (target, path, names) -> DiskCandidate(target, path, names.flatMap { users[it].orEmpty() }.distinct()) }
    }

    /** The file path of a volume given as "pool/volume", or null if it can't be found. */
    private suspend fun volumePath(poolAndVolume: String): String? {
        val pool = poolAndVolume.substringBefore('/')
        val vol = poolAndVolume.substringAfter('/')
        return runCatching { virsh("vol-path", "--pool", pool, vol).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * For each of [paths], the other VMs whose (persistent) configuration uses it, so shared
     * images aren't deleted along with one VM.
     */
    suspend fun diskUsers(except: String, paths: List<String>): Map<String, List<String>> {
        if (paths.isEmpty()) return emptyMap()
        val names = virsh("list", "--all", "--name").lines().map { it.trim() }.filter { it.isNotEmpty() && it != except }
        val xmls = script(names.map { listOf("dumpxml", "--inactive", it) })
        val usage = mutableMapOf<String, MutableList<String>>()
        names.zip(xmls).forEach { (vm, xml) ->
            val sources = xml?.let { runCatching { Parsers.parseDomainXml(it).disks.mapNotNull { d -> d.source } }.getOrNull() }.orEmpty()
            paths.filter { it in sources }.forEach { usage.getOrPut(it) { mutableListOf() } += vm }
        }
        return usage
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
        val list = Parsers.parseSnapshotList(virshTable("snapshot-list", name), current)
        val files = snapshotFiles(name, list.map { it.name })
        return list.map { s -> s.copy(external = files[s.name]?.external == true) }
    }

    private suspend fun snapshotFiles(name: String, snapshots: List<String>): Map<String, SnapshotFiles> {
        if (snapshots.isEmpty()) return emptyMap()
        val xmls = script(snapshots.map { listOf("snapshot-dumpxml", name, it) })
        return snapshots.zip(xmls).mapNotNull { (s, xml) -> xml?.let { runCatching { s to Parsers.parseSnapshotXml(it) }.getOrNull() } }.toMap()
    }

    /** How [createSnapshot] stored a snapshot. */
    enum class SnapshotKind { INTERNAL, EXTERNAL, DISKS_ONLY }

    /**
     * Takes a snapshot like virt-manager: internal (inside the qcow2 images) where libvirt
     * allows it, else external. Internal ones are impossible for running UEFI VMs whose NVRAM
     * isn't qcow2, which is libvirt's default; then the disks get overlay files and the memory
     * goes to a file next to the first disk. A VM with passed-through PCI devices (VFIO) can't
     * have its memory saved at all, so it gets the disks only, as after a power cut.
     */
    suspend fun createSnapshot(name: String, snapshot: String, description: String?): SnapshotKind {
        val base = mutableListOf("snapshot-create-as", name, snapshot)
        if (!description.isNullOrBlank()) base += listOf("--description", description)
        try {
            virsh(*base.toTypedArray())
            return SnapshotKind.INTERNAL
        } catch (e: VirshException) {
            if (!isInternalSnapshotUnsupported(e.message)) throw e
        }
        val def = Parsers.parseDomainXml(dumpXml(name))
        val running = runCatching { domInfo(name).state.isActive }.getOrDefault(false)
        if (running) {
            try {
                virsh(*externalSnapshotArgs(base, def, name, snapshot, withMemory = true).toTypedArray())
                return SnapshotKind.EXTERNAL
            } catch (e: VirshException) {
                if (!isMemoryUnsaveable(e.message)) throw e
            }
        }
        virsh(*externalSnapshotArgs(base, def, name, snapshot, withMemory = false).toTypedArray())
        return if (running) SnapshotKind.DISKS_ONLY else SnapshotKind.EXTERNAL
    }

    // --- Snapshots helpers ---------------------------------------------------------------

    /** Arguments for an external snapshot: file disks get overlays, other devices are skipped. */
    internal fun externalSnapshotArgs(base: List<String>, def: DomainDefinition, name: String, snapshot: String, withMemory: Boolean): List<String> {
        val args = base.toMutableList()
        val disks = def.disks.filter { it.target != null }
        val withOverlay = disks.filter { it.device == "disk" && !it.readOnly && it.type == "file" && it.source != null }
        if (withMemory) {
            val dir = withOverlay.firstOrNull()?.source?.substringBeforeLast('/') ?: "/var/lib/libvirt/qemu/snapshot"
            args += listOf("--memspec", "file=$dir/$name-$snapshot.mem,snapshot=external")
        } else {
            args += "--disk-only"
        }
        disks.forEach { d -> args += listOf("--diskspec", "${d.target},snapshot=" + if (d in withOverlay) "external" else "no") }
        return args
    }

    /** Saving memory works like a migration, which passed-through devices (VFIO) don't support. */
    private fun isMemoryUnsaveable(message: String?): Boolean {
        val m = message ?: return false
        return m.contains("cannot migrate", true) || m.contains("migration is not supported", true) ||
            (m.contains("VFIO", true) && m.contains("migrat", true))
    }

    private fun isInternalSnapshotUnsupported(message: String?): Boolean {
        val m = message ?: return false
        return m.contains("internal snapshot", true) && (m.contains("not supported", true) || m.contains("require", true))
    }

    suspend fun revertSnapshot(name: String, snapshot: String) {
        virsh("snapshot-revert", name, snapshot)
        // Best effort: a failed repair must not make the revert look failed.
        runCatching { restoreSnapshotMedia(name, snapshot) }
    }

    suspend fun deleteSnapshot(name: String, snapshot: String) {
        restoreSnapshotMedia(name, snapshot)
        virsh("snapshot-delete", name, snapshot)
    }

    /**
     * Repairs what reverting to an external snapshot does to CD-ROMs (see [SnapshotRepair]):
     * the snapshot's metadata gets its media disks back to "not snapshotted", and the saved
     * configuration its ISO with the right format. The running VM keeps its CD-ROM until it
     * is shut down. Returns whether anything was repaired.
     */
    suspend fun restoreSnapshotMedia(name: String, snapshot: String): Boolean {
        val snapXml = runCatching { virsh("snapshot-dumpxml", "--security-info", name, snapshot) }.getOrNull() ?: return false
        var repaired = false
        SnapshotRepair.fixSnapshot(snapXml)?.let { fixed ->
            virsh("snapshot-create", name, "/dev/stdin", "--redefine", stdin = fixed)
            repaired = true
        }
        SnapshotRepair.fixDomain(dumpXml(name, inactive = true), snapXml)?.let { fixed ->
            defineXml(fixed)
            repaired = true
        }
        return repaired
    }

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

    /** Saves user-edited XML for [name]; refuses edits that would create or rename a VM instead. */
    suspend fun redefine(name: String, uuid: String?, xml: String) {
        val (newName, newUuid) = DomainXmlEditor.identity(xml)
        if (newName != name) throw VirshException("Changing <name> here would create a new VM. Use Rename instead.")
        if (uuid != null && newUuid != null && !newUuid.equals(uuid, ignoreCase = true)) {
            throw VirshException("The <uuid> must not change.")
        }
        defineXml(xml)
    }

    // --- Full configuration editing (virt-manager's details window) ------------------------

    suspend fun inactiveConfig(name: String) = DomainConfig.parse(dumpXml(name, inactive = true))

    /** Saves an edited configuration of [name]; the identity checks of [redefine] apply. */
    suspend fun saveConfig(name: String, config: DomainConfig) = redefine(name, config.uuid, config.toXml())

    /**
     * Host USB or PCI devices that can be passed through, in address order. Every device libvirt
     * reports is returned (virt-manager shows them all too); [HostDevice.isBridge] marks the ones
     * that make no sense to assign.
     *
     * PCI devices are also read from sysfs: libvirt silently leaves out any PCI device whose
     * config space it fails to read (typically a GPU that stopped answering after a failed reset),
     * and such devices are exactly the ones people look for. They come with
     * [HostDevice.inLibvirt] false.
     */
    suspend fun hostDevices(type: String): List<HostDevice> {
        val cap = if (type == "usb") "usb_device" else "pci"
        val listed = runCatching { virsh("nodedev-list", "--cap", cap).lines().map { it.trim() }.filter { it.isNotEmpty() } }
        val names = listed.getOrDefault(emptyList())
        val xmls = if (names.isEmpty()) emptyList() else script(names.map { listOf("nodedev-dumpxml", it) })
        val fromLibvirt = names.zip(xmls).map { (name, xml) ->
            // Never drop a device just because its details couldn't be read.
            xml?.let(HostInventory::parseNodedev) ?: HostInventory.placeholder(type, name)
        }
        val merged = if (type == "pci") HostInventory.mergePci(fromLibvirt, sysfsPciDevices()) else fromLibvirt
        // Nothing from either source: report why libvirt couldn't list devices.
        if (merged.isEmpty()) listed.getOrThrow()
        val devices = merged.filterNot { it.type == "usb" && it.vendorId == "0x1d6b" } // root hubs can't be passed through
        // Identical USB devices can only be told apart by bus/device address.
        val dupes = devices.filter { it.type == "usb" }.groupBy { "${it.vendorId}:${it.productId}" }.filter { it.value.size > 1 }.keys
        return devices.map { d ->
            if (d.type == "usb" && "${d.vendorId}:${d.productId}" in dupes) d.copy(settings = d.settings.copy(pinUsbAddress = true)) else d
        }.sortedBy { it.address }
    }

    private suspend fun sysfsPciDevices(): List<HostDevice> {
        val r = runner.run(HostInventory.SYSFS_PCI_SCRIPT, null)
        return if (r.ok) HostInventory.parseSysfsPci(r.stdout) else emptyList()
    }

    /** OS variants from the host's libosinfo, or a built-in list if it isn't installed. */
    suspend fun osVariants(): List<OsVariant> {
        val r = runner.run("osinfo-query os -f short-id,name,id 2>/dev/null", null)
        val parsed = if (r.ok) HostInventory.parseOsinfoQuery(r.stdout) else emptyList()
        return parsed.ifEmpty { HostInventory.fallbackOs }.sortedBy { it.name.lowercase() }
    }

    /** Hypervisor capabilities for a VM like [config] (virt type, arch and machine). */
    suspend fun domainCapabilities(config: DomainConfig): DomainCapabilities = runCatching {
        val args = mutableListOf("domcapabilities")
        config.domainType?.let { args += listOf("--virttype", it) }
        config.arch?.let { args += listOf("--arch", it) }
        config.machine?.let { args += listOf("--machine", it) }
        DomainCapabilities.parse(virsh(*args.toTypedArray()))
    }.getOrDefault(DomainCapabilities.UNKNOWN)

    suspend fun cpuModels(arch: String): List<String> =
        runCatching { virsh("cpu-models", arch).lines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    /** All ISO images in active pools, used by the New VM wizard. */
    suspend fun isoImages(): List<StorageVolume> =
        pools().filter { it.active }.flatMap { pool ->
            runCatching { volumes(pool.name) }.getOrDefault(emptyList())
        }.filter { it.name.endsWith(".iso", ignoreCase = true) || it.path.endsWith(".iso", ignoreCase = true) }
}
