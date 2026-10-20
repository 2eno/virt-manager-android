package dev.virtmanager.android.libvirt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuAccessTest {
    private val qemu = 64055
    private val kvmGroup = 108

    @Test fun identityFromCapabilities() {
        val caps = """
            <capabilities><host>
              <secmodel><model>apparmor</model><doi>0</doi></secmodel>
              <secmodel><model>dac</model><doi>0</doi>
                <baselabel type='kvm'>+64055:+994</baselabel>
                <baselabel type='qemu'>+64055:+108</baselabel>
              </secmodel>
            </host></capabilities>
        """.trimIndent()
        assertEquals(64055 to 994, QemuAccess.qemuIdentity(caps, "kvm"))
        assertEquals(64055 to 108, QemuAccess.qemuIdentity(caps, "qemu"))
        assertNull(QemuAccess.qemuIdentity("<capabilities><host/></capabilities>", "kvm"))
    }

    @Test fun classicPermissionBits() {
        val home = QemuAccess.Dir("/home/zeno", 1000, 1000, "700".toInt(8))
        assertFalse(QemuAccess.canSearch(home, qemu, setOf(kvmGroup)))
        assertTrue(QemuAccess.canSearch(home.copy(mode = "711".toInt(8)), qemu, setOf(kvmGroup)))
        // Group access counts when QEMU's user is in the folder's group.
        assertTrue(QemuAccess.canSearch(home.copy(mode = "750".toInt(8)), qemu, setOf(kvmGroup, 1000)))
        assertFalse(QemuAccess.canSearch(home.copy(mode = "750".toInt(8)), qemu, setOf(kvmGroup)))
        assertTrue(QemuAccess.canSearch(home, 0, emptySet()))
    }

    @Test fun aclRules() {
        val base = QemuAccess.Dir("/home/zeno", 1000, 1000, "700".toInt(8))
        val granted = base.copy(acl = listOf("user::rwx", "user:64055:--x", "group::---", "mask::--x", "other::---"))
        assertTrue(QemuAccess.canSearch(granted, qemu, setOf(kvmGroup)))
        // The mask limits named entries.
        val masked = base.copy(acl = listOf("user::rwx", "user:64055:--x\t#effective:---", "group::---", "mask::---", "other::---"))
        assertFalse(QemuAccess.canSearch(masked, qemu, setOf(kvmGroup)))
        // A minimal ACL is just the mode bits.
        val plain = base.copy(acl = listOf("user::rwx", "group::---", "other::---"))
        assertFalse(QemuAccess.canSearch(plain, qemu, setOf(kvmGroup)))
        val namedGroup = base.copy(acl = listOf("user::rwx", "group::---", "group:108:--x", "mask::--x", "other::---"))
        assertTrue(QemuAccess.canSearch(namedGroup, qemu, setOf(kvmGroup)))
    }

    @Test fun parsesTheScriptOutput() {
        val out = """
            U libvirt-qemu
            G 108 
            P /home/zeno/isos/debian 13.iso
            D 1000 1000 755 /home/zeno/isos
            A user::rwx
            A group::r-x
            A other::r-x
            D 1000 1000 700 /home/zeno
            A user::rwx
            A group::---
            A other::---
            D 0 0 755 /home
            D 0 0 755 /
            P /var/lib/libvirt/images/vm.qcow2
            D 0 0 711 /var/lib/libvirt/images
            D 0 0 755 /var/lib/libvirt
            D  /var/lib
            D 0 0 755 /var
            D 0 0 755 /
        """.trimIndent()
        val p = QemuAccess.parse(out, qemu, kvmGroup)!!
        assertEquals("libvirt-qemu", p.user)
        assertEquals(mapOf("/home/zeno/isos/debian 13.iso" to listOf("/home/zeno")), p.blocked)
        assertEquals("setfacl -m u:libvirt-qemu:x /home/zeno", p.command)
        assertNull(QemuAccess.parse(out.replace("D 1000 1000 700 /home/zeno", "D 1000 1000 711 /home/zeno")
            .replace("A group::---\nA other::---", "A group::---\nA other::--x"), qemu, kvmGroup))
    }

    @Test fun scriptQuotesPaths() {
        val s = QemuAccess.script(qemu, listOf("/home/zeno/it's here.iso"))
        assertTrue(s, s.contains("dirname -- '/home/zeno/it'\"'\"'s here.iso'"))
        assertTrue(s.contains("getent passwd 64055"))
    }
}
