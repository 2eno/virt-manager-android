package dev.virtmanager.android.libvirt.config

import dev.virtmanager.android.libvirt.CommandRunner
import dev.virtmanager.android.libvirt.ExecResult
import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.libvirt.VirshException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PCI devices libvirt leaves out. A typical AMD desktop: the GPU (03:00.0) and its HDMI audio
 * (03:00.1) sit behind the card's own PCIe switch (01:00.0 / 02:00.0). After a VM used the GPU
 * and its reset failed, the card reads as all ones and libvirt's device list drops both.
 */
class HostPciTest {
    private val sysfs = """
        0000:00:00.0 0x1022 0x14d8 0x060000 - 0 22104d81
        0000:00:01.1 0x1022 0x14db 0x060400 pcieport 1 2210db14
        0000:01:00.0 0x1002 0x1478 0x060400 pcieport 2 02107814
        0000:02:00.0 0x1002 0x1479 0x060400 pcieport 3 02107914
        0000:03:00.0 0x1002 0x744c 0x030000 vfio-pci 14 ffffffff
        0000:03:00.1 0x1002 0xab30 0x040300 vfio-pci 14 ffffffff
        0000:0e:00.0 0x8086 0x125c 0x020000 igc 20 86805c12
        __VMA_LSPCI__
        0000:00:00.0 "Host bridge [0600]" "Advanced Micro Devices, Inc. [AMD] [1022]" "Device [14d8]" "" ""
        0000:03:00.0 "VGA compatible controller [0300]" "Advanced Micro Devices, Inc. [AMD/ATI] [1002]" "Navi 31 [Radeon RX 7900 XT/7900 XTX/7900 GRE/7900M] [744c]" -rc8 "Sapphire Technology Limited [1da2]" "Device [e471]"
        0000:03:00.1 "Audio device [0403]" "Advanced Micro Devices, Inc. [AMD/ATI] [1002]" "Navi 31 HDMI/DP Audio [ab30]" "Sapphire Technology Limited [1da2]" "Device [e471]"
    """.trimIndent()

    private fun nodedev(addr: String, vendor: String, product: String, cls: String): String {
        val (d, b, sf) = addr.split(":")
        val (sl, f) = sf.split(".")
        return "<device><name>pci_${addr.replace(':', '_').replace('.', '_')}</name><capability type='pci'><class>$cls</class>" +
            "<domain>${d.toInt(16)}</domain><bus>${b.toInt(16)}</bus><slot>${sl.toInt(16)}</slot><function>${f.toInt(16)}</function>" +
            "<product id='$product'/><vendor id='$vendor'/></capability></device>"
    }

    @Test fun sysfsRowsAndLspciNames() {
        val devs = HostInventory.parseSysfsPci(sysfs)
        assertEquals(7, devs.size)
        val gpu = devs.first { it.address == "0000:03:00.0" }
        assertEquals("pci_0000_03_00_0", gpu.name)
        assertEquals("Advanced Micro Devices, Inc. [AMD/ATI]", gpu.vendorName)
        assertEquals("Navi 31 [Radeon RX 7900 XT/7900 XTX/7900 GRE/7900M]", gpu.productName)
        assertEquals("VGA compatible controller", gpu.className)
        assertEquals("vfio-pci", gpu.driver)
        assertEquals(14, gpu.iommuGroup)
        assertEquals(listOf("0000:03:00.1"), gpu.groupMembers)
        assertFalse(gpu.responding)
        assertEquals("0000:03:00.0", gpu.settings.pciAddress)
        // lspci without a name ("Device [14d8]") falls back to nothing, the vendor to its name.
        val host = devs.first { it.address == "0000:00:00.0" }
        assertEquals(null, host.productName)
        assertEquals("Advanced Micro Devices, Inc. [AMD]", host.vendorName)
        // No lspci at all: vendor names come from the built-in list.
        val nic = devs.first { it.address == "0000:0e:00.0" }
        assertEquals("Intel Corporation", nic.vendorName)
        assertTrue(nic.responding)
        assertEquals("igc", nic.driver)
    }

    @Test fun devicesLibvirtDroppedAreAddedFromSysfs() {
        val libvirt = listOf(
            HostInventory.parseNodedev(nodedev("0000:00:00.0", "0x1022", "0x14d8", "0x060000"))!!,
            HostInventory.parseNodedev(nodedev("0000:00:01.1", "0x1022", "0x14db", "0x060400"))!!,
            HostInventory.parseNodedev(nodedev("0000:01:00.0", "0x1002", "0x1478", "0x060400"))!!,
            HostInventory.parseNodedev(nodedev("0000:02:00.0", "0x1002", "0x1479", "0x060400"))!!,
            // Listed, but nodedev-dumpxml failed for it.
            HostInventory.placeholder("pci", "pci_0000_0e_00_0"),
        )
        val merged = HostInventory.mergePci(libvirt, HostInventory.parseSysfsPci(sysfs)).sortedBy { it.address }
        assertEquals(
            listOf("0000:00:00.0", "0000:00:01.1", "0000:01:00.0", "0000:02:00.0", "0000:03:00.0", "0000:03:00.1", "0000:0e:00.0"),
            merged.map { it.address },
        )
        val gpu = merged.first { it.address == "0000:03:00.0" }
        assertFalse(gpu.inLibvirt)
        assertTrue(gpu.description, gpu.description.startsWith("0000:03:00.0 VGA compatible controller: Advanced Micro Devices"))
        // The placeholder got its details from sysfs and is still libvirt's device.
        val nic = merged.first { it.address == "0000:0e:00.0" }
        assertTrue(nic.inLibvirt)
        assertEquals("0x8086", nic.vendorId)
        assertEquals("Ethernet controller", nic.className)
        assertTrue(merged.filter { it.address < "0000:03" }.all { it.inLibvirt && it.responding })
    }

    @Test fun placeholderKeepsThePciAddress() {
        val p = HostInventory.placeholder("pci", "pci_0000_03_00_1")
        assertEquals("0000:03:00.1", p.address)
        assertEquals("0000:03:00.1", p.settings.pciAddress)
        assertEquals("usb_1_5", HostInventory.placeholder("usb", "usb_1_5").address)
    }

    /** Answers by the first matching key, like a host would. */
    private class FakeHost(private val rules: List<Pair<String, ExecResult>>) : CommandRunner {
        override suspend fun run(command: String, stdin: String?): ExecResult =
            rules.firstOrNull { command.contains(it.first) }?.second ?: ExecResult(1, "", "error: unexpected")
    }

    @Test fun hostDevicesListsWhatLibvirtLeftOut() = runBlocking {
        val xmls = listOf(
            nodedev("0000:00:00.0", "0x1022", "0x14d8", "0x060000"),
            nodedev("0000:0e:00.0", "0x8086", "0x125c", "0x020000"),
        )
        val host = FakeHost(listOf(
            "nodedev-list" to ExecResult(0, "pci_0000_00_00_0\npci_0000_0e_00_0\n", ""),
            "nodedev-dumpxml" to ExecResult(0, xmls.joinToString("") { "$it\n__VMA_SEP__" }, ""),
            "/sys/bus/pci/devices" to ExecResult(0, sysfs, ""),
        ))
        val devs = VirshClient(host, "qemu:///system").hostDevices("pci")
        assertEquals(7, devs.size)
        assertEquals(devs.map { it.address }.sorted(), devs.map { it.address })
        assertEquals(setOf("0000:00:01.1", "0000:01:00.0", "0000:02:00.0", "0000:03:00.0", "0000:03:00.1"),
            devs.filter { !it.inLibvirt }.map { it.address }.toSet())
    }

    @Test fun sysfsStandsInWhenLibvirtCantListDevices() = runBlocking {
        val noNodedev = ExecResult(1, "", "error: Failed to connect socket to '/var/run/libvirt/virtnodedevd-sock': No such file or directory")
        val devs = VirshClient(FakeHost(listOf("nodedev-list" to noNodedev, "/sys/bus/pci/devices" to ExecResult(0, sysfs, ""))), "qemu:///system")
            .hostDevices("pci")
        assertEquals(7, devs.size)
        assertTrue(devs.none { it.inLibvirt })

        try {
            VirshClient(FakeHost(listOf("nodedev-list" to noNodedev)), "qemu:///system").hostDevices("pci")
            fail("expected libvirt's error")
        } catch (e: VirshException) {
            assertTrue(e.message!!.contains("virtnodedevd"))
        }
    }
}
