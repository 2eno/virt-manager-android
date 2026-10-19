package dev.virtmanager.android.libvirt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deleting VMs and their disks, and the one-process virsh scripts it relies on. */
class DeleteDomainTest {

    /** Answers each command with the first rule whose key it contains. */
    private class FakeHost(vararg rules: Pair<String, ExecResult>) : CommandRunner {
        val rules = rules.toMutableList()
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, stdin: String?): ExecResult {
            commands += command
            return rules.firstOrNull { command.contains(it.first) }?.second ?: ExecResult(1, "", "error: unexpected command")
        }
    }

    private fun ok(out: String = "") = ExecResult(0, out, "")
    private fun err(msg: String) = ExecResult(1, "", "error: $msg\n")

    private val shutOff = "Id: -\nName: web\nState: shut off\n"

    @Test fun undefineDropsUnsupportedFlagsStepByStep() = runBlocking {
        val host = FakeHost(
            "--tpm" to err("command 'undefine' doesn't support option --tpm"),
            "undefine" to ok(),
            "domuuid" to ok("1234\n"),
        )
        VirshClient(host, "qemu:///system").undefine("web")
        val undefines = host.commands.filter { "undefine" in it }
        assertEquals(2, undefines.size)
        assertTrue(undefines[1], undefines[1].endsWith("undefine web --managed-save --snapshots-metadata --nvram"))
    }

    @Test fun undefineSucceedsWhenTheVmIsGoneDespiteAnError() = runBlocking {
        // e.g. the definition was removed but cleaning up NVRAM failed afterwards.
        val host = FakeHost("undefine" to err("cannot delete nvram"), "domuuid" to err("Domain not found"))
        VirshClient(host, "qemu:///system").undefine("web")
        assertEquals(1, host.commands.count { "undefine" in it })
    }

    @Test fun undefineReportsRealErrors() = runBlocking {
        val host = FakeHost("undefine" to err("Requested operation is not valid: domain has snapshots"), "domuuid" to ok("1\n"))
        try {
            VirshClient(host, "qemu:///system").undefine("web")
            fail("expected an error")
        } catch (e: VirshException) {
            assertTrue(e.message!!.contains("snapshots"))
        }
    }

    @Test fun deleteForcesOffARunningVmFirst() = runBlocking {
        val host = FakeHost("dominfo" to ok("State: running\n"), "destroy" to ok(), "undefine" to ok())
        VirshClient(host, "qemu:///system").deleteDomain("web")
        val destroy = host.commands.indexOfFirst { "destroy web" in it }
        val undefine = host.commands.indexOfFirst { "undefine web" in it }
        assertTrue(destroy in 0 until undefine)
    }

    @Test fun deleteReportsFilesItCouldNotRemove() = runBlocking {
        val host = FakeHost(
            "dominfo" to ok(shutOff),
            "undefine" to ok(),
            "vol-delete /pool/web.qcow2" to ok(),
            "vol-delete /srv/vm/data.img" to err("failed to get vol '/srv/vm/data.img'\nStorage volume not found: no storage vol with matching path"),
            "test -e /srv/vm/data.img" to ok(),
            "vol-delete /gone.qcow2" to err("Storage volume not found"),
            "test -e /gone.qcow2" to err("x"),
        )
        val report = VirshClient(host, "qemu:///system").deleteDomain("web", listOf("/pool/web.qcow2", "/srv/vm/data.img", "/gone.qcow2"))
        assertEquals(listOf("/pool/web.qcow2"), report.deleted)
        assertEquals(listOf("/srv/vm/data.img" to "not in an active storage pool"), report.notDeleted)
    }

    @Test fun deleteRemovesNothingButTheDefinitionByDefault() = runBlocking {
        val host = FakeHost("dominfo" to ok(shutOff), "undefine" to ok())
        val report = VirshClient(host, "qemu:///system").deleteDomain("web")
        assertTrue(report.deleted.isEmpty() && report.notDeleted.isEmpty())
        assertTrue(host.commands.none { "vol-delete" in it || "--storage" in it || "--remove-all-storage" in it })
    }

    @Test fun candidatesAreOwnDisksWithOtherUsers() = runBlocking {
        val web = """
            <domain type='kvm'><name>web</name><devices>
              <disk type='file' device='disk'><source file='/pool/web.qcow2'/><target dev='vda' bus='virtio'/></disk>
              <disk type='file' device='disk'><source file='/pool/shared.qcow2'/><target dev='vdb' bus='virtio'/></disk>
              <disk type='volume' device='disk'><source pool='fast' volume='data.qcow2'/><target dev='vdc' bus='virtio'/></disk>
              <disk type='file' device='cdrom'><source file='/iso/debian.iso'/><target dev='sda' bus='sata'/></disk>
              <disk type='network' device='disk'><source protocol='rbd' name='rbd/img'/><target dev='vdd' bus='virtio'/></disk>
            </devices></domain>
        """.trimIndent()
        val db = """
            <domain type='kvm'><name>db</name><devices>
              <disk type='file' device='disk'><source file='/pool/shared.qcow2'/><target dev='vda' bus='virtio'/></disk>
              <disk type='file' device='disk'><source file='/fast/data.qcow2'/><target dev='vdb' bus='virtio'/></disk>
            </devices></domain>
        """.trimIndent()
        val host = FakeHost(
            "dumpxml --inactive --security-info web" to ok(web),
            "vol-path --pool fast data.qcow2" to ok("/fast/data.qcow2\n"),
            "list --all --name" to ok("web\ndb\n"),
            "dumpxml --inactive db" to ok("$db\n__VMA_SEP__"),
        )
        val c = VirshClient(host, "qemu:///system").deletionCandidates("web")
        assertEquals(
            listOf(
                VirshClient.DiskCandidate("vda", "/pool/web.qcow2", emptyList()),
                VirshClient.DiskCandidate("vdb", "/pool/shared.qcow2", listOf("db")),
                VirshClient.DiskCandidate("vdc", "/fast/data.qcow2", listOf("db")),
            ),
            c,
        )
    }

    @Test fun scriptRunsOneVirshAndSplitsOnTheMarker() = runBlocking {
        // virsh's echo prints no newline; a failing command prints nothing to stdout.
        val host = FakeHost("virsh" to ok("<a/>\n__VMA_SEP__\n__VMA_SEP__<b/>\n__VMA_SEP__"))
        val out = VirshClient(host, "qemu:///system").script(
            listOf(listOf("nodedev-dumpxml", "pci_0000_00_00_0"), listOf("metadata", "my vm", "https://x/y"), listOf("nodedev-dumpxml", "b")),
        )
        assertEquals(listOf("<a/>", null, "<b/>"), out)
        val cmd = host.commands.single()
        assertTrue(cmd, cmd.contains("nodedev-dumpxml pci_0000_00_00_0 ; echo __VMA_SEP__ ; metadata \"my vm\" https://x/y ; echo __VMA_SEP__"))
    }

    @Test fun pendingSetupsComeFromTheMetadataMarker() = runBlocking {
        val host = FakeHost("virsh" to ok("__VMA_SEP__<vma:setup state='customizing'/>\n__VMA_SEP__"))
        val pending = VirshClient(host, "qemu:///system").pendingSetups(listOf("done", "new"))
        assertEquals(setOf("new"), pending)
    }
}
