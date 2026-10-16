package dev.virtmanager.android.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.data.DomainMetrics
import dev.virtmanager.android.data.HostCpuTracker
import dev.virtmanager.android.data.LiveConnection
import dev.virtmanager.android.data.MetricsTracker
import dev.virtmanager.android.libvirt.DomainStats
import dev.virtmanager.android.libvirt.NetworkDetails
import dev.virtmanager.android.libvirt.NodeMemStats
import dev.virtmanager.android.libvirt.StoragePool
import dev.virtmanager.android.libvirt.StorageVolume
import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.libvirt.VirtualNetwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HostUiState(
    val title: String = "",
    val uri: String = "",
    val loading: Boolean = true,
    val domains: List<DomainStats> = emptyList(),
    val metrics: Map<String, DomainMetrics> = emptyMap(),
    val details: VirshClient.HostDetails? = null,
    val hostCpuPercent: Double = 0.0,
    val hostCpuHistory: List<Float> = emptyList(),
    val hostMem: NodeMemStats? = null,
    val hostMemHistory: List<Float> = emptyList(),
    val networks: List<Pair<VirtualNetwork, NetworkDetails?>>? = null,
    val pools: List<StoragePool>? = null,
    val volumes: Map<String, List<StorageVolume>> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val error: String? = null,
    val message: String? = null,
)

class HostViewModel(private val app: VirtManagerApp, val connectionId: String) : ViewModel() {
    private val _state = MutableStateFlow(HostUiState())
    val state = _state.asStateFlow()

    private val tracker = MetricsTracker()
    private val cpuTracker = HostCpuTracker()
    private var pollJob: Job? = null

    private suspend fun conn(): LiveConnection = app.connections.connect(connectionId)

    init {
        viewModelScope.launch {
            app.repository.get(connectionId)?.let { p ->
                _state.update { it.copy(title = p.name, uri = p.displayUri) }
            }
            runCatching { conn().virsh.hostDetails() }
                .onSuccess { d -> _state.update { it.copy(details = d) } }
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                poll()
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll() {
        try {
            val snap = conn().virsh.pollHost()
            val metrics = tracker.update(snap.domains, System.nanoTime())
            snap.cpu?.let(cpuTracker::update)
            _state.update { s ->
                s.copy(
                    loading = false,
                    domains = snap.domains,
                    metrics = metrics,
                    hostCpuPercent = cpuTracker.percent,
                    hostCpuHistory = cpuTracker.history,
                    hostMem = snap.mem ?: s.hostMem,
                    hostMemHistory = snap.mem?.let { m ->
                        (s.hostMemHistory + (m.usedKiB.toFloat() / m.totalKiB.coerceAtLeast(1))).takeLast(60)
                    } ?: s.hostMemHistory,
                    error = null,
                )
            }
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, error = ConnectionManager.friendly(t)) }
        }
    }

    fun refresh() = viewModelScope.launch { poll() }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Runs a lifecycle action on a domain, tracking it as busy for the UI. */
    fun domainAction(name: String, label: String, action: suspend VirshClient.(String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + name) }
            try {
                conn().virsh.action(name)
                _state.update { it.copy(message = "$label: $name") }
                poll()
            } catch (t: Throwable) {
                _state.update { it.copy(error = "$label failed: ${ConnectionManager.friendly(t)}") }
            } finally {
                _state.update { it.copy(busy = it.busy - name) }
            }
        }
    }

    fun loadNetworks() = viewModelScope.launch {
        try {
            val v = conn().virsh
            val list = v.networks().map { n -> n to runCatching { v.networkDetails(n.name) }.getOrNull() }
            _state.update { it.copy(networks = list) }
        } catch (t: Throwable) {
            _state.update { it.copy(networks = it.networks ?: emptyList(), error = ConnectionManager.friendly(t)) }
        }
    }

    fun networkAction(name: String, action: suspend VirshClient.() -> Unit) = viewModelScope.launch {
        try {
            conn().virsh.action()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
        loadNetworks()
    }

    fun loadPools() = viewModelScope.launch {
        try {
            val pools = conn().virsh.pools()
            _state.update { it.copy(pools = pools) }
        } catch (t: Throwable) {
            _state.update { it.copy(pools = it.pools ?: emptyList(), error = ConnectionManager.friendly(t)) }
        }
    }

    fun loadVolumes(pool: String) = viewModelScope.launch {
        try {
            val vols = conn().virsh.volumes(pool)
            _state.update { it.copy(volumes = it.volumes + (pool to vols)) }
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
    }

    fun poolAction(name: String, action: suspend VirshClient.() -> Unit) = viewModelScope.launch {
        try {
            conn().virsh.action()
        } catch (t: Throwable) {
            _state.update { it.copy(error = ConnectionManager.friendly(t)) }
        }
        loadPools()
        if (_state.value.volumes.containsKey(name)) loadVolumes(name)
    }

    fun disconnect() = app.connections.disconnect(connectionId)

    override fun onCleared() {
        stopPolling()
    }
}
