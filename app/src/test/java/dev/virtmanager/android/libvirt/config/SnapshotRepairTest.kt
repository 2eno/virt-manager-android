package dev.virtmanager.android.libvirt.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undoing the CD-ROM overlay that reverting an external snapshot leaves behind. */
class SnapshotRepairTest {
    private val iso = "/var/lib/libvirt/images/debian-13.3.0-amd64-netinst.iso"
    private val overlay = "/var/lib/libvirt/images/debian-13.3.0-amd64-netinst.1792594964"

    // What libvirt 11 records for the snapshot after a revert: the CD-ROM became "external".
    private val snapshot = """
        <domainsnapshot>
          <name>snapshot-20261020-1344</name>
          <memory snapshot='no'/>
          <disks>
            <disk name='vda' snapshot='external' type='file'><driver type='qcow2'/><source file='/var/lib/libvirt/images/AI-Chat.1792594964'/></disk>
            <disk name='sdb' snapshot='external' type='file'><driver type='qcow2'/><source file='$overlay'/></disk>
          </disks>
          <domain type='kvm'><name>AI-Chat</name><devices>
            <disk type='file' device='disk'><driver name='qemu' type='qcow2'/><source file='/var/lib/libvirt/images/AI-Chat.qcow2'/><target dev='vda' bus='virtio'/></disk>
            <disk type='file' device='cdrom'><driver name='qemu' type='raw'/><source file='$iso'/><target dev='sdb' bus='sata'/><readonly/></disk>
          </devices></domain>
        </domainsnapshot>
    """.trimIndent()

    private val domain = """
        <domain type='kvm'><name>AI-Chat</name><devices>
          <disk type='file' device='disk'><driver name='qemu' type='qcow2'/><source file='/var/lib/libvirt/images/AI-Chat.snapshot-20261021-1715'/><target dev='vda' bus='virtio'/></disk>
          <disk type='file' device='cdrom'><driver name='qemu' type='qcow2'/><source file='$overlay'/>
            <backingStore type='file'><format type='raw'/><source file='$iso'/></backingStore>
            <target dev='sdb' bus='sata'/><readonly/></disk>
        </devices></domain>
    """.trimIndent()

    @Test fun snapshotSkipsTheCdromAgain() {
        val fixed = SnapshotRepair.fixSnapshot(snapshot)!!
        val disks = Dom.parse(fixed).documentElement.child("disks")!!.children("disk").associateBy { it.attr("name") }
        assertEquals("external", disks["vda"]!!.attr("snapshot"))
        assertEquals("no", disks["sdb"]!!.attr("snapshot"))
        assertNull(disks["sdb"]!!.child("source"))
        assertNull(SnapshotRepair.fixSnapshot(fixed))
    }

    @Test fun cdromGetsItsIsoBackAsRaw() {
        val fixed = SnapshotRepair.fixDomain(domain, snapshot)!!
        val disks = Dom.parse(fixed).documentElement.child("devices")!!.children("disk").associateBy { it.child("target")!!.attr("dev") }
        val cdrom = disks["sdb"]!!
        assertEquals(iso, cdrom.child("source")!!.attr("file"))
        assertEquals("raw", cdrom.child("driver")!!.attr("type"))
        assertNull(cdrom.child("backingStore"))
        // The system disk keeps its overlay: that one is the snapshot's.
        assertEquals("/var/lib/libvirt/images/AI-Chat.snapshot-20261021-1715", disks["vda"]!!.child("source")!!.attr("file"))
        assertNull(SnapshotRepair.fixDomain(fixed, snapshot))
    }

    @Test fun otherMediaAreLeftAlone() {
        val changed = domain.replace(overlay, "/var/lib/libvirt/images/other.iso")
        assertNull(SnapshotRepair.fixDomain(changed, snapshot))
    }

    @Test fun overlayNames() {
        assertTrue(SnapshotRepair.isOverlayOf(overlay, iso))
        assertFalse(SnapshotRepair.isOverlayOf("/other/debian-13.3.0-amd64-netinst.1792594964", iso))
        assertFalse(SnapshotRepair.isOverlayOf("/var/lib/libvirt/images/debian-13.3.0-amd64-netinst.snapshot-1", iso))
        assertFalse(SnapshotRepair.isOverlayOf(iso, iso))
    }
}
