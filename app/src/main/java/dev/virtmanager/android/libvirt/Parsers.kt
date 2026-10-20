package dev.virtmanager.android.libvirt

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Parsers for the text and XML that `virsh` prints. Everything here is pure Kotlin/JDK so it can
 * be unit tested on the JVM without an Android device.
 */
object Parsers {

    // ---------------------------------------------------------------------------------------
    // domstats
    // ---------------------------------------------------------------------------------------

    fun parseDomStats(output: String): List<DomainStats> {
        val result = mutableListOf<DomainStats>()
        var name: String? = null
        var fields = mutableMapOf<String, String>()

        fun flush() {
            val n = name ?: return
            result += buildDomainStats(n, fields)
        }

        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("Domain:")) {
                flush()
                name = line.removePrefix("Domain:").trim().removeSurrounding("'")
                fields = mutableMapOf()
            } else if (name != null && line.contains('=')) {
                val trimmed = line.trim()
                val eq = trimmed.indexOf('=')
                fields[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
            }
        }
        flush()
        return result
    }

    private fun buildDomainStats(name: String, f: Map<String, String>): DomainStats {
        fun long(key: String) = f[key]?.toLongOrNull()
        fun int(key: String) = f[key]?.toIntOrNull()

        val nets = (0 until (int("net.count") ?: 0)).map { i ->
            InterfaceStats(
                name = f["net.$i.name"] ?: "net$i",
                rxBytes = long("net.$i.rx.bytes") ?: 0,
                txBytes = long("net.$i.tx.bytes") ?: 0,
            )
        }
        val blocks = (0 until (int("block.count") ?: 0)).map { i ->
            BlockStats(
                name = f["block.$i.name"] ?: "block$i",
                readBytes = long("block.$i.rd.bytes") ?: 0,
                writeBytes = long("block.$i.wr.bytes") ?: 0,
                capacity = long("block.$i.capacity"),
                allocation = long("block.$i.allocation"),
            )
        }
        return DomainStats(
            name = name,
            state = DomainState.fromCode(int("state.state")),
            cpuTimeNs = long("cpu.time"),
            vcpus = int("vcpu.current"),
            maxVcpus = int("vcpu.maximum"),
            memoryKiB = long("balloon.current"),
            maxMemoryKiB = long("balloon.maximum"),
            rssKiB = long("balloon.rss"),
            unusedKiB = long("balloon.unused"),
            availableKiB = long("balloon.available"),
            interfaces = nets,
            blocks = blocks,
        )
    }

    // ---------------------------------------------------------------------------------------
    // "Key: value" style outputs (dominfo, nodeinfo, nodememstats, nodecpustats, version)
    // ---------------------------------------------------------------------------------------

    fun parseKeyValues(output: String): Map<String, String> =
        output.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }
            .toMap()

    private fun leadingLong(value: String?): Long? =
        value?.trim()?.takeWhile { it.isDigit() }?.toLongOrNull()

    fun parseDomInfo(output: String): DomainInfo {
        val kv = parseKeyValues(output)
        return DomainInfo(
            id = kv["id"]?.takeIf { it != "-" },
            name = kv["name"] ?: "",
            uuid = kv["uuid"],
            osType = kv["os type"],
            state = DomainState.fromText(kv["state"] ?: ""),
            cpus = kv["cpu(s)"]?.toIntOrNull(),
            maxMemoryKiB = leadingLong(kv["max memory"]),
            usedMemoryKiB = leadingLong(kv["used memory"]),
            persistent = kv["persistent"] == "yes",
            autostart = kv["autostart"] == "enable",
        )
    }

    fun parseNodeInfo(output: String): NodeInfo {
        val kv = parseKeyValues(output)
        return NodeInfo(
            cpuModel = kv["cpu model"],
            cpus = kv["cpu(s)"]?.toIntOrNull(),
            cpuFrequencyMHz = leadingLong(kv["cpu frequency"])?.toInt(),
            sockets = kv["cpu socket(s)"]?.toIntOrNull(),
            coresPerSocket = kv["core(s) per socket"]?.toIntOrNull(),
            threadsPerCore = kv["thread(s) per core"]?.toIntOrNull(),
            numaCells = kv["numa cell(s)"]?.toIntOrNull(),
            memoryKiB = leadingLong(kv["memory size"]),
        )
    }

    fun parseNodeMemStats(output: String): NodeMemStats {
        val kv = parseKeyValues(output)
        return NodeMemStats(
            totalKiB = leadingLong(kv["total"]) ?: 0,
            freeKiB = leadingLong(kv["free"]) ?: 0,
            buffersKiB = leadingLong(kv["buffers"]) ?: 0,
            cachedKiB = leadingLong(kv["cached"]) ?: 0,
        )
    }

    fun parseNodeCpuStats(output: String): NodeCpuStats {
        val kv = parseKeyValues(output)
        return NodeCpuStats(
            user = leadingLong(kv["user"]) ?: 0,
            system = leadingLong(kv["system"]) ?: 0,
            idle = leadingLong(kv["idle"]) ?: 0,
            iowait = leadingLong(kv["iowait"]) ?: 0,
        )
    }

    fun parseVersion(output: String): HypervisorVersion {
        var lib: String? = null
        var api: String? = null
        var hv: String? = null
        for (line in output.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith("Compiled against library:") -> Unit
                l.startsWith("Using library:") -> lib = l.removePrefix("Using library:").trim()
                l.startsWith("Using API:") -> api = l.removePrefix("Using API:").trim()
                l.startsWith("Running hypervisor:") -> hv = l.removePrefix("Running hypervisor:").trim()
            }
        }
        return HypervisorVersion(lib, api, hv)
    }

    // ---------------------------------------------------------------------------------------
    // Tables (net-list, pool-list, vol-list, snapshot-list)
    // ---------------------------------------------------------------------------------------

    private val columnSplit = Regex("\\s{2,}")

    /** Returns the data rows of a virsh table, each split into columns. */
    fun parseTable(output: String): List<List<String>> {
        val lines = output.lines()
        val sep = lines.indexOfFirst { it.trim().startsWith("---") }
        if (sep < 0) return emptyList()
        return lines.drop(sep + 1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(columnSplit) }
    }

    private fun yes(s: String?) = s.equals("yes", ignoreCase = true)

    fun parseNetList(output: String): List<VirtualNetwork> = parseTable(output).mapNotNull { c ->
        if (c.isEmpty()) null
        else VirtualNetwork(
            name = c[0],
            active = c.getOrNull(1).equals("active", ignoreCase = true),
            autostart = yes(c.getOrNull(2)),
            persistent = yes(c.getOrNull(3)),
        )
    }

    fun parsePoolList(output: String): List<StoragePool> = parseTable(output).mapNotNull { c ->
        if (c.isEmpty()) null
        else StoragePool(
            name = c[0],
            state = c.getOrElse(1) { "" },
            autostart = yes(c.getOrNull(2)),
            persistent = yes(c.getOrNull(3)),
            capacity = c.getOrNull(4)?.takeIf { it != "-" },
            allocation = c.getOrNull(5)?.takeIf { it != "-" },
            available = c.getOrNull(6)?.takeIf { it != "-" },
        )
    }

    fun parseVolList(output: String): List<StorageVolume> = parseTable(output).mapNotNull { c ->
        if (c.size < 2) null
        else StorageVolume(
            name = c[0],
            path = c[1],
            type = c.getOrNull(2),
            capacity = c.getOrNull(3),
            allocation = c.getOrNull(4),
        )
    }

    fun parseSnapshotList(output: String, current: String? = null): List<Snapshot> =
        parseTable(output).mapNotNull { c ->
            if (c.isEmpty()) null
            else Snapshot(
                name = c[0],
                creationTime = c.getOrElse(1) { "" },
                state = c.getOrElse(2) { "" },
                current = c[0] == current,
            )
        }

    fun parseSnapshotXml(xml: String): SnapshotFiles {
        val root = parseXml(xml)
        val memory = root.child("memory")?.takeIf { it.attr("snapshot") == "external" }?.attr("file")
        val overlays = root.child("disks")?.children("disk").orEmpty()
            .filter { it.attr("snapshot") == "external" }
            .mapNotNull { d -> d.attr("name")?.let { n -> d.child("source")?.let { it.attr("file") ?: it.attr("dev") }?.let { n to it } } }
            .toMap()
        val base = root.child("domain")?.child("devices")?.children("disk").orEmpty()
            .filter { (it.attr("device") ?: "disk") == "disk" }
            .mapNotNull { d ->
                val target = d.child("target")?.attr("dev") ?: return@mapNotNull null
                d.child("source")?.let { it.attr("file") ?: it.attr("dev") }?.let { target to it }
            }.toMap()
        return SnapshotFiles(memory != null || overlays.isNotEmpty(), memory, overlays, base)
    }

    // ---------------------------------------------------------------------------------------
    // XML
    // ---------------------------------------------------------------------------------------

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == tag) out += n as Element
        }
        return out
    }

    private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
    private fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Converts a libvirt memory element (value + unit attribute) to KiB. */
    private fun Element.memoryKiB(): Long? {
        val value = text()?.toLongOrNull() ?: return null
        return toKiB(value, attr("unit") ?: "KiB")
    }

    fun toKiB(value: Long, unit: String): Long = when (unit.lowercase()) {
        "b", "bytes" -> value / 1024
        "k", "kib" -> value
        "kb" -> value * 1000 / 1024
        "m", "mib" -> value * 1024
        "mb" -> value * 1000 * 1000 / 1024
        "g", "gib" -> value * 1024 * 1024
        "gb" -> value * 1000 * 1000 * 1000 / 1024
        "t", "tib" -> value * 1024 * 1024 * 1024
        else -> value
    }

    fun parseDomainXml(xml: String): DomainDefinition {
        val root = parseXml(xml)
        val os = root.child("os")
        val osType = os?.child("type")
        val devices = root.child("devices")

        val disks = devices?.children("disk").orEmpty().map { d ->
            val src = d.child("source")
            val source = src?.let {
                it.attr("file") ?: it.attr("dev") ?: it.attr("name")
                    ?: it.attr("volume")?.let { vol -> "${it.attr("pool") ?: "?"}/$vol" }
            }
            DiskDevice(
                device = d.attr("device") ?: "disk",
                type = d.attr("type") ?: "file",
                driverType = d.child("driver")?.attr("type"),
                source = source,
                target = d.child("target")?.attr("dev"),
                bus = d.child("target")?.attr("bus"),
                readOnly = d.child("readonly") != null,
            )
        }

        val nics = devices?.children("interface").orEmpty().map { n ->
            val src = n.child("source")
            NetworkInterface(
                type = n.attr("type") ?: "network",
                mac = n.child("mac")?.attr("address"),
                source = src?.let { it.attr("network") ?: it.attr("bridge") ?: it.attr("dev") },
                model = n.child("model")?.attr("type"),
                target = n.child("target")?.attr("dev"),
            )
        }

        val graphics = devices?.children("graphics").orEmpty().map { g ->
            val listenEl = g.child("listen")
            GraphicsDevice(
                type = g.attr("type") ?: "vnc",
                port = g.attr("port")?.toIntOrNull()?.takeIf { it > 0 },
                autoport = g.attr("autoport") == "yes",
                listen = g.attr("listen") ?: listenEl?.attr("address"),
                hasPassword = g.attr("passwd") != null,
                socket = g.attr("socket") ?: listenEl?.attr("socket"),
            )
        }

        val firmware = os?.attr("firmware")
            ?: os?.child("loader")?.let { if (it.attr("type") == "pflash" || it.text()?.contains("OVMF", true) == true || it.text()?.contains("AAVMF", true) == true) "efi" else null }
            ?: "bios"

        return DomainDefinition(
            name = root.child("name")?.text() ?: "",
            uuid = root.child("uuid")?.text(),
            title = root.child("title")?.text(),
            description = root.child("description")?.text(),
            domainType = root.attr("type"),
            memoryKiB = root.child("memory")?.memoryKiB(),
            currentMemoryKiB = root.child("currentMemory")?.memoryKiB(),
            vcpus = root.child("vcpu")?.let { it.attr("current")?.toIntOrNull() ?: it.text()?.toIntOrNull() },
            arch = osType?.attr("arch"),
            machine = osType?.attr("machine"),
            firmware = firmware,
            bootDevices = os?.children("boot").orEmpty().mapNotNull { it.attr("dev") },
            cpuMode = root.child("cpu")?.attr("mode"),
            emulator = devices?.child("emulator")?.text(),
            disks = disks,
            interfaces = nics,
            graphics = graphics,
            videoModel = devices?.child("video")?.child("model")?.attr("type"),
            hasSerialConsole = devices?.children("serial").orEmpty().isNotEmpty() ||
                devices?.children("console").orEmpty().isNotEmpty(),
            hasGuestAgentChannel = devices?.children("channel").orEmpty().any {
                it.child("target")?.attr("name") == "org.qemu.guest_agent.0"
            },
            setupPending = root.child("metadata")?.let { md ->
                val nodes = md.childNodes
                (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().any { e ->
                    e.tagName.endsWith(":setup") && (0 until e.attributes.length).any { i -> e.attributes.item(i).nodeValue == SetupMarker.NS }
                }
            } == true,
        )
    }

    fun parseNetworkXml(xml: String): NetworkDetails {
        val root = parseXml(xml)
        val ip = root.child("ip")
        val range = ip?.child("dhcp")?.child("range")
        return NetworkDetails(
            name = root.child("name")?.text() ?: "",
            uuid = root.child("uuid")?.text(),
            bridge = root.child("bridge")?.attr("name"),
            forwardMode = root.child("forward")?.let { it.attr("mode") ?: "nat" } ?: "isolated",
            ipAddress = ip?.attr("address"),
            netmask = ip?.attr("netmask"),
            prefix = ip?.attr("prefix"),
            dhcpStart = range?.attr("start"),
            dhcpEnd = range?.attr("end"),
        )
    }
}
