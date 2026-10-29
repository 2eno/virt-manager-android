package dev.virtmanager.android.ssh

import com.jcraft.jsch.JSchException
import dev.virtmanager.android.data.ConnectionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Connecting hop by hop and blaming errors on the right hop. */
class SshChainTest {
    private class Fake(val name: String, val via: Fake?) { var closed = false }

    private val noVerifier = HostKeyVerifier { _, _, _, _, _ -> false }
    private fun hop(name: String, host: String) = SshHop(name, name, SshTarget(host, 22, "zeno", null, null, null, false), noVerifier)
    private val nas = hop("nas", "192.168.178.12")
    private val kvm = hop("kvm", "10.0.20.5")

    private fun connect(hops: List<SshHop>, fail: Map<String, Throwable> = emptyMap()): Fake = runBlocking {
        SshChain.connect<Fake>(hops, { f -> var c: Fake? = f; while (c != null) { c.closed = true; c = c.via } }) { hop, via ->
            fail[hop.name]?.let { throw it }
            Fake(hop.name, via)
        }
    }

    @Test fun eachHopRunsOverThePreviousOne() {
        val s = connect(listOf(nas, kvm))
        assertEquals("kvm", s.name)
        assertEquals("nas", s.via!!.name)
    }

    @Test fun withoutJumpHostsErrorsAreUnchanged() {
        val e = JSchException("Auth fail for methods 'publickey'")
        try {
            connect(listOf(kvm), mapOf("kvm" to e)); fail()
        } catch (t: Throwable) {
            assertSame(e, t)
        }
    }

    @Test fun jumpHostAuthFailureNamesTheJumpHost() {
        try {
            connect(listOf(nas, kvm), mapOf("nas" to JSchException("Auth fail for methods 'publickey,password'"))); fail()
        } catch (e: SshHopException) {
            assertSame(nas, e.hop)
            assertTrue(e.isJump)
            assertEquals("Jump host nas (192.168.178.12): Authentication failed", ConnectionManager.friendly(e))
        }
    }

    @Test fun refusedTunnelIsTheJumpHostsFault() {
        // JSch wraps what the proxy throws into its own exception.
        val wrapped = JSchException("java.io.IOException: x", TunnelOpenException("10.0.20.5", 22, 1))
        try {
            connect(listOf(nas, kvm), mapOf("kvm" to wrapped)); fail()
        } catch (e: SshHopException) {
            assertSame(nas, e.hop)
            assertEquals(
                "Jump host nas (192.168.178.12): Jump host doesn't allow TCP forwarding (AllowTcpForwarding in sshd_config)",
                ConnectionManager.friendly(e),
            )
        }
    }

    @Test fun targetErrorsNameTheTargetAndCloseTheJumpSession() {
        var opened: Fake? = null
        try {
            runBlocking {
                SshChain.connect<Fake>(listOf(nas, kvm), { it.closed = true }) { hop, via ->
                    if (hop === kvm) throw JSchException("timeout: socket is not established", java.net.SocketTimeoutException("Read timed out"))
                    Fake(hop.name, via).also { opened = it }
                }
            }
            fail()
        } catch (e: SshHopException) {
            assertSame(kvm, e.hop)
            assertEquals(false, e.isJump)
            assertEquals("Host kvm (10.0.20.5): Connection timed out", ConnectionManager.friendly(e))
        }
        assertTrue("the jump session must not leak", opened!!.closed)
    }

    @Test fun middleHopOfALongerChainIsAJumpHost() {
        val dmz = hop("dmz", "172.16.0.1")
        try {
            connect(listOf(nas, dmz, kvm), mapOf("dmz" to JSchException("Auth fail"))); fail()
        } catch (e: SshHopException) {
            assertSame(dmz, e.hop)
            assertTrue(e.isJump)
        }
    }
}
