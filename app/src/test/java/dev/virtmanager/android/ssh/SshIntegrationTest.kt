package dev.virtmanager.android.ssh

import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.libvirt.NetworkSource
import dev.virtmanager.android.libvirt.NewVmSpec
import dev.virtmanager.android.libvirt.VirshClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * End-to-end test against a real sshd and virsh using libvirt's built-in `test:///default`
 * driver (no hypervisor needed). Opt-in: set VMA_IT_SSH_PORT and VMA_IT_SSH_KEY (path to an
 * OpenSSH private key authorised for VMA_IT_SSH_USER, default root, on 127.0.0.1).
 */
class SshIntegrationTest {
    private val port = System.getenv("VMA_IT_SSH_PORT")?.toIntOrNull()
    private val keyPath = System.getenv("VMA_IT_SSH_KEY")
    private val user = System.getenv("VMA_IT_SSH_USER") ?: "root"
    private lateinit var session: SshSession
    private lateinit var virsh: VirshClient
    private val seenFingerprints = mutableListOf<String>()

    private val knownHosts = object : KnownHosts {
        val map = mutableMapOf<String, Pair<String, String>>()
        override fun lookup(hostId: String) = map[hostId]
        override fun save(hostId: String, keyType: String, key: String) { map[hostId] = keyType to key }
    }

    @Before fun connect() = runBlocking<Unit> {
        assumeTrue("integration env not configured", port != null && keyPath != null)
        session = SshSession.connect(
            SshTarget("127.0.0.1", port!!, user, null, File(keyPath!!).readText(), null, compression = true),
            knownHosts,
        ) { _, _, type, fp, changed ->
            assertEquals(false, changed)
            seenFingerprints += "$type $fp"
            true
        }
        virsh = VirshClient(session, "test:///default")
    }

    @After fun close() {
        if (::session.isInitialized) session.close()
    }

    @Test fun hostKeyIsRememberedAfterFirstUse() = runBlocking<Unit> {
        assertEquals(1, seenFingerprints.size)
        assertTrue(seenFingerprints[0].startsWith("ssh-ed25519 SHA256:"))
        // Second connection must not prompt again.
        val again = SshSession.connect(
            SshTarget("127.0.0.1", port!!, user, null, File(keyPath!!).readText(), null, compression = false),
            knownHosts,
        ) { _, _, _, _, _ -> error("should not prompt for a known host") }
        assertEquals("ok\n", again.run("echo ok", null).stdout)
        again.close()
    }

    @Test fun pollsHostAndDomains() = runBlocking<Unit> {
        val snap = virsh.pollHost()
        val test = snap.domains.single { it.name == "test" }
        assertEquals(DomainState.RUNNING, test.state)
        // The test driver implements nodecpustats but not nodememstats; both are optional.
        assertNotNull(snap.cpu)

        val details = virsh.hostDetails()
        assertEquals("test:///default", details.uri)
        assertNotNull(details.node?.cpus)
        assertNotNull(details.version?.library)
    }

    @Test fun readsDefinitionsNetworksAndPools() = runBlocking<Unit> {
        val def = virsh.definition("test")
        assertEquals("test", def.name)
        assertEquals(DomainState.RUNNING, virsh.domInfo("test").state)
        val nets = virsh.networks()
        assertTrue(nets.any { it.name == "default" && it.active })
        assertEquals("default", virsh.networkDetails("default").name)
        val pools = virsh.pools()
        assertTrue(pools.any { it.name == "default-pool" })
        virsh.volumes("default-pool")
    }

    @Test fun actionsAndStdin() = runBlocking<Unit> {
        virsh.suspend("test")      // each virsh call is a fresh test-driver instance,
        virsh.destroy("test")      // so these just have to succeed against the running VM
        val xml = DomainXmlBuilder.build(NewVmSpec("from-android", 512, 1, network = NetworkSource.None))
            .replace("type='kvm'", "type='test'")
        virsh.defineXml(xml) // throws if virsh rejected the XML read from stdin
        val err = runCatching { virsh.start("does-not-exist") }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("does-not-exist"))
    }

    @Test fun ptyChannel() = runBlocking<Unit> {
        val pty = session.openPty("printf 'tty:'; tty >/dev/null && echo yes")
        val text = pty.input.readBytes().toString(Charsets.UTF_8)
        assertTrue(text, text.contains("tty:yes"))
        pty.close()
    }

    @Test fun portForwarding() = runBlocking<Unit> {
        val echo = ServerSocket(0)
        thread {
            echo.accept().use { s -> s.getOutputStream().write(s.getInputStream().read()) }
        }
        val local = session.forwardLocal("127.0.0.1", echo.localPort)
        Socket("127.0.0.1", local).use { s ->
            s.getOutputStream().write(42)
            assertEquals(42, s.getInputStream().read())
        }
        session.removeForward(local)
        echo.close()
    }
}
