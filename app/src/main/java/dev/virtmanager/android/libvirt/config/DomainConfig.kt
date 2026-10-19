package dev.virtmanager.android.libvirt.config

import org.w3c.dom.Document
import org.w3c.dom.Element

enum class Chipset(val label: String, val machine: String) {
    Q35("Q35", "q35"),
    I440FX("i440FX", "pc"),
    VIRT("virt (ARM)", "virt"),
    OTHER("Other", "");

    companion object {
        fun of(machine: String?): Chipset = when {
            machine == null -> OTHER
            machine.contains("q35") -> Q35
            machine == "pc" || machine.startsWith("pc-i440fx") || machine.startsWith("pc-") -> I440FX
            machine.startsWith("virt") -> VIRT
            else -> OTHER
        }
    }
}

enum class FirmwareKind(val label: String) { BIOS("BIOS"), UEFI("UEFI"), UEFI_SECURE("UEFI with Secure Boot") }

data class CpuTopology(val sockets: Int, val dies: Int = 1, val cores: Int, val threads: Int) {
    val total: Int get() = sockets * dies * cores * threads
}

/** A device element under `<devices>`, identified by its position there. */
data class Device(
    val index: Int,
    val tag: String,
    val label: String,
    val removable: Boolean,
)

data class BootEntry(val index: Int, val label: String, val enabled: Boolean)

/**
 * Mutable view of a domain's persistent XML for virt-manager-style editing: every page of
 * virt-manager's details window (Overview, OS information, CPUs, Memory, Boot options and each
 * device) reads and writes through here, and [toXml] is saved with `virsh define`.
 */
class DomainConfig private constructor(internal val doc: Document) {
    internal val root: Element get() = doc.documentElement
    internal val devicesEl: Element get() = root.ensure("devices")

    fun toXml(): String = Dom.serialize(doc)

    // --- Overview --------------------------------------------------------------------------

    val name: String get() = root.child("name")?.text().orEmpty()
    val uuid: String? get() = root.child("uuid")?.text()
    val domainType: String? get() = root.attr("type")
    val arch: String? get() = root.child("os")?.child("type")?.attr("arch")
    val emulator: String? get() = devicesEl.child("emulator")?.text()

    var title: String?
        get() = root.child("title")?.text()
        set(v) = root.setChildText("title", v?.replace('\n', ' '))

    var description: String?
        get() = root.child("description")?.text()
        set(v) = root.setChildText("description", v)

    val machine: String? get() = root.child("os")?.child("type")?.attr("machine")
    val chipset: Chipset get() = Chipset.of(machine)

    /**
     * Switches the machine type. Device addresses and implicit controllers are dropped so libvirt
     * lays the machine out again, and disks move between IDE (i440FX) and SATA (Q35).
     */
    fun setChipset(c: Chipset) {
        if (c == Chipset.OTHER || c == chipset) return
        root.ensure("os").ensure("type").setAttribute("machine", c.machine)
        for (dev in devicesEl.elements()) dev.removeChildren("address")
        devicesEl.children("controller")
            .filter { it.attr("type") in setOf("pci", "ide", "sata") }
            .forEach { devicesEl.removeChild(it) }
        // The iTCO watchdog is part of the Q35 chipset (libvirt adds it there by itself).
        if (c != Chipset.Q35) devicesEl.children("watchdog").filter { it.attr("model") == "itco" }.forEach { devicesEl.removeChild(it) }
        val from = if (c == Chipset.I440FX) "sata" else "ide"
        val to = if (c == Chipset.I440FX) "ide" else "sata"
        devicesEl.children("disk").forEach { d ->
            val target = d.child("target") ?: return@forEach
            if (target.attr("bus") == from) {
                target.setAttribute("bus", to)
                target.setAttribute("dev", nextTarget(to, exclude = d))
            }
        }
    }

    var firmware: FirmwareKind
        get() {
            val os = root.child("os") ?: return FirmwareKind.BIOS
            val efi = os.attr("firmware") == "efi" ||
                os.child("loader")?.let { it.attr("type") == "pflash" || it.text()?.contains("VMF", true) == true } == true
            if (!efi) return FirmwareKind.BIOS
            val secure = os.child("firmware")?.children("feature")
                ?.any { it.attr("name") == "secure-boot" && it.attr("enabled") == "yes" } == true ||
                os.child("loader")?.attr("secure") == "yes"
            return if (secure) FirmwareKind.UEFI_SECURE else FirmwareKind.UEFI
        }
        set(kind) {
            val os = root.ensure("os")
            // Let libvirt pick a matching firmware image and NVRAM template again.
            listOf("loader", "nvram", "firmware").forEach(os::removeChildren)
            when (kind) {
                FirmwareKind.BIOS -> os.removeAttribute("firmware")
                FirmwareKind.UEFI, FirmwareKind.UEFI_SECURE -> {
                    os.setAttribute("firmware", "efi")
                    val secure = if (kind == FirmwareKind.UEFI_SECURE) "yes" else "no"
                    val fw = doc.createElement("firmware")
                    listOf("enrolled-keys", "secure-boot").forEach { n ->
                        fw.appendChild(doc.createElement("feature").apply { setAttribute("enabled", secure); setAttribute("name", n) })
                    }
                    os.appendChild(fw)
                }
            }
            val features = root.ensure("features")
            if (kind == FirmwareKind.UEFI_SECURE) features.ensure("smm").setAttribute("state", "on")
        }

    // --- OS information --------------------------------------------------------------------

    private val osinfoNs = "http://libosinfo.org/xmlns/libvirt/domain/1.0"

    /** libosinfo id, e.g. "http://almalinux.org/almalinux/10" (virt-manager's "Operating System"). */
    var osInfoId: String?
        get() = root.child("metadata")?.elements()
            ?.firstOrNull { it.tagName.endsWith(":libosinfo") || it.tagName == "libosinfo" }
            ?.elements()?.firstOrNull { it.tagName.endsWith("os") }?.attr("id")
        set(v) {
            val metadata = root.ensure("metadata")
            metadata.elements().filter { it.tagName.endsWith(":libosinfo") || it.tagName == "libosinfo" }
                .forEach { metadata.removeChild(it) }
            if (!v.isNullOrBlank()) {
                val info = doc.createElement("libosinfo:libosinfo").apply { setAttribute("xmlns:libosinfo", osinfoNs) }
                info.appendChild(doc.createElement("libosinfo:os").apply { setAttribute("id", v) })
                metadata.appendChild(info)
            }
            if (metadata.elements().isEmpty()) root.removeChild(metadata)
        }

    // --- CPUs ------------------------------------------------------------------------------

    val maxVcpus: Int get() = root.child("vcpu")?.text()?.toIntOrNull() ?: 1
    val currentVcpus: Int get() = root.child("vcpu")?.attr("current")?.toIntOrNull() ?: maxVcpus

    /** Sets vCPU allocation; [current] below [max] leaves the rest hot-pluggable. */
    fun setVcpus(current: Int, max: Int) {
        require(max >= 1 && current in 1..max) { "vCPUs must be between 1 and the maximum" }
        val vcpu = root.ensure("vcpu")
        vcpu.setText(max.toString())
        if (vcpu.attr("placement") == null) vcpu.setAttribute("placement", "static")
        vcpu.setAttr("current", if (current < max) current.toString() else null)
        topology?.let { if (it.total != max) topology = null }
    }

    /**
     * host-passthrough, host-model, maximum or custom; null means no CPU configuration at all
     * (the hypervisor's default model).
     */
    var cpuMode: String?
        get() = root.child("cpu")?.let { it.attr("mode") ?: "custom" }
        set(mode) {
            if (mode == null) {
                val cpu = root.child("cpu") ?: return
                listOf("mode", "match", "check", "migratable").forEach(cpu::removeAttribute)
                cpu.removeChildren("model")
                if (cpu.elements().isEmpty()) root.removeChild(cpu)
                return
            }
            val cpu = root.ensure("cpu")
            cpu.setAttribute("mode", mode)
            listOf("match", "check", "migratable").forEach(cpu::removeAttribute)
            when (mode) {
                "custom" -> { cpu.setAttribute("match", "exact"); cpu.setAttribute("check", "none") }
                "host-passthrough" -> { cpu.setAttribute("check", "none"); cpu.setAttribute("migratable", "on") }
            }
            if (mode != "custom") cpu.removeChildren("model")
        }

    var cpuModel: String?
        get() = root.child("cpu")?.child("model")?.text()
        set(v) {
            val cpu = root.ensure("cpu")
            if (v.isNullOrBlank()) cpu.removeChildren("model")
            else cpu.ensure("model").apply { setText(v); setAttribute("fallback", "allow") }
        }

    var topology: CpuTopology?
        get() = root.child("cpu")?.child("topology")?.let { t ->
            CpuTopology(
                sockets = t.attr("sockets")?.toIntOrNull() ?: 1,
                dies = t.attr("dies")?.toIntOrNull() ?: 1,
                cores = t.attr("cores")?.toIntOrNull() ?: 1,
                threads = t.attr("threads")?.toIntOrNull() ?: 1,
            )
        }
        set(t) {
            if (t == null) {
                root.child("cpu")?.removeChildren("topology")
                return
            }
            require(t.total == maxVcpus) { "Sockets × cores × threads must equal the maximum vCPUs ($maxVcpus)" }
            root.ensure("cpu").ensure("topology").apply {
                setAttribute("sockets", t.sockets.toString())
                setAttribute("dies", t.dies.toString())
                setAttribute("cores", t.cores.toString())
                setAttribute("threads", t.threads.toString())
            }
        }

    // --- Memory ----------------------------------------------------------------------------

    private fun memKiB(tag: String): Long? = root.child(tag)?.let { el ->
        el.text()?.toLongOrNull()?.let { dev.virtmanager.android.libvirt.Parsers.toKiB(it, el.attr("unit") ?: "KiB") }
    }

    val maxMemoryMiB: Long get() = (memKiB("memory") ?: 0) / 1024
    val currentMemoryMiB: Long get() = (memKiB("currentMemory") ?: memKiB("memory") ?: 0) / 1024

    fun setMemory(currentMiB: Long, maxMiB: Long) {
        require(maxMiB >= 64 && currentMiB in 64..maxMiB) { "Memory must be at least 64 MiB and not above the maximum" }
        root.ensure("memory").apply { setAttribute("unit", "KiB"); setText((maxMiB * 1024).toString()) }
        root.ensure("currentMemory").apply { setAttribute("unit", "KiB"); setText((currentMiB * 1024).toString()) }
    }

    /** Shared memory backing, needed by virtiofs filesystems. */
    var sharedMemory: Boolean
        get() = root.child("memoryBacking")?.child("access")?.attr("mode") == "shared"
        set(on) {
            if (on) {
                val mb = root.ensure("memoryBacking")
                mb.ensure("source").setAttribute("type", "memfd")
                mb.ensure("access").setAttribute("mode", "shared")
            } else {
                root.child("memoryBacking")?.let { mb ->
                    mb.removeChildren("access")
                    mb.child("source")?.takeIf { it.attr("type") == "memfd" }?.let { mb.removeChild(it) }
                    if (mb.elements().isEmpty()) root.removeChild(mb)
                }
            }
        }

    // --- Boot options ----------------------------------------------------------------------

    var bootMenu: Boolean
        get() = root.child("os")?.child("bootmenu")?.attr("enable") == "yes"
        set(on) {
            val os = root.ensure("os")
            if (on) os.ensure("bootmenu").setAttribute("enable", "yes") else os.removeChildren("bootmenu")
        }

    private fun isBootable(el: Element) = el.tagName in setOf("disk", "interface", "hostdev", "redirdev")

    /** Bootable devices in boot order (enabled first), like virt-manager's boot device list. */
    fun bootEntries(): List<BootEntry> {
        val all = devicesEl.elements().withIndex().filter { isBootable(it.value) }
        val perDevice = all.mapNotNull { (i, el) -> el.child("boot")?.attr("order")?.toIntOrNull()?.let { i to it } }.toMap()
        val ordered: List<Int> = if (perDevice.isNotEmpty()) {
            perDevice.entries.sortedBy { it.value }.map { it.key }
        } else {
            // Legacy <os><boot dev=.../> list: map device classes to the first matching devices.
            root.child("os")?.children("boot")?.mapNotNull { it.attr("dev") }.orEmpty().flatMap { dev ->
                all.filter { (_, el) ->
                    when (dev) {
                        "hd" -> el.tagName == "disk" && (el.attr("device") ?: "disk") == "disk"
                        "cdrom" -> el.tagName == "disk" && el.attr("device") == "cdrom"
                        "fd" -> el.tagName == "disk" && el.attr("device") == "floppy"
                        "network" -> el.tagName == "interface"
                        else -> false
                    }
                }.map { it.index }.take(1)
            }
        }
        val labels = devices().associateBy({ it.index }, { it.label })
        val enabled = ordered.map { BootEntry(it, labels[it] ?: "Device $it", true) }
        val rest = all.map { it.index }.filterNot { it in ordered }.map { BootEntry(it, labels[it] ?: "Device $it", false) }
        return enabled + rest
    }

    /** Writes per-device `<boot order>` for [enabledInOrder] and removes the legacy list. */
    fun setBootOrder(enabledInOrder: List<Int>) {
        root.child("os")?.removeChildren("boot")
        val els = devicesEl.elements()
        els.forEach { it.removeChildren("boot") }
        enabledInOrder.forEachIndexed { order, idx ->
            val el = els.getOrNull(idx) ?: return@forEachIndexed
            require(isBootable(el)) { "Device ${el.tagName} can't boot" }
            el.appendChild(doc.createElement("boot").apply { setAttribute("order", (order + 1).toString()) })
        }
    }

    // --- Devices ---------------------------------------------------------------------------

    /** SPICE channels, USB redirection and smartcard passthrough only work with a SPICE display. */
    val hasSpice: Boolean get() = devicesEl.children("graphics").any { it.attr("type") == "spice" }

    fun deviceElement(index: Int): Element? = devicesEl.elements().getOrNull(index)

    fun deviceXml(index: Int): String? = deviceElement(index)?.let(Dom::serialize)

    fun devices(): List<Device> {
        val counters = mutableMapOf<String, Int>()
        fun next(key: String) = counters.merge(key, 1, Int::plus)!!
        return devicesEl.elements().mapIndexedNotNull { i, el ->
            if (hidden(el)) null else Device(i, el.tagName, label(el, ::next), removable(el))
        }
    }

    private fun hidden(el: Element): Boolean = when (el.tagName) {
        "emulator", "audio", "memballoon" -> true
        "controller" -> el.attr("type") == "pci" && el.attr("model") !in setOf("pci-root", "pcie-root")
        "input" -> el.attr("bus") == "ps2" // implicit on x86, not listed by virt-manager either
        else -> false
    }

    private fun removable(el: Element): Boolean = when (el.tagName) {
        "controller" -> el.attr("type") != "pci"
        "input" -> el.attr("bus") != "ps2" // implicit on x86; libvirt adds them back anyway
        else -> true
    }

    private fun label(el: Element, next: (String) -> Int): String {
        fun cap(s: String?) = when (s) {
            null -> ""
            "virtio" -> "VirtIO"
            "sata", "ide", "scsi", "usb", "qxl", "vga", "vnc" -> s.uppercase()
            "fdc" -> "Floppy"
            "spice" -> "Spice"
            else -> s.replaceFirstChar { it.uppercase() }
        }
        return when (el.tagName) {
            "disk" -> {
                val bus = el.child("target")?.attr("bus")
                val dev = when (el.attr("device") ?: "disk") { "cdrom" -> "CDROM"; "floppy" -> "Floppy"; "lun" -> "LUN"; else -> "Disk" }
                "${cap(bus)} $dev ${next("disk-$bus-$dev")}".trim()
            }
            "interface" -> "NIC " + (el.child("mac")?.attr("address")?.takeLast(9) ?: next("nic").toString())
            "input" -> when (el.attr("type")) {
                "tablet" -> "Tablet"
                "mouse" -> "Mouse"
                "keyboard" -> "Keyboard"
                else -> "Input ${el.attr("type").orEmpty()}"
            } + el.attr("bus")?.takeIf { it != "usb" && el.attr("type") != "tablet" }?.let { " (${cap(it)})" }.orEmpty()
            "graphics" -> "Display ${cap(el.attr("type"))}"
            "sound" -> "Sound ${el.attr("model").orEmpty()}"
            "serial" -> "Serial ${next("serial")}"
            "parallel" -> "Parallel ${next("parallel")}"
            "console" -> "Console ${next("console")}"
            "channel" -> "Channel " + when (val n = el.child("target")?.attr("name")) {
                "org.qemu.guest_agent.0" -> "(qemu-ga)"
                "com.redhat.spice.0" -> "(spice)"
                "org.spice-space.webdav.0" -> "(spice-webdav)"
                null -> next("channel").toString()
                else -> "($n)"
            }
            "video" -> "Video " + when (val m = el.child("model")?.attr("type")) {
                "virtio" -> "Virtio"
                "qxl", "vga" -> m.uppercase()
                null -> ""
                else -> m.replaceFirstChar { it.uppercase() }
            }
            "controller" -> "Controller " + when (val t = el.attr("type")) {
                "pci" -> if (el.attr("model") == "pcie-root") "PCIe" else "PCI"
                "virtio-serial" -> "VirtIO Serial"
                else -> cap(t)
            }
            "redirdev" -> "USB Redirector ${next("redir")}"
            "rng" -> "RNG ${el.child("backend")?.text() ?: el.child("backend")?.attr("model").orEmpty()}"
            "hostdev" -> {
                val src = el.child("source")
                when (el.attr("type")) {
                    "usb" -> "USB " + listOfNotNull(src?.child("vendor")?.attr("id"), src?.child("product")?.attr("id"))
                        .joinToString(":") { it.removePrefix("0x") }
                    "pci" -> "PCI " + (src?.child("address")?.let { a ->
                        "%04x:%02x:%02x.%x".format(hex(a.attr("domain")), hex(a.attr("bus")), hex(a.attr("slot")), hex(a.attr("function")))
                    } ?: "")
                    else -> "Host device"
                }
            }
            "watchdog" -> "Watchdog"
            "tpm" -> "TPM v${el.child("backend")?.attr("version") ?: "2.0"}"
            "filesystem" -> "Filesystem ${el.child("target")?.attr("dir").orEmpty()}"
            "panic" -> "Panic Notifier"
            "vsock" -> "VirtIO VSOCK"
            "smartcard" -> "Smartcard"
            "hub" -> "USB Hub"
            "shmem" -> "Shared memory ${el.attr("name").orEmpty()}"
            "iommu" -> "IOMMU"
            else -> el.tagName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun hex(v: String?): Int = v?.removePrefix("0x")?.toIntOrNull(16) ?: 0

    fun removeDevice(index: Int) {
        val el = deviceElement(index) ?: throw IllegalArgumentException("No device #$index")
        require(removable(el)) { "This device can't be removed" }
        devicesEl.removeChild(el)
    }

    /** Appends a device given as an XML snippet, e.g. `<rng model='virtio'>…</rng>`. */
    fun addDevice(xml: String): Int {
        val el = Dom.importSnippet(doc, xml)
        devicesEl.appendChild(el)
        return devicesEl.elements().indexOf(el)
    }

    /** Replaces device [index] with [xml] (the per-device XML tab). */
    fun replaceDevice(index: Int, xml: String) {
        val old = deviceElement(index) ?: throw IllegalArgumentException("No device #$index")
        val el = Dom.importSnippet(doc, xml)
        devicesEl.replaceChild(el, old)
    }

    /** First free target name for [bus] ("vda", "sdb", "hdc", "fda"). */
    fun nextTarget(bus: String, exclude: Element? = null): String {
        val prefix = when (bus) { "virtio" -> "vd"; "ide" -> "hd"; "fdc" -> "fd"; "xen" -> "xvd"; else -> "sd" }
        val used = devicesEl.children("disk").filter { it !== exclude }.mapNotNull { it.child("target")?.attr("dev") }.toSet()
        return generateSequence(0) { it + 1 }.map { prefix + driveSuffix(it) }.first { it !in used }
    }

    private fun driveSuffix(i: Int): String = if (i < 26) ('a' + i).toString() else driveSuffix(i / 26 - 1) + ('a' + i % 26)

    companion object {
        fun parse(xml: String): DomainConfig {
            val doc = Dom.parse(xml)
            require(doc.documentElement.tagName == "domain") { "Not a domain XML document" }
            return DomainConfig(doc)
        }
    }
}
