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
import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.ui.components.deletableDisks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    /** Created with "Customize configuration before install" and not started yet. */
    val customizing: Boolean = false,
    /** Set after Begin installation succeeds; the screen opens the console and clears it. */
    val openConsole: Boolean = false,
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
            _state.update { it.copy(xml = xml, definition = Parsers.parseDomainXml(xml)) }
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

    fun delete(deleteStorage: List<String>) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            val v = virsh()
            if (_state.value.stats?.state?.isActive == true) v.destroy(name)
            v.undefine(name, deleteStorage)
            _state.update { it.copy(deleted = true) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t), busy = false) }
        }
    }

    fun loadSnapshots() = viewModelScope.launch {
        try {
            val snaps = virsh().snapshots(name)
            _state.update { it.copy(snapshots = snaps) }
        } catch (t: Throwable) {
            _state.update { it.copy(snapshots = it.snapshots ?: emptyList(), error = ConnectionManager.friendly(t)) }
        }
    }

    private fun snapshotOp(label: String, block: suspend VirshClient.() -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            virsh().block()
            _state.update { it.copy(message = label) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        } finally {
            _state.update { it.copy(busy = false) }
            loadSnapshots()
            poll(withInfo = true)
        }
    }

    fun createSnapshot(snap: String, description: String?) = snapshotOp("Snapshot \"$snap\" created") { createSnapshot(name, snap, description) }
    fun revertSnapshot(snap: String) = snapshotOp("Reverted to \"$snap\"") { revertSnapshot(name, snap) }
    fun deleteSnapshot(snap: String) = snapshotOp("Snapshot \"$snap\" deleted") { deleteSnapshot(name, snap) }

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
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun consoleOpened() = _state.update { it.copy(openConsole = false) }

    /** virt-manager's "Cancel installation": removes the VM and the disk images it was given. */
    fun cancelInstallation() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            // Read the saved configuration now: disks may have been added while customizing.
            val v = virsh()
            val disks = deletableDisks(Parsers.parseDomainXml(v.dumpXml(name, inactive = true)).disks).mapNotNull { it.target }
            v.undefine(name, disks)
            _state.update { it.copy(deleted = true) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t), busy = false) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() = stopPolling()
}
