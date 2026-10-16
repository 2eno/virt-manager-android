package dev.virtmanager.android.libvirt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainXmlBuilderTest {

    @Test fun roundTripsThroughParser() {
        val spec = NewVmSpec(
            name = "test-vm",
            memoryMiB = 2048,
            vcpus = 2,
            firmware = Firmware.UEFI,
            installMediaPath = "/isos/debian & co.iso",
            diskPath = "/var/lib/libvirt/images/test-vm.qcow2",
            network = NetworkSource.Virtual("default"),
        )
        val d = Parsers.parseDomainXml(DomainXmlBuilder.build(spec))
        assertEquals("test-vm", d.name)
        assertEquals(spec.uuid, d.uuid)
        assertEquals(2048L * 1024, d.memoryKiB)
        assertEquals(2, d.vcpus)
        assertEquals("efi", d.firmware)
        assertEquals("q35", d.machine)
        assertEquals(listOf("cdrom", "hd"), d.bootDevices)
        assertEquals("/isos/debian & co.iso", d.disks.first { it.device == "cdrom" }.source)
        assertEquals("virtio", d.disks.first { it.device == "disk" }.bus)
        assertEquals("default", d.interfaces.single().source)
        assertEquals("127.0.0.1", d.vnc!!.listen)
        assertTrue(d.hasSerialConsole)
        assertTrue(d.hasGuestAgentChannel)
    }

    @Test fun windowsUsesEmulatedDevices() {
        val d = Parsers.parseDomainXml(
            DomainXmlBuilder.build(
                NewVmSpec("win11", 4096, 4, osFamily = OsFamily.WINDOWS, diskPath = "/x.qcow2", network = NetworkSource.Bridge("br0")),
            ),
        )
        assertEquals("sata", d.disks.single().bus)
        assertEquals("e1000e", d.interfaces.single().model)
        assertEquals("bridge", d.interfaces.single().type)
        assertEquals(listOf("hd"), d.bootDevices)
    }

    @Test fun arm64ForcesUefiAndVirtMachine() {
        val d = Parsers.parseDomainXml(DomainXmlBuilder.build(NewVmSpec("arm", 1024, 1, arch = "aarch64", network = NetworkSource.None)))
        assertEquals("virt", d.machine)
        assertEquals("efi", d.firmware)
        assertTrue(d.interfaces.isEmpty())
    }

    @Test fun nameValidation() {
        assertTrue(DomainXmlBuilder.isValidName("web-01.prod_2"))
        assertFalse(DomainXmlBuilder.isValidName("bad name"))
        assertFalse(DomainXmlBuilder.isValidName("-leading"))
        assertFalse(DomainXmlBuilder.isValidName("a'b"))
        assertFalse(DomainXmlBuilder.isValidName(""))
    }
}
