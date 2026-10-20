package dev.virtmanager.android.libvirt.config

/** A host device that can be passed through (virt-manager's "USB/PCI Host Device" list). */
data class HostDevice(
    val type: String,          // usb, pci
    val name: String,          // libvirt nodedev name
    val address: String,       // "0000:01:00.0" or "001:003" (bus:device)
    val vendorId: String?,
    val productId: String?,
    val vendorName: String?,
    val productName: String?,
    val className: String? = null,      // PCI class, e.g. "VGA compatible controller"
    val driver: String? = null,
    val iommuGroup: Int? = null,
    /** Other PCI functions in the same IOMMU group; they usually have to be passed through too. */
    val groupMembers: List<String> = emptyList(),
    val settings: HostdevSettings,
    /**
     * False for a PCI device the host has but libvirt's device list leaves out: libvirt drops
     * devices whose config space it can't read. It can still be assigned by address.
     */
    val inLibvirt: Boolean = true,
    /** False if the device's config space reads as all ones, i.e. the device doesn't answer. */
    val responding: Boolean = true,
) {
    private val ids: String? get() = if (vendorId != null && productId != null)
        "[${vendorId.removePrefix("0x")}:${productId.removePrefix("0x")}]" else null

    /** One line, like lspci / lsusb, so devices are recognisable even without names. */
    val description: String
        get() = when (type) {
            "pci" -> "$address " + listOfNotNull(
                className?.let { "$it:" },
                vendorName, productName, ids,
            ).joinToString(" ")
            else -> "Bus ${address.substringBefore(':')} Device ${address.substringAfter(':')}: " +
                listOfNotNull(vendorName, productName, ids).joinToString(" ")
        }

    /** Host bridges, PCI bridges and root ports can't usefully be given to a guest. */
    val isBridge: Boolean get() = type == "pci" && className?.contains("bridge", ignoreCase = true) == true
}

data class OsVariant(val shortId: String, val name: String, val id: String)

object HostInventory {

    fun parseNodedev(xml: String): HostDevice? = runCatching {
        val root = Dom.parse(xml).documentElement
        val name = root.child("name")?.text() ?: return null
        val cap = root.children("capability").firstOrNull() ?: return null
        val product = cap.child("product")
        val vendor = cap.child("vendor")
        val vendorId = vendor?.attr("id")
        val productId = product?.attr("id")
        // libvirt only knows names if the host has the PCI/USB ID databases; fall back to a
        // short list of common vendors so the list stays readable.
        val vendorName = vendor?.text() ?: vendorId?.let { knownVendors[it.lowercase()] }
        val driver = root.child("driver")?.child("name")?.text()
        when (cap.attr("type")) {
            "usb_device" -> {
                val bus = cap.child("bus")?.text()?.toIntOrNull() ?: 0
                val dev = cap.child("device")?.text()?.toIntOrNull() ?: 0
                HostDevice(
                    type = "usb", name = name, address = "%03d:%03d".format(bus, dev),
                    vendorId = vendorId, productId = productId, vendorName = vendorName, productName = product?.text(),
                    driver = driver,
                    settings = HostdevSettings("usb", vendorId = vendorId, productId = productId, usbBus = bus, usbDevice = dev),
                )
            }
            "pci" -> {
                fun n(el: org.w3c.dom.Element?, tag: String) = el?.child(tag)?.text()?.toIntOrNull() ?: 0
                val addr = pciAddress(n(cap, "domain"), n(cap, "bus"), n(cap, "slot"), n(cap, "function"))
                val group = cap.child("iommuGroup")
                val members = group?.children("address").orEmpty().map { a ->
                    fun h(attr: String) = a.attr(attr)?.removePrefix("0x")?.toIntOrNull(16) ?: 0
                    pciAddress(h("domain"), h("bus"), h("slot"), h("function"))
                }.filter { it != addr }
                HostDevice(
                    type = "pci", name = name, address = addr,
                    vendorId = vendorId, productId = productId, vendorName = vendorName, productName = product?.text(),
                    className = cap.child("class")?.text()?.let(::pciClassName),
                    driver = driver,
                    iommuGroup = group?.attr("number")?.toIntOrNull(),
                    groupMembers = members,
                    settings = HostdevSettings("pci", pciAddress = addr),
                )
            }
            else -> null
        }
    }.getOrNull()

    fun pciAddress(domain: Int, bus: Int, slot: Int, function: Int) = "%04x:%02x:%02x.%x".format(domain, bus, slot, function)

    private val nodedevPciName = Regex("^pci_([0-9a-f]{4})_([0-9a-f]{2})_([0-9a-f]{2})_([0-7])$")

    /**
     * Stands in for a device libvirt lists but couldn't describe (its `nodedev-dumpxml`
     * failed). For PCI the address is in libvirt's name, so it can still be found and chosen.
     */
    fun placeholder(type: String, name: String): HostDevice {
        val m = nodedevPciName.find(name)
        val addr = m?.let { "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}.${it.groupValues[4]}" }
        return HostDevice(
            type, name, addr ?: name, null, null, null, "details unavailable",
            settings = if (type == "pci" && addr != null) HostdevSettings("pci", pciAddress = addr) else HostdevSettings(type),
        )
    }

    private const val LSPCI_MARKER = "__VMA_LSPCI__"

    /**
     * Lists the host's PCI devices from sysfs, like lspci does, then the names from lspci if it
     * is installed. Readable without root; see [parseSysfsPci].
     */
    val SYSFS_PCI_SCRIPT = listOf(
        "for d in /sys/bus/pci/devices/*; do",
        "  [ -r \"\$d/vendor\" ] || continue",
        "  drv=; [ -e \"\$d/driver\" ] && drv=\$(basename \"\$(readlink \"\$d/driver\")\")",
        "  grp=; [ -e \"\$d/iommu_group\" ] && grp=\$(basename \"\$(readlink \"\$d/iommu_group\")\")",
        // The first 4 bytes are readable without root; all ones means the device doesn't answer.
        "  cfg=\$(od -An -tx1 -N4 \"\$d/config\" 2>/dev/null | tr -d ' \\n')",
        "  echo \"\${d##*/} \$(cat \"\$d/vendor\") \$(cat \"\$d/device\") \$(cat \"\$d/class\") \${drv:--} \${grp:--} \${cfg:--}\"",
        "done",
        "echo $LSPCI_MARKER",
        "for l in lspci /usr/sbin/lspci /sbin/lspci; do \"\$l\" -mm -nn -D 2>/dev/null && break; done",
        "true",
    ).joinToString("\n")

    /** Parses the output of [SYSFS_PCI_SCRIPT]. */
    fun parseSysfsPci(out: String): List<HostDevice> {
        val (devicePart, lspciPart) = out.split(LSPCI_MARKER).let { it[0] to it.getOrElse(1) { "" } }
        val names = parseLspci(lspciPart)
        val rows = devicePart.lines().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 7 || !f[0].matches(Regex("^[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-7]$"))) null else f
        }
        val groups = rows.filter { it[5] != "-" }.groupBy({ it[5] }, { it[0] })
        return rows.map { f ->
            val addr = f[0]
            val vendorId = f[1].lowercase()
            val lspci = names[addr]
            HostDevice(
                type = "pci", name = "pci_" + addr.replace(':', '_').replace('.', '_'), address = addr,
                vendorId = vendorId, productId = f[2].lowercase(),
                vendorName = lspci?.first ?: knownVendors[vendorId], productName = lspci?.second,
                className = pciClassName(f[3]),
                driver = f[4].takeIf { it != "-" },
                iommuGroup = f[5].toIntOrNull(),
                groupMembers = groups[f[5]].orEmpty().filter { it != addr },
                settings = HostdevSettings("pci", pciAddress = addr),
                responding = f[6] != "ffffffff",
            )
        }
    }

    /** `lspci -mm -nn -D`: address → (vendor, device) names, without lspci's "[1002]" ids. */
    fun parseLspci(out: String): Map<String, Pair<String?, String?>> = out.lines().mapNotNull { line ->
        val addr = line.substringBefore(' ').takeIf { it.matches(Regex("^[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-7]$")) }
            ?: return@mapNotNull null
        val quoted = Regex("\"([^\"]*)\"").findAll(line).map { it.groupValues[1] }.toList()
        fun clean(s: String?) = s?.replace(Regex("\\s*\\[[0-9a-f]{4}]$"), "")?.trim()
            ?.takeUnless { it.isEmpty() || it.matches(Regex("^(Device|Vendor) ?[0-9a-f]{0,4}$")) }
        addr to (clean(quoted.getOrNull(1)) to clean(quoted.getOrNull(2)))
    }.toMap()

    /**
     * Libvirt's PCI devices plus the ones only sysfs knows about. A device libvirt lists but
     * couldn't describe takes its details from sysfs; "not responding" comes from sysfs for all.
     */
    fun mergePci(libvirt: List<HostDevice>, sysfs: List<HostDevice>): List<HostDevice> {
        val bySysfs = sysfs.associateBy { it.address }
        val listed = libvirt.map { d ->
            val s = bySysfs[d.address] ?: return@map d
            if (d.vendorId == null) s.copy(name = d.name) else d.copy(responding = s.responding, driver = d.driver ?: s.driver)
        }
        val known = listed.map { it.address }.toSet()
        return listed + sysfs.filter { it.address !in known }.map { it.copy(inLibvirt = false) }
    }

    /** lspci's names for the common PCI classes (class code like "0x030000"). */
    fun pciClassName(code: String): String? {
        val v = code.removePrefix("0x").toIntOrNull(16) ?: return null
        val cls = v shr 16
        val sub = (v shr 8) and 0xff
        return when (cls) {
            0x00 -> "Unclassified device"
            0x01 -> when (sub) {
                0x00 -> "SCSI storage controller"; 0x01 -> "IDE interface"; 0x04 -> "RAID bus controller"
                0x06 -> "SATA controller"; 0x07 -> "Serial Attached SCSI controller"; 0x08 -> "Non-Volatile memory controller"
                else -> "Mass storage controller"
            }
            0x02 -> when (sub) { 0x00 -> "Ethernet controller"; 0x07 -> "Infiniband controller"; else -> "Network controller" }
            0x03 -> when (sub) { 0x00 -> "VGA compatible controller"; 0x02 -> "3D controller"; else -> "Display controller" }
            0x04 -> when (sub) { 0x01 -> "Multimedia audio controller"; 0x03 -> "Audio device"; else -> "Multimedia controller" }
            0x05 -> "Memory controller"
            0x06 -> when (sub) { 0x00 -> "Host bridge"; 0x01 -> "ISA bridge"; 0x04 -> "PCI bridge"; else -> "Bridge" }
            0x07 -> "Communication controller"
            0x08 -> "System peripheral"
            0x09 -> "Input device controller"
            0x0c -> when (sub) { 0x03 -> "USB controller"; 0x05 -> "SMBus"; 0x07 -> "IPMI interface"; else -> "Serial bus controller" }
            0x0d -> "Wireless controller"
            0x10 -> "Encryption controller"
            0x11 -> "Signal processing controller"
            0x12 -> "Processing accelerators"
            0x13 -> "Non-Essential Instrumentation"
            0xff -> "Unassigned class"
            else -> null
        }
    }

    private val knownVendors = mapOf(
        "0x8086" to "Intel Corporation", "0x8087" to "Intel Corp.", "0x10de" to "NVIDIA Corporation",
        "0x1002" to "Advanced Micro Devices, Inc. [AMD/ATI]", "0x1022" to "Advanced Micro Devices, Inc. [AMD]",
        "0x1af4" to "Red Hat, Inc.", "0x1b36" to "Red Hat, Inc.", "0x14e4" to "Broadcom Inc.", "0x10ec" to "Realtek Semiconductor Co., Ltd.",
        "0x15b3" to "Mellanox Technologies", "0x1b4b" to "Marvell Technology Group Ltd.", "0x144d" to "Samsung Electronics Co Ltd",
        "0x1987" to "Phison Electronics Corporation", "0x15b7" to "Sandisk Corp", "0x1c5c" to "SK hynix", "0x126f" to "Silicon Motion, Inc.",
        "0x1d6b" to "Linux Foundation", "0x106b" to "Apple Inc.", "0x1b21" to "ASMedia Technology Inc.", "0x1912" to "Renesas Technology Corp.",
        "0x168c" to "Qualcomm Atheros", "0x17cb" to "Qualcomm", "0x1077" to "QLogic Corp.", "0x1000" to "Broadcom / LSI",
        "0x9005" to "Adaptec", "0x1344" to "Micron Technology Inc", "0x2646" to "Kingston Technology Company, Inc.",
        "0x1e0f" to "KIOXIA Corporation", "0x1cc1" to "ADATA Technology Co., Ltd.", "0x046d" to "Logitech, Inc.",
        "0x0781" to "SanDisk Corp.", "0x0951" to "Kingston Technology", "0x05ac" to "Apple, Inc.", "0x045e" to "Microsoft Corp.",
        "0x1050" to "Yubico.com", "0x0bda" to "Realtek Semiconductor Corp.", "0x2109" to "VIA Labs, Inc.", "0x05e3" to "Genesys Logic, Inc.",
        "0x0424" to "Microchip Technology, Inc.", "0x04e8" to "Samsung Electronics Co., Ltd", "0x0930" to "Toshiba Corp.",
        "0x0b95" to "ASIX Electronics Corp.", "0x10c4" to "Silicon Labs", "0x0403" to "Future Technology Devices International, Ltd",
        "0x1a86" to "QinHeng Electronics", "0x067b" to "Prolific Technology, Inc.", "0x0451" to "Texas Instruments, Inc.",
    )

    /** Parses `osinfo-query os -f short-id,name,id` (a `|`-separated table). */
    fun parseOsinfoQuery(out: String): List<OsVariant> = out.lines()
        .filter { it.contains('|') && !it.trimStart().startsWith("Short ID") }
        .mapNotNull { line ->
            val cols = line.split('|').map { it.trim() }
            if (cols.size < 3 || cols[0].isEmpty() || cols[2].isEmpty()) null else OsVariant(cols[0], cols[1], cols[2])
        }

    /** Used when the host has no libosinfo (osinfo-query); the ids match libosinfo's. */
    val fallbackOs = listOf(
        OsVariant("almalinux10", "AlmaLinux 10", "http://almalinux.org/almalinux/10"),
        OsVariant("almalinux9", "AlmaLinux 9", "http://almalinux.org/almalinux/9"),
        OsVariant("rocky9", "Rocky Linux 9", "http://rockylinux.org/rocky/9"),
        OsVariant("rhel9.4", "Red Hat Enterprise Linux 9.4", "http://redhat.com/rhel/9.4"),
        OsVariant("centos-stream9", "CentOS Stream 9", "http://centos.org/centos-stream/9"),
        OsVariant("fedora41", "Fedora Linux 41", "http://fedoraproject.org/fedora/41"),
        OsVariant("debian13", "Debian 13", "http://debian.org/debian/13"),
        OsVariant("debian12", "Debian 12", "http://debian.org/debian/12"),
        OsVariant("ubuntu24.04", "Ubuntu 24.04 LTS", "http://ubuntu.com/ubuntu/24.04"),
        OsVariant("ubuntu22.04", "Ubuntu 22.04 LTS", "http://ubuntu.com/ubuntu/22.04"),
        OsVariant("opensuse15.6", "openSUSE Leap 15.6", "http://opensuse.org/opensuse/15.6"),
        OsVariant("archlinux", "Arch Linux", "http://archlinux.org/archlinux/rolling"),
        OsVariant("alpinelinux3.20", "Alpine Linux 3.20", "http://alpinelinux.org/alpinelinux/3.20"),
        OsVariant("nixos-unstable", "NixOS Unstable", "http://nixos.org/nixos/unstable"),
        OsVariant("freebsd14.1", "FreeBSD 14.1", "http://freebsd.org/freebsd/14.1"),
        OsVariant("openbsd7.5", "OpenBSD 7.5", "http://openbsd.org/openbsd/7.5"),
        OsVariant("win11", "Microsoft Windows 11", "http://microsoft.com/win/11"),
        OsVariant("win10", "Microsoft Windows 10", "http://microsoft.com/win/10"),
        OsVariant("win2k22", "Microsoft Windows Server 2022", "http://microsoft.com/win/2k22"),
        OsVariant("win2k25", "Microsoft Windows Server 2025", "http://microsoft.com/win/2k25"),
        OsVariant("linux2022", "Generic Linux 2022", "http://libosinfo.org/linux/2022"),
        OsVariant("linux2024", "Generic Linux 2024", "http://libosinfo.org/linux/2024"),
        OsVariant("generic", "Generic OS", "http://libosinfo.org/generic"),
    )
}
