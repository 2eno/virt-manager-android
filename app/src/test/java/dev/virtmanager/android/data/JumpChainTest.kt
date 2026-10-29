package dev.virtmanager.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JumpChainTest {
    private val nas = ConnectionProfile(id = "nas", name = "nas", host = "192.168.178.12", sshOnly = true)
    private val kvm = ConnectionProfile(id = "kvm", name = "kvm", host = "10.0.20.5", jumpHostId = "nas")
    private val all = listOf(nas, kvm)
    private val lookup = { id: String -> all.firstOrNull { it.id == id } }

    @Test fun jumpHostsComeFirst() {
        assertEquals(listOf("nas", "kvm"), JumpChain.resolve(kvm, lookup).map { it.id })
        assertEquals(listOf("nas"), JumpChain.resolve(nas, lookup).map { it.id })
    }

    @Test fun missingJumpHost() {
        try {
            JumpChain.resolve(kvm) { null }; fail()
        } catch (e: IllegalStateException) {
            assertEquals("The jump host of kvm no longer exists", e.message)
        }
    }

    @Test fun loopsAreRefusedAndNotOffered() {
        val loopy = nas.copy(jumpHostId = "kvm")
        try {
            JumpChain.resolve(kvm) { id -> if (id == "nas") loopy else kvm }; fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("loop"))
        }
        // nas can't use kvm as jump host: kvm already goes through nas.
        assertFalse(JumpChain.canJumpVia("nas", kvm, lookup))
        assertFalse(JumpChain.canJumpVia("nas", nas, lookup))
        assertTrue(JumpChain.canJumpVia("kvm", nas, lookup))
        assertTrue(JumpChain.canJumpVia("", kvm, lookup))
    }

    @Test fun oldProfilesStillLoad() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val p = json.decodeFromString(ConnectionProfile.serializer(), """{"id":"a","name":"a","host":"h"}""")
        assertEquals(null, p.jumpHostId)
        assertFalse(p.sshOnly)
        assertEquals("ssh://root@192.168.178.12", nas.displayUri)
    }
}
