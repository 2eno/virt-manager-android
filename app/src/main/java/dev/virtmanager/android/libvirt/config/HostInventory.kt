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
