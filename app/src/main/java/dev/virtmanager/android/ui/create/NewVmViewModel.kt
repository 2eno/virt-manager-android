package dev.virtmanager.android.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.libvirt.NetworkSource
import dev.virtmanager.android.libvirt.NewVmSpec
import dev.virtmanager.android.libvirt.StoragePool
import dev.virtmanager.android.libvirt.StorageVolume
import dev.virtmanager.android.libvirt.VirtualNetwork
import dev.virtmanager.android.libvirt.config.DomainCapabilities
import dev.virtmanager.android.libvirt.OsFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NewVmEnv(
    val loading: Boolean = true,
    val arch: String = "x86_64",
    val hostCpus: Int = 4,
    val hostMemMiB: Long = 8192,
    val existingNames: Set<String> = emptySet(),
    val pools: List<StoragePool> = emptyList(),
    val networks: List<VirtualNetwork> = emptyList(),
    val isos: List<StorageVolume> = emptyList(),
    /** Linux bridges on the host; null if they couldn't be listed (then any name is accepted). */
    val bridges: List<String>? = null,
    val virtType: String = "kvm",
    /** The hypervisor can emulate a TPM (swtpm installed); Windows 11 needs one. */
    val tpmEmulator: Boolean = false,
    /** qemu:///session and friends: no libvirt networks or bridges, user-mode networking instead. */
    val session: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val created: Created? = null,
)

/**
 * How creation ended. The VM exists in every case; [startError] is set when it was defined but
 * failed to start, so the user lands on its page with the reason instead of retrying the wizard.
 */
data class Created(val name: String, val customize: Boolean, val startError: String? = null)

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
                val libvirtBridges = nets.mapNotNull { n -> runCatching { v.networkDetails(n.name).bridge }.getOrNull() }.toSet()
                val bridges = runCatching { v.hostBridges(libvirtBridges) }.getOrNull()
                val virtType = if (v.supportsKvm()) "kvm" else "qemu"
                val caps = runCatching {
                    DomainCapabilities.parse(v.virsh("domcapabilities", "--virttype", virtType))
                }.getOrNull()
                val tpm = caps?.values("tpm", "backendModel")?.contains("emulator") == true
                val session = details.uri?.endsWith("/session") == true
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
                        bridges = bridges,
                        virtType = virtType,
                        tpmEmulator = tpm,
                        session = session,
                        existingNames = names,
                    )
                }
            } catch (t: Throwable) {
                _env.update { it.copy(loading = false, error = ConnectionManager.friendly(t)) }
            }
        }
    }

    /**
     * Creates the disk, defines the VM and optionally starts it. If defining fails, the new disk is
     * deleted again, so a retry starts from a clean slate. With [customize] the VM is only defined;
     * it is started later from its details page ("Begin installation").
     */
    fun create(spec: NewVmSpec, diskGiB: Int?, pool: String?, start: Boolean, customize: Boolean) = viewModelScope.launch {
        // Leaving the screen must not stop creation half-way (a disk without a VM, or a rollback
        // that never runs), so the steps below finish even if this ViewModel is cleared.
        withContext(NonCancellable) { createSteps(spec, diskGiB, pool, start, customize) }
    }

    private suspend fun createSteps(spec: NewVmSpec, diskGiB: Int?, pool: String?, start: Boolean, customize: Boolean) {
        _env.update { it.copy(error = null) }
        val v = try {
            app.connections.connect(connectionId).virsh
        } catch (t: Throwable) {
            _env.update { it.copy(error = ConnectionManager.friendly(t)) }
            return
        }
        var createdVolume: Pair<String, String>? = null
        val windows = spec.osFamily == OsFamily.WINDOWS
        try {
            preflight(spec)
            var diskPath: String? = null
            if (diskGiB != null && pool != null) {
                // Reuse-safe: a leftover image from an earlier attempt is never overwritten or reused.
                val volName = v.uniqueVolumeName(pool, "${spec.name}.qcow2")
                _env.update { it.copy(progress = "Creating $diskGiB GiB disk $volName…") }
                diskPath = v.createVolume(pool, volName, diskGiB)
                createdVolume = pool to volName
            }
            _env.update { it.copy(progress = "Defining virtual machine…") }
            v.defineXml(
                DomainXmlBuilder.build(
                    spec.copy(
                        diskPath = diskPath,
                        virtType = _env.value.virtType,
                        setupPending = customize,
                        tpm = windows && _env.value.tpmEmulator,
                    ),
                ),
            )
        } catch (t: Throwable) {
            createdVolume?.let { (p, vol) -> runCatching { v.deleteVolume(p, vol) } }
            _env.update { it.copy(progress = null, error = ConnectionManager.friendly(t)) }
            return
        }

        // From here on the VM exists; failures are reported on its page, not in the wizard.
        var startError: String? = null
        if (start && !customize) {
            try {
                _env.update { it.copy(progress = "Starting ${spec.name}…") }
                v.startWithNetworks(spec.name)
            } catch (t: Throwable) {
                startError = "Created, but it failed to start: ${ConnectionManager.friendly(t)}"
            }
        }
        _env.update { it.copy(progress = null, created = Created(spec.name, customize, startError)) }
    }

    /** Catches problems that would otherwise only surface after the disk and VM were created. */
    private fun preflight(spec: NewVmSpec) {
        when (val net = spec.network) {
            is NetworkSource.Bridge -> {
                if (net.bridge.isBlank()) throw IllegalArgumentException("Enter the name of a bridge on the host")
                val known = _env.value.bridges
                if (known != null && net.bridge !in known) {
                    throw IllegalArgumentException(
                        "The host has no bridge named \"${net.bridge}\"" +
                            if (known.isEmpty()) ". Use a virtual network instead." else " (found: ${known.joinToString()}).",
                    )
                }
            }
            is NetworkSource.Virtual ->
                if (_env.value.networks.none { it.name == net.network }) {
                    throw IllegalArgumentException("Virtual network \"${net.network}\" doesn't exist")
                }
            NetworkSource.User, NetworkSource.None -> Unit
        }
    }

    fun dismissError() = _env.update { it.copy(error = null) }
}
