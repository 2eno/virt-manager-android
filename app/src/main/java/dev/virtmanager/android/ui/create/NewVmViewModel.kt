package dev.virtmanager.android.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.libvirt.NewVmSpec
import dev.virtmanager.android.libvirt.StoragePool
import dev.virtmanager.android.libvirt.StorageVolume
import dev.virtmanager.android.libvirt.VirtualNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewVmEnv(
    val loading: Boolean = true,
    val arch: String = "x86_64",
    val hostCpus: Int = 4,
    val hostMemMiB: Long = 8192,
    val existingNames: Set<String> = emptySet(),
    val pools: List<StoragePool> = emptyList(),
    val networks: List<VirtualNetwork> = emptyList(),
    val isos: List<StorageVolume> = emptyList(),
    val progress: String? = null,
    val error: String? = null,
    val created: String? = null,
)

class NewVmViewModel(private val app: VirtManagerApp, private val connectionId: String) : ViewModel() {
    private val _env = MutableStateFlow(NewVmEnv())
    val env = _env.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val v = app.connections.connect(connectionId).virsh
                val details = v.hostDetails()
                val pools = runCatching { v.pools() }.getOrDefault(emptyList())
                val nets = runCatching { v.networks() }.getOrDefault(emptyList())
                val isos = runCatching { v.isoImages() }.getOrDefault(emptyList())
                val names = runCatching { v.pollHost().domains.map { it.name }.toSet() }.getOrDefault(emptySet())
                _env.update {
                    it.copy(
                        loading = false,
                        arch = details.node?.cpuModel?.takeIf { m -> m == "aarch64" || m == "x86_64" } ?: "x86_64",
                        hostCpus = details.node?.cpus ?: 4,
                        hostMemMiB = (details.node?.memoryKiB ?: (8L * 1024 * 1024)) / 1024,
                        pools = pools.filter { p -> p.active },
                        networks = nets,
                        isos = isos,
                        existingNames = names,
                    )
                }
            } catch (t: Throwable) {
                _env.update { it.copy(loading = false, error = ConnectionManager.friendly(t)) }
            }
        }
    }

    fun create(spec: NewVmSpec, diskGiB: Int?, pool: String?, start: Boolean) = viewModelScope.launch {
        try {
            val v = app.connections.connect(connectionId).virsh
            var diskPath: String? = null
            if (diskGiB != null && pool != null) {
                _env.update { it.copy(progress = "Creating ${diskGiB} GiB disk in \"$pool\"…", error = null) }
                diskPath = v.createVolume(pool, "${spec.name}.qcow2", diskGiB)
            }
            _env.update { it.copy(progress = "Defining virtual machine…") }
            v.defineXml(DomainXmlBuilder.build(spec.copy(diskPath = diskPath)))
            if (start) {
                _env.update { it.copy(progress = "Starting ${spec.name}…") }
                v.start(spec.name)
            }
            _env.update { it.copy(progress = null, created = spec.name) }
        } catch (t: Throwable) {
            _env.update { it.copy(progress = null, error = ConnectionManager.friendly(t)) }
        }
    }

    fun dismissError() = _env.update { it.copy(error = null) }
}
