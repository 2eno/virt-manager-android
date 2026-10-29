package dev.virtmanager.android.ssh

import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.libvirt.VirshClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ProxyJump against real sshds on 127.0.0.1. Opt-in: VMA_IT_SSH_PORT (the target, with virsh),
 * VMA_IT_JUMP_PORT (a jump host allowing TCP forwarding), VMA_IT_NOFWD_PORT (one with
 * "AllowTcpForwarding no"), VMA_IT_SSH_KEY and VMA_IT_SSH_USER as in [SshIntegrationTest].
 */
class JumpHostIntegrationTest {
    private val targetPort = System.getenv("VMA_IT_SSH_PORT")?.toIntOrNull()
    private val jumpPort = System.getenv("VMA_IT_JUMP_PORT")?.toIntOrNull()
    private val noForwardPort = System.getenv("VMA_IT_NOFWD_PORT")?.toIntOrNull()
    private val key = System.getenv("VMA_IT_SSH_KEY")?.let { File(it).readText() }
    private val user = System.getenv("VMA_IT_SSH_USER") ?: "root"

    private val knownHosts = object : KnownHosts {
        val map = mutableMapOf<String, Pair<String, String>>()
        override fun lookup(hostId: String) = map[hostId]
        override fun save(hostId: String, keyType: String, key: String) { map[hostId] = keyType to key }
    }
    private val prompts = mutableListOf<String>()
    private val opened = mutableListOf<SshSession>()

    private fun hop(name: String, port: Int) = SshHop(
        name, name, SshTarget("127.0.0.1", port, user, null, key, null, compression = false),
    ) { host, p, _, _, changed ->
        assertFalse(changed)
        prompts += "$name $host:$p"
        true
    }

    private fun chain(vararg hops: SshHop) = runBlocking { SshChain.connect(hops.toList(), knownHosts) }.also { opened += it }

    @After fun close() = opened.forEach { it.close() }

    @Test fun virshRunsOnTheTargetThroughTheJumpHost() = runBlocking<Unit> {
        assumeTrue(targetPort != null && jumpPort != null && key != null)
        val ssh = chain(hop("nas", jumpPort!!), hop("kvm", targetPort!!))
        // Each hop checks its own host key, stored under its own address.
        assertEquals(listOf("nas 127.0.0.1:$jumpPort", "kvm 127.0.0.1:$targetPort"), prompts)
        assertEquals(setOf("[127.0.0.1]:$jumpPort", "[127.0.0.1]:$targetPort"), knownHosts.map.keys)
        // What `virsh list --all` shows on the target.
        val list = VirshClient(ssh, "test:///default").virsh("list", "--all")
        assertTrue(list, list.lines().any { it.contains(" test ") && it.contains("running") })
    }

    @Test fun consoleTunnelRunsThroughTheTargetSession() = runBlocking<Unit> {
        assumeTrue(targetPort != null && jumpPort != null && key != null)
        // Stands in for QEMU's VNC server listening on the target's localhost.
        val server = ServerSocket(0)
        thread(isDaemon = true) { server.accept().use { s -> s.getOutputStream().write(s.getInputStream().read() + 1) } }
        val ssh = chain(hop("nas", jumpPort!!), hop("kvm", targetPort!!))
        val local = ssh.forwardLocal("127.0.0.1", server.localPort)
        Socket("127.0.0.1", local).use { s ->
            s.getOutputStream().write(41)
            assertEquals(42, s.getInputStream().read())
        }
        server.close()
    }

    @Test fun closingTheJumpSessionTakesTheTargetDown() = runBlocking<Unit> {
        assumeTrue(targetPort != null && jumpPort != null && key != null)
        val verifier = HostKeyVerifier { _, _, _, _, _ -> true }
        val jump = SshSession.connect(SshTarget("127.0.0.1", jumpPort!!, user, null, key, null, false), knownHosts, null, verifier)
        val target = SshSession.connect(SshTarget("127.0.0.1", targetPort!!, user, null, key, null, false), knownHosts, jump, verifier)
        opened += target
        assertEquals("ok\n", target.run("echo ok", null).stdout)
        jump.close()
        assertFalse(target.isConnected)
        try {
            target.run("echo ok", null); fail("the target session must be gone")
        } catch (_: Exception) {
        }
    }

    @Test fun jumpHostWithoutForwardingIsNamed() = runBlocking<Unit> {
        assumeTrue(targetPort != null && noForwardPort != null && key != null)
        try {
            chain(hop("nas", noForwardPort!!), hop("kvm", targetPort!!)); fail("expected an error")
        } catch (e: SshHopException) {
            assertEquals("nas", e.hop.name)
            assertEquals(
                "Jump host nas (127.0.0.1:$noForwardPort): Jump host doesn't allow TCP forwarding (AllowTcpForwarding in sshd_config)",
                ConnectionManager.friendly(e),
            )
        }
    }

    @Test fun unreachableTargetIsBlamedOnTheJumpHost() = runBlocking<Unit> {
        assumeTrue(jumpPort != null && key != null)
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            chain(hop("nas", jumpPort!!), hop("kvm", closedPort)); fail("expected an error")
        } catch (e: SshHopException) {
            assertEquals("nas", e.hop.name)
            assertTrue(ConnectionManager.friendly(e), ConnectionManager.friendly(e).contains("can't reach 127.0.0.1:$closedPort"))
        }
    }

    @Test fun wrongKeyOnTheTargetIsTheTargetsFault() = runBlocking<Unit> {
        assumeTrue(targetPort != null && jumpPort != null && key != null)
        val dir = kotlin.io.path.createTempDirectory().toFile()
        ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$dir/other").start().waitFor()
        val otherKey = File(dir, "other").readText().also { dir.deleteRecursively() }
        val bad = SshHop("kvm", "kvm", SshTarget("127.0.0.1", targetPort!!, user, null, otherKey, null, false)) { _, _, _, _, _ -> true }
        try {
            chain(hop("nas", jumpPort!!), bad); fail("expected an error")
        } catch (e: SshHopException) {
            assertEquals("kvm", e.hop.name)
            assertEquals("Host kvm (127.0.0.1:$targetPort): Authentication failed", ConnectionManager.friendly(e))
        }
    }
}
