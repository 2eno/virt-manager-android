package dev.virtmanager.android.ui.hw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.StoragePool
import dev.virtmanager.android.libvirt.StorageVolume
import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.libvirt.VirtualNetwork
import dev.virtmanager.android.libvirt.config.DomainCapabilities
import dev.virtmanager.android.libvirt.config.DomainConfig
import dev.virtmanager.android.libvirt.config.HostDevice
import dev.virtmanager.android.libvirt.config.OsVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfigState(
    val config: DomainConfig? = null,
    /** Bumped on every (re)load so forms re-initialise from the saved configuration. */
    val version: Int = 0,
    val caps: DomainCapabilities = DomainCapabilities.UNKNOWN,
    val state: DomainState = DomainState.NO_STATE,
    val autostart: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Set when an apply/add/remove finished; pages navigate back on it. */
    val done: Boolean = false,
    // Lazily loaded host data for pickers.
    val osVariants: List<OsVariant>? = null,
    val cpuModels: List<String>? = null,
    val networks: List<VirtualNetwork>? = null,
    val bridges: List<String>? = null,
    val pools: List<StoragePool>? = null,
    val volumes: Map<String, List<StorageVolume>> = emptyMap(),
    val hostDevices: Map<String, List<HostDevice>> = emptyMap(),
) {
    val running: Boolean get() = state.isActive
}

/**
 * Loads a VM's persistent configuration and saves edits to it, for the Hardware list and every
 * details page. Each save re-reads the current XML first, so concurrent changes made elsewhere
 * (virsh, virt-manager on a desktop) aren't overwritten with stale data.
 */
class ConfigViewModel(
    private val app: VirtManagerApp,
    private val connectionId: String,
    private val name: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ConfigState())
    val state = _state.asStateFlow()

    private suspend fun virsh(): VirshClient = app.connections.connect(connectionId).virsh

    init { reload() }

    fun reload() = viewModelScope.launch {
        try {
            val v = virsh()
            val cfg = v.inactiveConfig(name)
            val info = runCatching { v.domInfo(name) }.getOrNull()
            val caps = if (_state.value.caps === DomainCapabilities.UNKNOWN) v.domainCapabilities(cfg) else _state.value.caps
            _state.update {
                it.copy(
                    config = cfg, version = it.version + 1, caps = caps,
                    state = info?.state ?: it.state, autostart = info?.autostart ?: it.autostart,
                )
            }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
    }

    /**
     * Applies [edit] to a fresh copy of the saved configuration and defines it. [before] runs
     * first (e.g. creating a new disk image); [rollback] undoes it if libvirt rejects the result.
     */
    fun apply(
        message: String,
        before: (suspend VirshClient.() -> Unit)? = null,
        rollback: (suspend VirshClient.() -> Unit)? = null,
        after: (suspend VirshClient.() -> Unit)? = null,
        edit: DomainConfig.() -> Unit,
    ) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val v = try { virsh() } catch (t: Throwable) {
            _state.update { it.copy(busy = false, error = ConnectionManager.friendly(t)) }
            return@launch
        }
        var prepared = false
        try {
            before?.invoke(v)
            prepared = true
            val cfg = v.inactiveConfig(name)
            cfg.edit()
            v.saveConfig(name, cfg)
            after?.invoke(v)
            val note = if (_state.value.running) " (takes effect after the next shutdown and start)" else ""
            _state.update { it.copy(busy = false, message = message + note, done = true) }
            reload()
        } catch (t: Throwable) {
            if (prepared) rollback?.let { runCatching { it(v) } }
            _state.update { it.copy(busy = false, error = t.message ?: ConnectionManager.friendly(t)) }
        }
    }

    fun setAutostart(on: Boolean) = viewModelScope.launch {
        try {
            virsh().setAutostart(name, on)
            _state.update { it.copy(autostart = on) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
    }

    fun loadOsVariants() = loadOnce(_state.value.osVariants) { v -> _state.update { it.copy(osVariants = v.osVariants()) } }

    fun loadCpuModels() = loadOnce(_state.value.cpuModels) { v ->
        val arch = _state.value.config?.arch ?: "x86_64"
        _state.update { it.copy(cpuModels = v.cpuModels(arch)) }
    }

    fun loadNetworks() = loadOnce(_state.value.networks) { v ->
        val nets = v.networks()
        val own = nets.mapNotNull { n -> runCatching { v.networkDetails(n.name).bridge }.getOrNull() }.toSet()
        _state.update { it.copy(networks = nets, bridges = v.hostBridges(own)) }
    }

    fun loadPools() = loadOnce(_state.value.pools) { v -> _state.update { it.copy(pools = v.pools().filter { p -> p.active }) } }

    fun loadVolumes(pool: String) = viewModelScope.launch {
        runCatching { virsh().volumes(pool) }
            .onSuccess { vols -> _state.update { it.copy(volumes = it.volumes + (pool to vols)) } }
            .onFailure { t -> _state.update { it.copy(error = ConnectionManager.friendly(t)) } }
    }

    fun loadHostDevices(type: String) = loadOnce(_state.value.hostDevices[type]) { v ->
        val devs = v.hostDevices(type)
        _state.update { it.copy(hostDevices = it.hostDevices + (type to devs)) }
    }

    private fun loadOnce(current: Any?, block: suspend (VirshClient) -> Unit) {
        if (current != null) return
        viewModelScope.launch {
            try { block(virsh()) } catch (t: Throwable) { _state.update { it.copy(error = ConnectionManager.friendly(t)) } }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeDone() = _state.update { it.copy(done = false) }
}
