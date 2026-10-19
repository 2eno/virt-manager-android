package dev.virtmanager.android.ui

import dev.virtmanager.android.libvirt.DiskDevice
import dev.virtmanager.android.ui.components.deletableDisks
import org.junit.Assert.assertEquals
import org.junit.Test

class DeletableDisksTest {
    private fun disk(device: String, target: String?, source: String?) =
        DiskDevice(device, "file", "qcow2", source, target, "virtio", readOnly = device == "cdrom")

    @Test fun onlyTheVmsOwnDiskImages() {
        val disks = listOf(
            disk("disk", "vda", "/var/lib/libvirt/images/web.qcow2"),
            disk("cdrom", "sda", "/var/lib/libvirt/images/debian.iso"), // shared installer: never deleted
            disk("cdrom", "sdb", null),
            disk("disk", "vdb", null),                                  // no backing file
            disk("floppy", "fda", "/tmp/floppy.img"),
        )
        assertEquals(listOf("vda"), deletableDisks(disks).map { it.target })
    }
}
