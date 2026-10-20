package dev.virtmanager.android.libvirt

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Snapshots through a real libvirtd: UEFI VMs (raw NVRAM, libvirt's default) can't have
 * internal snapshots, so they get external ones, which must revert, delete and not leave
 * files behind when the VM is deleted. Opt-in: VMA_IT_REAL_URI with local virsh.
 */
class RealSnapshotTest {
    private val uri = System.getenv("VMA_IT_REAL_URI")
    private val images = "/var/lib/libvirt/images"
    private val vms = listOf("snap-bios", "snap-uefi")
    private lateinit var virsh: VirshClient

    private val local = CommandRunner { command, stdin ->
        val p = ProcessBuilder("sh", "-c", command).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        ExecResult(p.exitValue(), out, err)
    }

    @Before fun setUp() = runBlocking<Unit> {
        assumeTrue(uri != null)
        virsh = VirshClient(local, uri!!)
        cleanup()
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (!::virsh.isInitialized) return@runBlocking
        for (vm in vms) {
            runCatching { virsh.destroy(vm) }
            runCatching { virsh.undefine(vm) }
        }
        local.run("rm -f $images/snap-bios* $images/snap-uefi*", null)
        runCatching { virsh.refreshPool("default") }
    }

    private suspend fun define(name: String, uefi: Boolean) {
        assertTrue(local.run("qemu-img create -f qcow2 $images/$name.qcow2 64M >/dev/null", null).ok)
        virsh.defineXml(DomainXmlBuilder.build(NewVmSpec(
            name, 256, 1, firmware = if (uefi) Firmware.UEFI else Firmware.BIOS,
            installMediaPath = "$images/debian-13-netinst.iso", diskPath = "$images/$name.qcow2",
            network = NetworkSource.None, virtType = "qemu",
        )))
    }

    private fun files(prefix: String) = File(images).list().orEmpty().filter { it.startsWith(prefix) }.sorted()

    @Test fun biosVmGetsInternalSnapshots() = runBlocking {
        define("snap-bios", uefi = false)
        virsh.start("snap-bios")
        assertEquals(VirshClient.SnapshotKind.INTERNAL, virsh.createSnapshot("snap-bios", "s1", "first"))
        assertEquals(listOf(false), virsh.snapshots("snap-bios").map { it.external })
        virsh.revertSnapshot("snap-bios", "s1")
        virsh.deleteSnapshot("snap-bios", "s1")
        assertEquals(listOf("snap-bios.qcow2"), files("snap-bios"))
    }

    @Test fun uefiVmFallsBackToExternalSnapshots() = runBlocking {
        define("snap-uefi", uefi = true)
        virsh.start("snap-uefi")
        // Running: disk overlays plus a memory file; the install ISO gets no overlay.
        assertEquals(VirshClient.SnapshotKind.EXTERNAL, virsh.createSnapshot("snap-uefi", "s1", null))
        assertEquals(VirshClient.SnapshotKind.EXTERNAL, virsh.createSnapshot("snap-uefi", "s2", null))
        assertEquals(listOf(true, true), virsh.snapshots("snap-uefi").map { it.external })
        assertTrue(files("snap-uefi").toString(), files("snap-uefi").contains("snap-uefi-s1.mem"))
        assertTrue(virsh.definition("snap-uefi").disks.first { it.device == "cdrom" }.source!!.endsWith(".iso"))

        virsh.revertSnapshot("snap-uefi", "s1")
        assertTrue(virsh.domInfo("snap-uefi").state.isActive)
        virsh.deleteSnapshot("snap-uefi", "s2")
        virsh.deleteSnapshot("snap-uefi", "s1")
        assertTrue(virsh.snapshots("snap-uefi").isEmpty())
        assertEquals(listOf("snap-uefi.qcow2"), files("snap-uefi"))

        // Shut off, libvirt can snapshot the disks internally (only running VMs need pflash).
        virsh.destroy("snap-uefi")
        assertEquals(VirshClient.SnapshotKind.INTERNAL, virsh.createSnapshot("snap-uefi", "off", null))
        assertEquals("shutoff", virsh.snapshots("snap-uefi").single().state)
    }

    @Test fun deletingAVmRemovesItsSnapshotFiles() = runBlocking {
        define("snap-uefi", uefi = true)
        virsh.start("snap-uefi")
        virsh.createSnapshot("snap-uefi", "s1", null)
        virsh.createSnapshot("snap-uefi", "s2", null)
        val candidates = virsh.deletionCandidates("snap-uefi")
        // The current overlay, the older images behind it and both memory files.
        assertEquals(files("snap-uefi").map { "$images/$it" }.sorted(), candidates.map { it.path }.sorted())
        val report = virsh.deleteDomain("snap-uefi", candidates.map { it.path })
        assertTrue(report.notDeleted.toString(), report.notDeleted.isEmpty())
        assertEquals(emptyList<String>(), files("snap-uefi"))
        assertTrue(File("$images/debian-13-netinst.iso").exists())
    }

    /** What a VM with passed-through PCI devices gets: disks only, while it keeps running. */
    @Test fun runningVmDiskOnlySnapshot() = runBlocking {
        define("snap-uefi", uefi = true)
        virsh.start("snap-uefi")
        val def = Parsers.parseDomainXml(virsh.dumpXml("snap-uefi"))
        virsh.virsh(*virsh.externalSnapshotArgs(listOf("snapshot-create-as", "snap-uefi", "d1"), def, "snap-uefi", "d1", withMemory = false).toTypedArray())
        assertTrue(virsh.domInfo("snap-uefi").state.isActive)
        assertEquals("disk-snapshot", virsh.snapshots("snap-uefi").single().state)
        virsh.deleteSnapshot("snap-uefi", "d1")
        assertEquals(listOf("snap-uefi.qcow2"), files("snap-uefi"))
    }
}
