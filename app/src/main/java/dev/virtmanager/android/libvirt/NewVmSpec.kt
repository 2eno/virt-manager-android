package dev.virtmanager.android.libvirt

import java.util.UUID

enum class Firmware { BIOS, UEFI }

sealed interface NetworkSource {
    data class Virtual(val network: String) : NetworkSource
    data class Bridge(val bridge: String) : NetworkSource
    data object None : NetworkSource
}

/** Everything the New VM wizard collects; mirrors virt-manager's create wizard steps. */
data class NewVmSpec(
    val name: String,
    val memoryMiB: Long,
    val vcpus: Int,
    val arch: String = "x86_64",
    val firmware: Firmware = Firmware.BIOS,
    val installMediaPath: String? = null,
    val diskPath: String? = null,
    val diskFormat: String = "qcow2",
    val network: NetworkSource = NetworkSource.Virtual("default"),
    val osFamily: OsFamily = OsFamily.LINUX,
    /** "kvm" when the host has hardware virtualization, otherwise "qemu" (software emulation). */
    val virtType: String = "kvm",
    val uuid: String = UUID.randomUUID().toString(),
)

enum class OsFamily(val label: String) {
    LINUX("Linux"),
    WINDOWS("Windows"),
    BSD("BSD"),
    OTHER("Other"),
}

object DomainXmlBuilder {

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun isValidName(name: String) = name.isNotBlank() && Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$").matches(name)

    fun build(spec: NewVmSpec): String {
        require(isValidName(spec.name)) { "Invalid VM name" }
        val isArm = spec.arch == "aarch64"
        val machine = if (isArm) "virt" else "q35"
        // aarch64 "virt" machines only boot via UEFI.
        val firmware = if (isArm) Firmware.UEFI else spec.firmware
        val windows = spec.osFamily == OsFamily.WINDOWS
        // Windows has no in-box virtio drivers; use emulated devices so the installer sees them.
        val diskBus = if (windows) "sata" else "virtio"
        val nicModel = if (windows) "e1000e" else "virtio"
        val cdBus = if (isArm) "scsi" else "sata"

        return buildString {
            appendLine("<domain type='${esc(spec.virtType)}'>")
            appendLine("  <name>${esc(spec.name)}</name>")
            appendLine("  <uuid>${esc(spec.uuid)}</uuid>")
            appendLine("  <memory unit='MiB'>${spec.memoryMiB}</memory>")
            appendLine("  <currentMemory unit='MiB'>${spec.memoryMiB}</currentMemory>")
            appendLine("  <vcpu placement='static'>${spec.vcpus}</vcpu>")
            if (firmware == Firmware.UEFI) appendLine("  <os firmware='efi'>") else appendLine("  <os>")
            appendLine("    <type arch='${esc(spec.arch)}' machine='$machine'>hvm</type>")
            if (spec.installMediaPath != null) appendLine("    <boot dev='cdrom'/>")
            appendLine("    <boot dev='hd'/>")
            appendLine("  </os>")
            appendLine("  <features>")
            if (!isArm) {
                appendLine("    <acpi/>")
                appendLine("    <apic/>")
            } else {
                appendLine("    <acpi/>")
                appendLine("    <gic version='host'/>")
            }
            if (windows) {
                appendLine("    <hyperv mode='custom'>")
                appendLine("      <relaxed state='on'/>")
                appendLine("      <vapic state='on'/>")
                appendLine("      <spinlocks state='on' retries='8191'/>")
                appendLine("    </hyperv>")
            }
            appendLine("  </features>")
            // host-passthrough needs KVM; emulated guests get QEMU's default CPU model.
            if (spec.virtType == "kvm") appendLine("  <cpu mode='host-passthrough' check='none'/>")
            appendLine("  <clock offset='${if (windows) "localtime" else "utc"}'>")
            appendLine("    <timer name='rtc' tickpolicy='catchup'/>")
            appendLine("    <timer name='pit' tickpolicy='delay'/>")
            appendLine("    <timer name='hpet' present='no'/>")
            appendLine("  </clock>")
            appendLine("  <on_poweroff>destroy</on_poweroff>")
            appendLine("  <on_reboot>restart</on_reboot>")
            appendLine("  <on_crash>destroy</on_crash>")
            appendLine("  <pm>")
            appendLine("    <suspend-to-mem enabled='no'/>")
            appendLine("    <suspend-to-disk enabled='no'/>")
            appendLine("  </pm>")
            appendLine("  <devices>")
            if (spec.diskPath != null) {
                appendLine("    <disk type='file' device='disk'>")
                appendLine("      <driver name='qemu' type='${esc(spec.diskFormat)}' discard='unmap'/>")
                appendLine("      <source file='${esc(spec.diskPath)}'/>")
                appendLine("      <target dev='${if (diskBus == "virtio") "vda" else "sda"}' bus='$diskBus'/>")
                appendLine("    </disk>")
            }
            if (spec.installMediaPath != null) {
                appendLine("    <disk type='file' device='cdrom'>")
                appendLine("      <driver name='qemu' type='raw'/>")
                appendLine("      <source file='${esc(spec.installMediaPath)}'/>")
                appendLine("      <target dev='sdb' bus='$cdBus'/>")
                appendLine("      <readonly/>")
                appendLine("    </disk>")
            }
            if (isArm) appendLine("    <controller type='scsi' model='virtio-scsi'/>")
            appendLine("    <controller type='usb' model='qemu-xhci' ports='15'/>")
            when (val net = spec.network) {
                is NetworkSource.Virtual -> {
                    appendLine("    <interface type='network'>")
                    appendLine("      <source network='${esc(net.network)}'/>")
                    appendLine("      <model type='$nicModel'/>")
                    appendLine("    </interface>")
                }
                is NetworkSource.Bridge -> {
                    appendLine("    <interface type='bridge'>")
                    appendLine("      <source bridge='${esc(net.bridge)}'/>")
                    appendLine("      <model type='$nicModel'/>")
                    appendLine("    </interface>")
                }
                NetworkSource.None -> Unit
            }
            appendLine("    <serial type='pty'>")
            appendLine("      <target port='0'/>")
            appendLine("    </serial>")
            appendLine("    <console type='pty'>")
            appendLine("      <target type='serial' port='0'/>")
            appendLine("    </console>")
            appendLine("    <channel type='unix'>")
            appendLine("      <target type='virtio' name='org.qemu.guest_agent.0'/>")
            appendLine("    </channel>")
            appendLine("    <input type='tablet' bus='usb'/>")
            appendLine("    <input type='keyboard' bus='usb'/>")
            appendLine("    <graphics type='vnc' port='-1' autoport='yes' listen='127.0.0.1'>")
            appendLine("      <listen type='address' address='127.0.0.1'/>")
            appendLine("    </graphics>")
            appendLine("    <video>")
            appendLine("      <model type='${if (windows) "vga" else "virtio"}' heads='1' primary='yes'/>")
            appendLine("    </video>")
            appendLine("    <memballoon model='virtio'/>")
            appendLine("    <rng model='virtio'>")
            appendLine("      <backend model='random'>/dev/urandom</backend>")
            appendLine("    </rng>")
            appendLine("  </devices>")
            appendLine("</domain>")
        }
    }
}
