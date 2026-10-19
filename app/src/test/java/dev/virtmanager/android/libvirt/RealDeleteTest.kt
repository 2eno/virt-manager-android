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
 * Deleting VMs and unfinished setups through a real libvirtd. Opt-in: VMA_IT_REAL_URI (e.g.
 * qemu:///system) with local virsh, the "default" pool in /var/lib/libvirt/images and the
 * "default" network.
 */
class RealDeleteTest {
    private val uri = System.getenv("VMA_IT_REAL_URI")
    private lateinit var virsh: VirshClient
    private val images = "/var/lib/libvirt/images"
    private val vms = listOf("del-a", "del-b", "del-c")
    private val loose = File("/srv/vma-loose")
    private val stoppedPool = File("/srv/vma-stopped-pool")

    private val local = CommandRunner { command, stdin ->
        val p = ProcessBuilder("sh", "-c", command).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
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
        runCatching { virsh.virsh("pool-destroy", "vma-stopped") }
        runCatching { virsh.virsh("pool-undefine", "vma-stopped") }
        local.run("rm -rf $loose $stoppedPool; rm -f $images/del-*.qcow2", null)
        runCatching { virsh.refreshPool("default") }
    }

    private suspend fun image(path: String) {
        assertTrue(local.run("qemu-img create -f qcow2 $path 16M >/dev/null", null).ok)
    }

    private suspend fun define(name: String, vararg disks: String, setupPending: Boolean = false, uefi: Boolean = false, tpm: Boolean = false) {
        val spec = NewVmSpec(
            name, 256, 1, firmware = if (uefi) Firmware.UEFI else Firmware.BIOS, installMediaPath = "$images/debian-13-netinst.iso",
            diskPath = disks.first(), network = NetworkSource.Virtual("default"), virtType = "qemu", setupPending = setupPending, tpm = tpm,
        )
        var xml = DomainXmlBuilder.build(spec)
        // Further disks go in as written, e.g. a <source pool= volume=> one.
        disks.drop(1).forEachIndexed { i, d ->
            val disk = if (d.startsWith("<")) d else "<disk type='file' device='disk'><driver name='qemu' type='qcow2'/>" +
                "<source file='$d'/><target dev='vd${'b' + i}' bus='virtio'/></disk>"
            xml = xml.replace("</devices>", "$disk</devices>")
        }
        virsh.defineXml(xml)
    }

    @Test fun sharedDisksStayAndOwnOnesGo() = runBlocking {
        image("$images/del-a.qcow2"); image("$images/del-shared.qcow2"); image("$images/del-b.qcow2")
        virsh.refreshPool("default")
        define("del-a", "$images/del-a.qcow2", "$images/del-shared.qcow2")
        define("del-b", "$images/del-b.qcow2", "$images/del-shared.qcow2")

        val candidates = virsh.deletionCandidates("del-a")
        assertEquals(listOf("$images/del-a.qcow2", "$images/del-shared.qcow2"), candidates.map { it.path })
        assertEquals(listOf("del-b"), candidates.first { it.path.endsWith("shared.qcow2") }.sharedWith)
        assertTrue(candidates.first { it.path.endsWith("del-a.qcow2") }.sharedWith.isEmpty())
        // The ISO is never offered.
        assertTrue(candidates.none { it.path.endsWith(".iso") })

        val report = virsh.deleteDomain("del-a", candidates.filter { it.sharedWith.isEmpty() }.map { it.path })
        assertEquals(listOf("$images/del-a.qcow2"), report.deleted)
        assertTrue(report.notDeleted.isEmpty())
        assertFalse(virsh.exists("del-a"))
        assertFalse(File("$images/del-a.qcow2").exists())
        assertTrue(File("$images/del-shared.qcow2").exists())
        assertTrue(File("$images/debian-13-netinst.iso").exists())
        assertTrue(virsh.exists("del-b"))
    }

    @Test fun filesOutsideActivePoolsAreReported() = runBlocking {
        loose.mkdirs(); stoppedPool.mkdirs()
        image("$images/del-a.qcow2"); image("$loose/data.qcow2")
        virsh.virsh("pool-define-as", "vma-stopped", "dir", "--target", stoppedPool.path)
        virsh.virsh("pool-start", "vma-stopped")
        virsh.createVolume("vma-stopped", "del-c.qcow2", 1)
        virsh.virsh("pool-destroy", "vma-stopped")
        virsh.refreshPool("default")
        define("del-a", "$images/del-a.qcow2", "$loose/data.qcow2", "${stoppedPool.path}/del-c.qcow2")

        val paths = virsh.deletionCandidates("del-a").map { it.path }
        val report = virsh.deleteDomain("del-a", paths)
        assertFalse(virsh.exists("del-a"))
        assertEquals(listOf("$images/del-a.qcow2"), report.deleted)
        assertEquals(
            listOf("$loose/data.qcow2", "${stoppedPool.path}/del-c.qcow2"),
            report.notDeleted.map { it.first },
        )
        assertTrue(report.notDeleted.all { it.second == "not in an active storage pool" })
        // Reported, not silently gone or silently left: the files are really still there.
        assertTrue(File("$loose/data.qcow2").exists())
    }

    @Test fun volumeDisksAreOfferedByPath() = runBlocking {
        image("$images/del-a.qcow2"); image("$images/del-vol.qcow2")
        virsh.refreshPool("default")
        define("del-a", "$images/del-a.qcow2",
            "<disk type='volume' device='disk'><driver name='qemu' type='qcow2'/><source pool='default' volume='del-vol.qcow2'/>" +
                "<target dev='vdb' bus='virtio'/></disk>")
        val c = virsh.deletionCandidates("del-a")
        assertEquals(listOf("$images/del-a.qcow2", "$images/del-vol.qcow2"), c.map { it.path })
        val report = virsh.deleteDomain("del-a", c.map { it.path })
        assertEquals(2, report.deleted.size)
        assertFalse(File("$images/del-vol.qcow2").exists())
    }

    @Test fun runningUefiVmWithTpmIsDeletedCompletely() = runBlocking {
        image("$images/del-c.qcow2")
        virsh.refreshPool("default")
        define("del-c", "$images/del-c.qcow2", uefi = true, tpm = true)
        virsh.startWithNetworks("del-c")
        assertTrue(virsh.domInfo("del-c").state.isActive)
        val xml = virsh.dumpXml("del-c", inactive = true)
        val nvram = Regex("<nvram[^>]*>([^<]+)</nvram>").find(xml)?.groupValues?.get(1)
        val tpmState = File("/var/lib/libvirt/swtpm/" + Parsers.parseDomainXml(xml).uuid)
        assertTrue("NVRAM created on start", nvram != null && File(nvram).exists())
        val report = virsh.deleteDomain("del-c", listOf("$images/del-c.qcow2"))
        assertFalse(virsh.exists("del-c"))
        assertEquals(listOf("$images/del-c.qcow2"), report.deleted)
        assertFalse("NVRAM $nvram left behind", File(nvram!!).exists())
        assertFalse("TPM state left behind", tpmState.exists())
    }

    @Test fun unfinishedSetupIsMarkedUntilFirstStart() = runBlocking {
        image("$images/del-a.qcow2"); image("$images/del-b.qcow2")
        virsh.refreshPool("default")
        define("del-a", "$images/del-a.qcow2", setupPending = true)
        define("del-b", "$images/del-b.qcow2")
        assertEquals(setOf("del-a"), virsh.pendingSetups(listOf("del-a", "del-b")))
        assertTrue(virsh.definition("del-a").setupPending)
        assertFalse(virsh.definition("del-b").setupPending)

        // Editing the configuration (as the customize pages do) keeps the marker.
        val cfg = virsh.inactiveConfig("del-a")
        cfg.title = "edited"
        virsh.saveConfig("del-a", cfg)
        assertEquals(setOf("del-a"), virsh.pendingSetups(listOf("del-a", "del-b")))

        virsh.startWithNetworks("del-a")
        virsh.destroy("del-a")
        assertTrue(virsh.pendingSetups(listOf("del-a", "del-b")).isEmpty())
        assertFalse(virsh.definition("del-a").setupPending)
    }
}
