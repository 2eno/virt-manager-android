package dev.virtmanager.android.libvirt.config

/** A host device that can be passed through (virt-manager's "USB/PCI Host Device" list). */
data class HostDevice(
    val type: String,          // usb, pci
    val name: String,          // libvirt nodedev name
    val description: String,
    val settings: HostdevSettings,
)

data class OsVariant(val shortId: String, val name: String, val id: String)

object HostInventory {

    fun parseNodedev(xml: String): HostDevice? = runCatching {
        val root = Dom.parse(xml).documentElement
        val name = root.child("name")?.text() ?: return null
        val cap = root.children("capability").firstOrNull() ?: return null
        val product = cap.child("product")
        val vendor = cap.child("vendor")
        val desc = listOfNotNull(vendor?.text(), product?.text()).joinToString(" ").ifBlank { name }
        when (cap.attr("type")) {
            "usb_device" -> {
                val bus = cap.child("bus")?.text()?.toIntOrNull() ?: 0
                val dev = cap.child("device")?.text()?.toIntOrNull() ?: 0
                HostDevice(
                    "usb", name, "%03d:%03d %s".format(bus, dev, desc),
                    HostdevSettings("usb", vendorId = vendor?.attr("id"), productId = product?.attr("id"), description = desc),
                )
            }
            "pci" -> {
                fun n(tag: String) = cap.child(tag)?.text()?.toIntOrNull() ?: 0
                val addr = "%04x:%02x:%02x.%x".format(n("domain"), n("bus"), n("slot"), n("function"))
                HostDevice("pci", name, "$addr $desc", HostdevSettings("pci", pciAddress = addr, description = desc))
            }
            else -> null
        }
    }.getOrNull()

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
