package dev.virtmanager.android.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.data.DomainMetrics
import dev.virtmanager.android.data.MetricsTracker
import dev.virtmanager.android.libvirt.DomainDefinition
import dev.virtmanager.android.libvirt.DomainInfo
import dev.virtmanager.android.libvirt.DomainStats
import dev.virtmanager.android.libvirt.Snapshot
import dev.virtmanager.android.libvirt.Parsers
import dev.virtmanager.android.libvirt.QemuAccess
import dev.virtmanager.android.libvirt.VirshClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VmUiState(
    val name: String,
    val info: DomainInfo? = null,
    val stats: DomainStats? = null,
    val metrics: DomainMetrics = DomainMetrics(),
    val definition: DomainDefinition? = null,
    val xml: String? = null,
    val snapshots: List<Snapshot>? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val deleted: Boolean = false,
    /** Disk images offered by the delete dialog; null while loading. */
    val deleteCandidates: List<VirshClient.DiskCandidate>? = null,
    val deleteLookupError: String? = null,
    /** Files a deletion couldn't remove, shown before leaving the page. */
    val deleteReport: VirshClient.DeleteReport? = null,
    /**
     * The VM's setup isn't finished (created with "Customize configuration before install" and
     * never started); comes from the marker in its metadata, so it survives leaving the page.
     */
    val customizing: Boolean = false,
    /** Set after Begin installation succeeds; the screen opens the console and clears it. */
    val openConsole: Boolean = false,
    /** A snapshot that can only be deleted with the VM shut off. */
    val snapshotNeedsShutdown: String? = null,
    /** QEMU was refused an image file itself; shows the commands to fix it on the host. */
    val fileProblem: QemuAccess.FileProblem? = null,
    /** A start failed because QEMU can't reach image files; offered to fix with setfacl. */
    val accessProblem: QemuAccess.Problem? = null,
)


class VmDetailViewModel(
    private val app: VirtManagerApp,
    private val connectionId: String,
    initialName: String,
    notice: String? = null,
    customize: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(VmUiState(initialName, error = notice, customizing = customize))
    val state = _state.asStateFlow()
    private val tracker = MetricsTracker()
    private var pollJob: Job? = null
    private val name get() = _state.value.name

    private suspend fun virsh(): VirshClient = app.connections.connect(connectionId).virsh

    init {
        reloadDefinition()
        // The wizard's start failed: it may be QEMU being refused the ISO's folder.
        if (QemuAccess.isPermissionError(notice)) diagnoseAccess(notice)
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        // Coming back from a hardware page: the configuration may have changed.
        reloadDefinition()
        pollJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                poll(withInfo = tick % 5 == 0)
                tick++
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll(withInfo: Boolean) {
        try {
            val v = virsh()
            val stats = v.domStats(name)
            val metrics = stats?.let { tracker.update(listOf(it), System.nanoTime())[name] } ?: DomainMetrics()
            val previousState = _state.value.stats?.state
            val info = if (withInfo || previousState != stats?.state) v.domInfo(name) else _state.value.info
            _state.update { it.copy(stats = stats, metrics = metrics, info = info) }
            // Live XML changes when a VM starts (VNC port, interface targets) — refresh it.
            if (previousState != null && stats != null && previousState != stats.state) reloadDefinition()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
    }

    fun reloadDefinition() = viewModelScope.launch {
        try {
            val xml = virsh().dumpXml(name)
            val def = Parsers.parseDomainXml(xml)
            _state.update { it.copy(xml = xml, definition = def, customizing = def.setupPending) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
    }

    private fun run(label: String?, reload: Boolean = true, block: suspend VirshClient.() -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            virsh().block()
            if (label != null) _state.update { it.copy(message = label) }
            poll(withInfo = true)
            if (reload) reloadDefinition()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
            if (QemuAccess.isPermissionError(t.message)) diagnoseAccess(t.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun action(label: String, action: suspend VirshClient.(String) -> Unit) = run(label) { action(name) }

    fun setAutostart(on: Boolean) = run(if (on) "Autostart enabled" else "Autostart disabled", reload = false) { setAutostart(name, on) }

    fun rename(newName: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            virsh().rename(name, newName)
            _state.update { it.copy(name = newName, message = "Renamed to $newName") }
            reloadDefinition()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Looks up the disk images the delete dialog offers (current config, shared-use check). */
    fun loadDeleteCandidates() = viewModelScope.launch {
        _state.update { it.copy(deleteCandidates = null, deleteLookupError = null) }
        try {
            _state.update { it.copy(deleteCandidates = virsh().deletionCandidates(name)) }
        } catch (t: Throwable) {
            _state.update { it.copy(deleteLookupError = ConnectionManager.friendly(t), deleteCandidates = emptyList()) }
        }
    }

    /** Deletes the VM and the disk images at [deletePaths]. */
    fun delete(deletePaths: List<String>) = viewModelScope.launch {
        // Finish even if the user leaves the page meanwhile: half a deletion helps nobody.
        withContext(NonCancellable) {
            _state.update { it.copy(busy = true, error = null) }
            stopPolling()
            try {
                val report = virsh().deleteDomain(name, deletePaths)
                _state.update { it.copy(busy = false, deleted = true, deleteReport = report.takeIf { r -> r.notDeleted.isNotEmpty() }) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = ConnectionManager.friendly(t), busy = false) }
                startPolling()
            }
        }
    }

    fun dismissDeleteReport() = _state.update { it.copy(deleteReport = null) }

    fun loadSnapshots() = viewModelScope.launch {
        try {
            val snaps = virsh().snapshots(name)
            _state.update { it.copy(snapshots = snaps) }
        } catch (t: Throwable) {
            _state.update { it.copy(snapshots = it.snapshots ?: emptyList(), error = ConnectionManager.friendly(t)) }
        }
    }

    /** Runs a snapshot action; [block] may return a message that replaces [label]. */
    private fun snapshotOp(label: String, block: suspend VirshClient.() -> String?) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val message = virsh().block() ?: label
            _state.update { it.copy(message = message) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        } finally {
            _state.update { it.copy(busy = false) }
            loadSnapshots()
            poll(withInfo = true)
        }
    }

    fun createSnapshot(snap: String, description: String?) = snapshotOp("Snapshot \"$snap\" created") {
        when (createSnapshot(name, snap, description)) {
            VirshClient.SnapshotKind.INTERNAL -> null
            VirshClient.SnapshotKind.EXTERNAL -> "Snapshot \"$snap\" created as an external snapshot (UEFI firmware rules out internal ones)"
            VirshClient.SnapshotKind.DISKS_ONLY ->
                "Snapshot \"$snap\" holds the disks only: the memory of a VM with passed-through PCI devices can't be saved"
        }
    }
    fun revertSnapshot(snap: String) = snapshotOp("Reverted to \"$snap\"") { revertSnapshot(name, snap); null }
    fun deleteSnapshot(snap: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            virsh().deleteSnapshot(name, snap)
            _state.update { it.copy(message = "Snapshot \"$snap\" deleted") }
        } catch (t: Throwable) {
            // A running VM merges an external snapshot itself (block-commit), which needs write
            // access to the image below it; hosts with AppArmor only give QEMU read access to
            // that one. Shut off, libvirt merges the files and it works.
            val running = _state.value.stats?.state?.isActive == true
            if (running && t.message?.contains("block-commit") == true) _state.update { it.copy(snapshotNeedsShutdown = snap) }
            else {
                _state.update { it.copy(error = ConnectionManager.friendly(t)) }
                if (QemuAccess.isPermissionError(t.message)) diagnoseAccess(t.message)
            }
        } finally {
            _state.update { it.copy(busy = false) }
            loadSnapshots()
            poll(withInfo = true)
        }
    }

    fun dismissSnapshotNeedsShutdown() = _state.update { it.copy(snapshotNeedsShutdown = null) }

    /** Shuts the guest down, waits until it is off (up to 3 minutes), then deletes [snap]. */
    fun shutDownAndDeleteSnapshot(snap: String) = viewModelScope.launch {
        _state.update { it.copy(snapshotNeedsShutdown = null, busy = true, message = "Shutting down to delete \"$snap\"…") }
        try {
            val v = virsh()
            v.shutdown(name)
            val deadline = System.currentTimeMillis() + 180_000
            while (v.domInfo(name).state.isActive) {
                if (System.currentTimeMillis() > deadline) {
                    _state.update { it.copy(error = "The guest didn't shut down within 3 minutes; shut it down, then delete the snapshot.") }
                    return@launch
                }
                delay(2000)
            }
            v.deleteSnapshot(name, snap)
            _state.update { it.copy(message = "Snapshot \"$snap\" deleted") }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        } finally {
            _state.update { it.copy(busy = false) }
            loadSnapshots()
            poll(withInfo = true)
        }
    }

    /** Starts the VM after customizing, bringing up any inactive virtual networks it uses first. */
    fun beginInstallation() = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            virsh().startWithNetworks(name)
            _state.update { it.copy(customizing = false, openConsole = true, message = "Installation started") }
            poll(withInfo = true)
            reloadDefinition()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
            if (QemuAccess.isPermissionError(t.message)) diagnoseAccess(t.message)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun diagnoseAccess(message: String?) = viewModelScope.launch {
        val folders = runCatching { virsh().qemuAccessProblem(name) }.getOrNull()
        if (folders != null) {
            _state.update { it.copy(accessProblem = folders) }
            return@launch
        }
        // The folders are fine: look at the refused file itself (owner/mode, AppArmor).
        val path = QemuAccess.refusedPath(message) ?: return@launch
        runCatching { virsh().qemuFileProblem(name, path) }.getOrNull()?.let { p -> _state.update { it.copy(fileProblem = p) } }
    }

    fun dismissFileProblem() = _state.update { it.copy(fileProblem = null) }

    fun dismissAccessProblem() = _state.update { it.copy(accessProblem = null) }

    /** Lets QEMU pass through the folders it was refused, then starts the VM again. */
    fun fixAccessAndStart() = viewModelScope.launch {
        val problem = _state.value.accessProblem ?: return@launch
        _state.update { it.copy(accessProblem = null, busy = true) }
        val failed = try {
            virsh().grantQemuSearch(problem)
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t), busy = false) }
            return@launch
        }
        _state.update { it.copy(busy = false) }
        if (failed.isNotEmpty()) {
            val why = failed.joinToString("; ") { (dir, reason) -> "$dir: $reason" }
            _state.update { it.copy(error = "Couldn't change the permissions ($why). Run this on the host: ${problem.command}") }
            return@launch
        }
        _state.update { it.copy(error = null) }
        if (_state.value.customizing) beginInstallation() else run("Started") { startWithNetworks(name) }
    }

    fun consoleOpened() = _state.update { it.copy(openConsole = false) }

    /**
     * virt-manager's "Cancel installation": removes the VM and the disk images it was given,
     * read from the saved configuration now (disks may have been added while customizing).
     * Images another VM also uses, and install media, are kept.
     */
    fun cancelInstallation() = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val paths = try {
            virsh().deletionCandidates(name).filter { it.sharedWith.isEmpty() }.map { it.path }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t), busy = false) }
            return@launch
        }
        delete(paths)
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() = stopPolling()
}
