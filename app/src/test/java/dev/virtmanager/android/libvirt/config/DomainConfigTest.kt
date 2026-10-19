package dev.virtmanager.android.libvirt.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainConfigTest {
    /** A VM as virt-manager creates it (the one in the reported screenshot). */
    private fun alma() = DomainConfig.parse(javaClass.getResource("/almalinux10.xml")!!.readText())

    @Test fun listsDevicesLikeVirtManager() {
        assertEquals(
            listOf(
                "VirtIO Disk 1", "SATA CDROM 1", "Controller USB", "Controller PCIe", "NIC :9a:43:2c", "Console 1",
                "Channel (qemu-ga)", "Channel (spice)", "Tablet", "Display Spice", "Sound ich9", "Video Virtio",
                "USB Redirector 1", "USB Redirector 2", "RNG /dev/urandom",
            ),
            alma().devices().map { it.label },
        )
    }

    @Test fun overviewValues() {
        val c = alma()
        assertEquals("almalinux10", c.name)
        assertEquals(Chipset.Q35, c.chipset)
        assertEquals(FirmwareKind.BIOS, c.firmware)
        assertEquals("http://almalinux.org/almalinux/10", c.osInfoId)
        assertEquals("host-passthrough", c.cpuMode)
        assertEquals(4096, c.maxMemoryMiB)
        assertTrue(c.hasSpice)
    }

    @Test fun legacyBootListIsMappedToDevices() {
        val c = alma()
        val boot = c.bootEntries()
        assertEquals(listOf("VirtIO Disk 1"), boot.filter { it.enabled }.map { it.label })
        assertEquals(setOf("SATA CDROM 1", "NIC :9a:43:2c", "USB Redirector 1", "USB Redirector 2"), boot.filterNot { it.enabled }.map { it.label }.toSet())

        val cd = boot.first { it.label == "SATA CDROM 1" }.index
        val disk = boot.first { it.label == "VirtIO Disk 1" }.index
        c.setBootOrder(listOf(cd, disk))
        val xml = c.toXml()
        assertFalse(xml, xml.contains("<boot dev="))
        assertEquals(listOf("SATA CDROM 1", "VirtIO Disk 1"), c.bootEntries().filter { it.enabled }.map { it.label })
    }

    @Test fun chipsetSwitchMovesSataToIde() {
        val c = alma()
        c.setChipset(Chipset.I440FX)
        assertTrue(c.devices().any { it.label == "IDE CDROM 1" })
        assertTrue(c.devices().none { it.label == "Controller PCIe" })
        assertEquals("pc", c.machine)
    }

    @Test fun firmwareAndSecureBoot() {
        val c = alma()
        c.firmware = FirmwareKind.UEFI_SECURE
        assertEquals(FirmwareKind.UEFI_SECURE, c.firmware)
        assertTrue(c.toXml().contains("smm"))
        c.firmware = FirmwareKind.UEFI
        assertEquals(FirmwareKind.UEFI, c.firmware)
        c.firmware = FirmwareKind.BIOS
        assertEquals(FirmwareKind.BIOS, c.firmware)
    }

    @Test fun vcpusAndTopologyValidation() {
        val c = alma()
        c.setVcpus(2, 4)
        assertEquals(2, c.currentVcpus); assertEquals(4, c.maxVcpus)
        assertTrue(runCatching { c.topology = CpuTopology(1, 1, 3, 1) }.isFailure)
        c.topology = CpuTopology(1, 1, 2, 2)
        c.setVcpus(4, 8) // topology no longer matches: dropped instead of producing invalid XML
        assertEquals(null, c.topology)
    }

    @Test fun nextTargetSkipsUsedNames() {
        val c = alma()
        assertEquals("vdb", c.nextTarget("virtio"))
        assertEquals("sdb", c.nextTarget("sata"))
        assertEquals("hda", c.nextTarget("ide"))
    }

    @Test fun diskEditingChangesBusAndTarget() {
        val c = alma()
        val disk = c.devices().first { it.label == "VirtIO Disk 1" }
        val el = c.deviceElement(disk.index)!!
        (DeviceSettings.read(el) as DiskSettings).copy(bus = "sata", cache = "none").apply(el, c)
        assertEquals("SATA Disk 1", c.devices().first { it.tag == "disk" }.label)
        val s = DeviceSettings.read(c.deviceElement(disk.index)!!) as DiskSettings
        assertEquals("none", s.cache)
        assertTrue(c.deviceXml(disk.index)!!.contains("dev=\"sdb\"") || c.deviceXml(disk.index)!!.contains("dev='sdb'"))
    }

    @Test fun graphicsKeepsPasswordAndListen() {
        val c = alma()
        val gfx = c.devices().first { it.tag == "graphics" }
        GraphicsSettings("vnc", "127.0.0.1", autoport = true, password = "pw").apply(c.deviceElement(gfx.index)!!, c)
        assertEquals(GraphicsSettings("vnc", "127.0.0.1", true, null, "pw", false), DeviceSettings.read(c.deviceElement(gfx.index)!!))
    }

    @Test fun watchdogsShareOneAction() {
        val c = alma()
        c.addDevice(WatchdogSettings("itco", "reset").toXml(c))
        c.addDevice(WatchdogSettings("i6300esb", "poweroff").toXml(c))
        val actions = c.devices().filter { it.tag == "watchdog" }.map { (DeviceSettings.read(c.deviceElement(it.index)!!) as WatchdogSettings).action }
        assertEquals(listOf("poweroff", "poweroff"), actions)
    }

    @Test fun virtiofsEnablesSharedMemory() {
        val c = alma()
        c.addDevice(FilesystemSettings("virtiofs", "/srv", "share").toXml(c))
        assertTrue(c.sharedMemory)
    }

    @Test fun removeAndReplace() {
        val c = alma()
        val rng = c.devices().first { it.tag == "rng" }
        c.replaceDevice(rng.index, "<rng model='virtio'><backend model='random'>/dev/random</backend></rng>")
        assertTrue(c.devices().any { it.label == "RNG /dev/random" })
        c.removeDevice(c.devices().first { it.tag == "rng" }.index)
        assertTrue(c.devices().none { it.tag == "rng" })
        val pcie = c.devices().first { it.label == "Controller PCIe" }
        assertFalse(pcie.removable)
        assertTrue(runCatching { c.removeDevice(pcie.index) }.isFailure)
    }

    @Test fun osinfoQueryParsing() {
        val out = """
             Short ID             | Name                       | ID
            ----------------------+----------------------------+-----------------------------------
             almalinux10          | AlmaLinux 10               | http://almalinux.org/almalinux/10
             win11                | Microsoft Windows 11       | http://microsoft.com/win/11
        """.trimIndent()
        assertEquals(
            listOf(OsVariant("almalinux10", "AlmaLinux 10", "http://almalinux.org/almalinux/10"), OsVariant("win11", "Microsoft Windows 11", "http://microsoft.com/win/11")),
            HostInventory.parseOsinfoQuery(out),
        )
    }

    @Test fun nodedevParsing() {
        val usb = HostInventory.parseNodedev(
            "<device><name>usb_1_3</name><capability type='usb_device'><bus>1</bus><device>3</device>" +
                "<product id='0xc52b'>Unifying Receiver</product><vendor id='0x046d'>Logitech, Inc.</vendor></capability></device>",
        )!!
        assertEquals("001:003 Logitech, Inc. Unifying Receiver", usb.description)
        assertEquals("0x046d", usb.settings.vendorId)
        val pci = HostInventory.parseNodedev(
            "<device><name>pci_0000_01_00_0</name><capability type='pci'><class>0x030000</class><domain>0</domain><bus>1</bus><slot>0</slot>" +
                "<function>0</function><product id='0x2484'>GA104</product><vendor id='0x10de'>NVIDIA Corporation</vendor></capability></device>",
        )!!
        assertEquals("0000:01:00.0", pci.settings.pciAddress)
        assertTrue(pci.settings.toXml(alma()).contains("bus='0x01'"))
    }

    @Test fun capabilitiesFilterOptions() {
        val caps = DomainCapabilities.parse(
            "<domainCapabilities><os><enum name='firmware'><value>efi</value></enum></os><devices>" +
                "<graphics supported='yes'><enum name='type'><value>vnc</value><value>dbus</value></enum></graphics>" +
                "</devices></domainCapabilities>",
        )
        assertEquals(listOf("vnc"), caps.filter(listOf("vnc", "spice"), "graphics", "type"))
        assertEquals(listOf("vnc", "spice"), caps.filter(listOf("vnc", "spice"), "graphics", "type", keep = "spice"))
        assertEquals(listOf("virtio", "qxl"), caps.filter(listOf("virtio", "qxl"), "video", "modelType")) // not reported: keep all
        assertEquals(true, caps.firmwareEfi)
    }
}
