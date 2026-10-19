package dev.virtmanager.android.libvirt.config

import org.w3c.dom.Element

/**
 * The editable fields of one device, as on virt-manager's per-device "Details" pages. [apply]
 * writes them into an existing element, [toXml] builds a new device for "Add Hardware".
 */
sealed interface DeviceSettings {
    fun apply(el: Element, cfg: DomainConfig)
    fun toXml(cfg: DomainConfig): String

    companion object {
        /** Typed settings for [el], or null for devices that only get the XML tab. */
        fun read(el: Element): DeviceSettings? = when (el.tagName) {
            "disk" -> DiskSettings.read(el)
            "interface" -> InterfaceSettings.read(el)
            "input" -> InputSettings.read(el)
            "graphics" -> GraphicsSettings.read(el)
            "sound" -> SoundSettings(el.attr("model") ?: "ich9")
            "serial", "parallel", "console", "channel" -> CharSettings.read(el)
            "video" -> VideoSettings.read(el)
            "controller" -> ControllerSettings(el.attr("type") ?: "usb", el.attr("model"))
            "redirdev" -> RedirdevSettings.read(el)
            "rng" -> RngSettings(el.child("backend")?.text() ?: "/dev/urandom")
            "watchdog" -> WatchdogSettings(el.attr("model") ?: "i6300esb", el.attr("action") ?: "reset")
            "tpm" -> TpmSettings.read(el)
            "filesystem" -> FilesystemSettings.read(el)
            "panic" -> PanicSettings(el.attr("model") ?: "isa")
            "vsock" -> VsockSettings.read(el)
            "smartcard" -> SmartcardSettings(el.attr("mode") ?: "passthrough")
            "hostdev" -> HostdevSettings.read(el)
            else -> null
        }
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

// --- Storage --------------------------------------------------------------------------------

data class DiskSettings(
    val device: String = "disk",          // disk, cdrom, floppy
    val sourceType: String = "file",      // file, block, volume, network
    val source: String? = null,           // path (file/block); null = empty CD-ROM
    val bus: String = "virtio",
    val format: String? = "qcow2",
    val cache: String? = null,            // null = hypervisor default
    val discard: String? = null,          // unmap / ignore
    val readOnly: Boolean = false,
    val shareable: Boolean = false,
    val serial: String? = null,
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("device", device)
        if (sourceType == "file" || sourceType == "block") el.setAttribute("type", sourceType)
        if (sourceType == "file" || sourceType == "block") {
            el.removeChildren("source")
            if (!source.isNullOrBlank()) {
                val src = el.ownerDocument.createElement("source")
                src.setAttribute(if (sourceType == "block") "dev" else "file", source)
                el.insertBefore(src, el.child("target"))
            }
        }
        val target = el.ensure("target")
        if (target.attr("bus") != bus) {
            target.setAttribute("bus", bus)
            target.setAttribute("dev", cfg.nextTarget(bus, exclude = el))
            el.removeChildren("address") // bus-specific; libvirt assigns a new one
        }
        val driver = el.ensure("driver")
        driver.setAttribute("name", "qemu")
        driver.setAttr("type", format)
        driver.setAttr("cache", cache)
        driver.setAttr("discard", discard)
        if (readOnly || device == "cdrom") el.ensure("readonly") else el.removeChildren("readonly")
        if (shareable) el.ensure("shareable") else el.removeChildren("shareable")
        el.setChildText("serial", serial)
    }

    override fun toXml(cfg: DomainConfig): String = buildString {
        val type = if (sourceType == "block") "block" else "file"
        append("<disk type='$type' device='${esc(device)}'>")
        append("<driver name='qemu'")
        if (format != null) append(" type='${esc(format)}'")
        if (cache != null) append(" cache='${esc(cache)}'")
        if (discard != null) append(" discard='${esc(discard)}'")
        append("/>")
        if (!source.isNullOrBlank()) append("<source ${if (type == "block") "dev" else "file"}='${esc(source)}'/>")
        append("<target dev='${cfg.nextTarget(bus)}' bus='${esc(bus)}'/>")
        if (readOnly || device == "cdrom") append("<readonly/>")
        if (shareable) append("<shareable/>")
        if (!serial.isNullOrBlank()) append("<serial>${esc(serial)}</serial>")
        append("</disk>")
    }

    companion object {
        val buses = listOf("virtio", "sata", "scsi", "usb", "ide")
        val caches = listOf("none", "writethrough", "writeback", "directsync", "unsafe")
        val formats = listOf("qcow2", "raw")

        fun read(el: Element): DiskSettings {
            val src = el.child("source")
            val driver = el.child("driver")
            return DiskSettings(
                device = el.attr("device") ?: "disk",
                sourceType = el.attr("type") ?: "file",
                source = src?.let { it.attr("file") ?: it.attr("dev") ?: it.attr("volume")?.let { v -> "${it.attr("pool")}/$v" } ?: it.attr("name") },
                bus = el.child("target")?.attr("bus") ?: "virtio",
                format = driver?.attr("type"),
                cache = driver?.attr("cache"),
                discard = driver?.attr("discard"),
                readOnly = el.child("readonly") != null,
                shareable = el.child("shareable") != null,
                serial = el.child("serial")?.text(),
            )
        }
    }
}

// --- Network --------------------------------------------------------------------------------

data class InterfaceSettings(
    val kind: String = "network",         // network, bridge, direct (macvtap), user
    val source: String = "default",       // network / bridge / host device; unused for user
    val model: String = "virtio",
    val mac: String? = null,              // null = let libvirt generate one
    val linkUp: Boolean = true,
    val directMode: String = "bridge",    // macvtap mode: bridge, vepa, private, passthrough
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        val before = read(el)
        el.setAttribute("type", kind)
        // Rebuild <source> only when it changes, so e.g. a portgroup or the passt backend stays.
        if (before == null || before.kind != kind || before.source != source || before.directMode != directMode) {
            el.removeChildren("source")
            if (before?.kind != kind) el.removeChildren("target")
            sourceElement(el)?.let { el.insertBefore(it, el.child("mac")?.nextSibling ?: el.firstChild) }
        }
        val m = el.ensure("model")
        if (m.attr("type") != model) {
            m.setAttribute("type", model)
            el.removeChildren("address")
        }
        if (mac.isNullOrBlank()) el.removeChildren("mac") else el.ensure("mac").setAttribute("address", mac.lowercase())
        if (linkUp) el.removeChildren("link") else el.ensure("link").setAttribute("state", "down")
    }

    private fun sourceElement(el: Element): Element? {
        val src = el.ownerDocument.createElement("source")
        when (kind) {
            "network" -> src.setAttribute("network", source)
            "bridge" -> src.setAttribute("bridge", source)
            "direct" -> { src.setAttribute("dev", source); src.setAttribute("mode", directMode) }
            else -> return null // usermode networking has no source
        }
        return src
    }

    override fun toXml(cfg: DomainConfig): String = buildString {
        append("<interface type='${esc(kind)}'>")
        if (!mac.isNullOrBlank()) append("<mac address='${esc(mac.lowercase())}'/>")
        when (kind) {
            "network" -> append("<source network='${esc(source)}'/>")
            "bridge" -> append("<source bridge='${esc(source)}'/>")
            "direct" -> append("<source dev='${esc(source)}' mode='${esc(directMode)}'/>")
        }
        append("<model type='${esc(model)}'/>")
        if (!linkUp) append("<link state='down'/>")
        append("</interface>")
    }

    companion object {
        val kinds = listOf("network", "bridge", "direct", "user")
        val models = listOf("virtio", "e1000e", "e1000", "rtl8139")
        val directModes = listOf("bridge", "vepa", "private", "passthrough")
        private val macRegex = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        fun isValidMac(mac: String) = macRegex.matches(mac)

        /** Null for interface types without a form here (vhostuser, ethernet, hostdev …): XML only. */
        fun read(el: Element): InterfaceSettings? {
            val kind = el.attr("type") ?: "network"
            if (kind !in kinds) return null
            val src = el.child("source")
            return InterfaceSettings(
                kind = kind,
                source = when (kind) {
                    "network" -> src?.attr("network")
                    "bridge" -> src?.attr("bridge")
                    "direct" -> src?.attr("dev")
                    else -> null
                }.orEmpty(),
                model = el.child("model")?.attr("type") ?: "virtio",
                mac = el.child("mac")?.attr("address"),
                linkUp = el.child("link")?.attr("state") != "down",
                directMode = src?.attr("mode") ?: "bridge",
            )
        }
    }
}

// --- Input / display / sound / video ---------------------------------------------------------

data class InputSettings(val type: String = "tablet", val bus: String = "usb") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("type", type)
        if (el.attr("bus") != bus) { el.setAttribute("bus", bus); el.removeChildren("address") }
    }
    override fun toXml(cfg: DomainConfig) = "<input type='${esc(type)}' bus='${esc(bus)}'/>"

    companion object {
        val types = listOf("tablet", "mouse", "keyboard")
        val buses = listOf("usb", "virtio", "ps2")

        /** QEMU has no PS/2 tablet, and PS/2 exists only on x86 machines. */
        fun busesFor(type: String, arch: String?): List<String> =
            if (type == "tablet" || (arch != null && arch !in setOf("x86_64", "i686"))) listOf("usb", "virtio") else buses

        fun read(el: Element) = InputSettings(el.attr("type") ?: "tablet", el.attr("bus") ?: "usb")
    }
}

data class GraphicsSettings(
    val type: String = "vnc",             // vnc, spice
    val listen: String = "127.0.0.1",     // an address, or "none"
    val autoport: Boolean = true,
    val port: Int? = null,
    val password: String? = null,
    val openGl: Boolean = false,          // spice only
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        // Only the attributes this form edits are rewritten; keymap, sharePolicy, passwdValidTo …
        // stay. Switching VNC <-> Spice drops what only the other protocol understands.
        val managed = listOf("port", "autoport", "listen", "passwd", "socket")
        val typeSpecific = if (el.attr("type") != type) listOf("tlsPort", "defaultMode", "sharePolicy", "websocket", "powerControl") else emptyList()
        (managed + typeSpecific).forEach { el.removeAttribute(it) }
        if (typeSpecific.isNotEmpty()) {
            listOf("channel", "image", "jpeg", "zlib", "playback", "streaming", "clipboard", "mouse", "filetransfer").forEach { el.removeChildren(it) }
        }
        val socketPath = el.child("listen")?.takeIf { it.attr("type") == "socket" }?.attr("socket")
        el.removeChildren("listen")
        el.removeChildren("gl")
        el.setAttribute("type", type)
        fill(el, socketPath)
    }

    private fun fill(el: Element, socketPath: String? = null) {
        val doc = el.ownerDocument
        if (listen == "none") {
            el.appendChild(doc.createElement("listen").apply { setAttribute("type", "none") })
        } else if (listen == SOCKET) {
            // A UNIX socket on the host; libvirt picks the path unless one was set before.
            el.appendChild(doc.createElement("listen").apply {
                setAttribute("type", "socket")
                if (socketPath != null) setAttribute("socket", socketPath)
            })
        } else {
            if (autoport || port == null) { el.setAttribute("port", "-1"); el.setAttribute("autoport", "yes") }
            else { el.setAttribute("port", port.toString()); el.setAttribute("autoport", "no") }
            el.setAttribute("listen", listen)
            el.appendChild(doc.createElement("listen").apply { setAttribute("type", "address"); setAttribute("address", listen) })
        }
        el.setAttr("passwd", password)
        if (type == "spice" && openGl) el.appendChild(doc.createElement("gl").apply { setAttribute("enable", "yes") })
    }

    override fun toXml(cfg: DomainConfig): String {
        val doc = Dom.parse("<graphics type='${esc(type)}'/>")
        fill(doc.documentElement)
        return Dom.serialize(doc.documentElement)
    }

    companion object {
        const val SOCKET = "socket"

        fun read(el: Element): GraphicsSettings {
            val listenEl = el.child("listen")
            return GraphicsSettings(
                type = el.attr("type") ?: "vnc",
                listen = when (listenEl?.attr("type")) {
                    "none" -> "none"
                    "socket" -> SOCKET
                    else -> listenEl?.attr("address") ?: el.attr("listen") ?: "127.0.0.1"
                },
                autoport = el.attr("autoport") != "no",
                port = el.attr("port")?.toIntOrNull()?.takeIf { it > 0 },
                password = el.attr("passwd"),
                openGl = el.child("gl")?.attr("enable") == "yes",
            )
        }
    }
}

data class SoundSettings(val model: String = "ich9") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        if (el.attr("model") != model) { el.setAttribute("model", model); el.removeChildren("address") }
    }
    override fun toXml(cfg: DomainConfig) = "<sound model='${esc(model)}'/>"
    companion object { val models = listOf("ich9", "ich6", "ac97", "usb") }
}

data class VideoSettings(val model: String = "virtio", val heads: Int = 1, val accel3d: Boolean = false) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        val m = el.ensure("model")
        val primary = m.attr("primary")
        listOf("vram", "ram", "vgamem", "vram64").forEach(m::removeAttribute) // model specific
        m.setAttribute("type", model)
        m.setAttribute("heads", heads.toString())
        m.setAttr("primary", primary)
        m.removeChildren("acceleration")
        if (accel3d && model == "virtio") m.appendChild(el.ownerDocument.createElement("acceleration").apply { setAttribute("accel3d", "yes") })
        el.removeChildren("address")
    }

    override fun toXml(cfg: DomainConfig): String {
        val primary = if (cfg.devicesEl.children("video").isEmpty()) " primary='yes'" else ""
        val accel = if (accel3d && model == "virtio") "<acceleration accel3d='yes'/>" else ""
        return "<video><model type='${esc(model)}' heads='$heads'$primary>$accel</model></video>"
    }

    companion object {
        val models = listOf("virtio", "qxl", "vga", "bochs", "ramfb", "none")
        /** libvirt only accepts these as additional (non-primary) video devices. */
        val secondaryModels = listOf("virtio", "qxl")
        fun read(el: Element): VideoSettings {
            val m = el.child("model")
            return VideoSettings(
                model = m?.attr("type") ?: "virtio",
                heads = m?.attr("heads")?.toIntOrNull() ?: 1,
                accel3d = m?.child("acceleration")?.attr("accel3d") == "yes",
            )
        }
    }
}

// --- Character devices ----------------------------------------------------------------------

data class CharSettings(
    val tag: String,                      // serial, parallel, console, channel
    val type: String = "pty",             // pty, unix, spicevmc, qemu-vdagent
    val targetType: String? = null,       // console: serial/virtio; channel: virtio
    val targetName: String? = null,       // channel: org.qemu.guest_agent.0, com.redhat.spice.0
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("type", type)
        if (type != "unix") el.removeChildren("source")
        val target = el.ensure("target")
        target.setAttr("type", targetType ?: target.attr("type"))
        if (tag == "channel") target.setAttr("name", targetName)
    }

    override fun toXml(cfg: DomainConfig): String = when (tag) {
        "console" -> "<console type='${esc(type)}'><target type='${esc(targetType ?: "virtio")}'/></console>"
        "channel" -> "<channel type='${esc(type)}'><target type='virtio' name='${esc(targetName ?: "org.qemu.guest_agent.0")}'/></channel>"
        else -> "<$tag type='${esc(type)}'/>"
    }

    companion object {
        /** Well-known channels and the backend type each one needs. */
        val channels = listOf(
            "org.qemu.guest_agent.0" to "unix",
            "com.redhat.spice.0" to "spicevmc",
            "org.spice-space.webdav.0" to "spiceport",
        )

        fun read(el: Element) = CharSettings(
            tag = el.tagName,
            type = el.attr("type") ?: "pty",
            targetType = el.child("target")?.attr("type"),
            targetName = el.child("target")?.attr("name"),
        )
    }
}

// --- Controllers / USB redirection / misc ---------------------------------------------------

data class ControllerSettings(val type: String = "usb", val model: String? = "qemu-xhci") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        if (el.attr("model") != model) { el.setAttr("model", model); el.removeChildren("address") }
    }

    override fun toXml(cfg: DomainConfig): String {
        val index = cfg.devicesEl.children("controller").filter { it.attr("type") == type }
            .mapNotNull { it.attr("index")?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 0
        val modelAttr = model?.let { " model='${esc(it)}'" }.orEmpty()
        val ports = if (type == "usb" && model?.contains("xhci") == true) " ports='15'" else ""
        return "<controller type='${esc(type)}' index='$index'$modelAttr$ports/>"
    }

    companion object {
        val modelsByType = mapOf(
            "usb" to listOf("qemu-xhci", "nec-xhci", "ich9-ehci1", "piix3-uhci", "none"),
            "scsi" to listOf("virtio-scsi", "lsilogic"),
            "sata" to listOf<String>(),
            "virtio-serial" to listOf<String>(),
            "ccid" to listOf<String>(),
        )
    }
}

/** USB redirection: from a SPICE client (`spicevmc`, needs a SPICE display) or a TCP usbredir server. */
data class RedirdevSettings(val type: String = "spicevmc", val host: String = "localhost", val port: Int = 4000) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("type", type)
        el.removeChildren("source")
        if (type == "tcp") el.appendChild(Dom.importSnippet(el.ownerDocument, sourceXml()))
    }
    private fun sourceXml() = "<source mode='connect' host='${esc(host)}' service='$port'/>"
    override fun toXml(cfg: DomainConfig) =
        if (type == "tcp") "<redirdev bus='usb' type='tcp'>${sourceXml()}</redirdev>" else "<redirdev bus='usb' type='spicevmc'/>"
    companion object {
        fun read(el: Element) = RedirdevSettings(
            type = el.attr("type") ?: "spicevmc",
            host = el.child("source")?.attr("host") ?: "localhost",
            port = el.child("source")?.attr("service")?.toIntOrNull() ?: 4000,
        )
    }
}

data class RngSettings(val device: String = "/dev/urandom") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("model", el.attr("model") ?: "virtio")
        el.removeChildren("backend")
        el.appendChild(el.ownerDocument.createElement("backend").apply { setAttribute("model", "random"); setText(device) })
    }
    override fun toXml(cfg: DomainConfig) = "<rng model='virtio'><backend model='random'>${esc(device)}</backend></rng>"
    companion object { val devices = listOf("/dev/urandom", "/dev/random", "/dev/hwrng") }
}

data class WatchdogSettings(val model: String = "i6300esb", val action: String = "reset") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        if (el.attr("model") != model) { el.setAttribute("model", model); el.removeChildren("address") }
        shareAction(cfg)
        el.setAttribute("action", action)
    }

    override fun toXml(cfg: DomainConfig): String {
        shareAction(cfg)
        return "<watchdog model='${esc(model)}' action='${esc(action)}'/>"
    }

    /** QEMU applies one action to all watchdogs (Q35 has a built-in iTCO one), so keep them equal. */
    private fun shareAction(cfg: DomainConfig) = cfg.devicesEl.children("watchdog").forEach { it.setAttribute("action", action) }
    companion object {
        val models = listOf("i6300esb", "itco", "ib700")
        val actions = listOf("reset", "shutdown", "poweroff", "pause", "dump", "inject-nmi", "none")
    }
}

data class TpmSettings(val model: String = "tpm-crb", val backend: String = "emulator", val path: String? = null) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("model", model)
        el.removeChildren("backend")
        el.appendChild(Dom.importSnippet(el.ownerDocument, backendXml()))
    }
    private fun backendXml() =
        if (backend == "passthrough") "<backend type='passthrough'><device path='${esc(path ?: "/dev/tpm0")}'/></backend>"
        else "<backend type='emulator' version='2.0'/>"
    override fun toXml(cfg: DomainConfig) = "<tpm model='${esc(model)}'>${backendXml()}</tpm>"
    companion object {
        val models = listOf("tpm-crb", "tpm-tis")
        fun read(el: Element) = TpmSettings(
            model = el.attr("model") ?: "tpm-crb",
            backend = el.child("backend")?.attr("type") ?: "emulator",
            path = el.child("backend")?.child("device")?.attr("path"),
        )
    }
}

data class FilesystemSettings(
    val driver: String = "virtiofs",      // virtiofs, path (9p)
    val source: String = "",
    val target: String = "",
    val readOnly: Boolean = false,
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("type", "mount")
        el.setAttribute("accessmode", "passthrough")
        el.ensure("driver").setAttribute("type", driver)
        el.ensure("source").setAttribute("dir", source)
        el.ensure("target").setAttribute("dir", target)
        if (readOnly) el.ensure("readonly") else el.removeChildren("readonly")
        if (driver == "virtiofs") cfg.sharedMemory = true
    }

    override fun toXml(cfg: DomainConfig): String {
        if (driver == "virtiofs") cfg.sharedMemory = true // virtiofs requires shared guest memory
        return "<filesystem type='mount' accessmode='passthrough'><driver type='${esc(driver)}'/>" +
            "<source dir='${esc(source)}'/><target dir='${esc(target)}'/>" +
            (if (readOnly) "<readonly/>" else "") + "</filesystem>"
    }

    companion object {
        fun read(el: Element) = FilesystemSettings(
            driver = el.child("driver")?.attr("type") ?: "path",
            source = el.child("source")?.let { it.attr("dir") ?: it.attr("file") }.orEmpty(),
            target = el.child("target")?.attr("dir").orEmpty(),
            readOnly = el.child("readonly") != null,
        )
    }
}

data class PanicSettings(val model: String = "isa") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        if (el.attr("model") != model) { el.setAttribute("model", model); el.removeChildren("address") }
    }
    override fun toXml(cfg: DomainConfig) = "<panic model='${esc(model)}'/>"
    companion object { val models = listOf("isa", "pvpanic", "hyperv") }
}

data class VsockSettings(val autoCid: Boolean = true, val cid: Int? = null) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("model", "virtio")
        el.removeChildren("cid")
        el.appendChild(Dom.importSnippet(el.ownerDocument, cidXml()))
    }
    private fun cidXml() = if (autoCid || cid == null) "<cid auto='yes'/>" else "<cid auto='no' address='$cid'/>"
    override fun toXml(cfg: DomainConfig) = "<vsock model='virtio'>${cidXml()}</vsock>"
    companion object {
        fun read(el: Element) = VsockSettings(
            autoCid = el.child("cid")?.attr("auto") != "no",
            cid = el.child("cid")?.attr("address")?.toIntOrNull(),
        )
    }
}

data class SmartcardSettings(val mode: String = "passthrough") : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) {
        el.setAttribute("mode", mode)
        if (mode == "passthrough") el.setAttribute("type", "spicevmc") else el.removeAttribute("type")
    }
    override fun toXml(cfg: DomainConfig) =
        if (mode == "passthrough") "<smartcard mode='passthrough' type='spicevmc'/>" else "<smartcard mode='host'/>"
}

/**
 * A host USB or PCI device passed through to the guest. USB devices are matched by vendor and
 * product; [usbBus]/[usbDevice] are added when several identical devices are plugged in, which
 * libvirt otherwise refuses to start ("multiple USB devices for ...").
 */
data class HostdevSettings(
    val type: String,                     // usb, pci
    val vendorId: String? = null,         // usb
    val productId: String? = null,        // usb
    val pciAddress: String? = null,       // pci, "0000:01:00.0"
    val usbBus: Int? = null,
    val usbDevice: Int? = null,
    val pinUsbAddress: Boolean = false,
) : DeviceSettings {
    override fun apply(el: Element, cfg: DomainConfig) = Unit // the source is chosen when adding

    override fun toXml(cfg: DomainConfig): String = when (type) {
        "usb" -> "<hostdev mode='subsystem' type='usb' managed='yes'><source>" +
            "<vendor id='${esc(vendorId.orEmpty())}'/><product id='${esc(productId.orEmpty())}'/>" +
            (if (pinUsbAddress && usbBus != null && usbDevice != null) "<address bus='$usbBus' device='$usbDevice'/>" else "") +
            "</source></hostdev>"
        else -> {
            val (dom, rest) = pciAddress.orEmpty().split(":", limit = 2).let { if (it.size == 2) it[0] to it[1] else "0000" to it[0] }
            val (bus, slotFn) = rest.split(":").let { it.getOrElse(0) { "00" } to it.getOrElse(1) { "00.0" } }
            val (slot, fn) = slotFn.split(".").let { it.getOrElse(0) { "00" } to it.getOrElse(1) { "0" } }
            "<hostdev mode='subsystem' type='pci' managed='yes'><source>" +
                "<address domain='0x$dom' bus='0x$bus' slot='0x$slot' function='0x$fn'/></source></hostdev>"
        }
    }

    /** Whether this configured device refers to [d] on the host. */
    fun matches(d: HostDevice): Boolean = when (type) {
        "pci" -> d.type == "pci" && pciAddress == d.address
        else -> d.type == "usb" && vendorId.equals(d.vendorId, true) && productId.equals(d.productId, true) &&
            (usbBus == null || (usbBus == d.settings.usbBus && usbDevice == d.settings.usbDevice))
    }

    companion object {
        fun read(el: Element): HostdevSettings {
            val src = el.child("source")
            val addr = src?.child("address")
            return HostdevSettings(
                type = el.attr("type") ?: "usb",
                vendorId = src?.child("vendor")?.attr("id"),
                productId = src?.child("product")?.attr("id"),
                pciAddress = if (el.attr("type") == "pci") addr?.let { a ->
                    fun h(n: String) = a.attr(n)?.removePrefix("0x")?.toIntOrNull(16) ?: 0
                    HostInventory.pciAddress(h("domain"), h("bus"), h("slot"), h("function"))
                } else null,
                usbBus = if (el.attr("type") == "usb") addr?.attr("bus")?.toIntOrNull() else null,
                usbDevice = if (el.attr("type") == "usb") addr?.attr("device")?.toIntOrNull() else null,
                pinUsbAddress = el.attr("type") == "usb" && addr != null,
            )
        }
    }
}
