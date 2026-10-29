package dev.virtmanager.android.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod { PASSWORD, KEY }

/** A saved hypervisor connection, equivalent to virt-manager's "Add Connection" dialog. */
@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** libvirt URI as seen from the SSH host, e.g. qemu:///system or qemu:///session. */
    val libvirtUri: String = "qemu:///system",
    val useSudo: Boolean = false,
    val compression: Boolean = true,
    val autoConnect: Boolean = false,
    /** Saved profile to connect through first (OpenSSH ProxyJump); it may have a jump host itself. */
    val jumpHostId: String? = null,
    /** A plain SSH host without libvirt, e.g. one only used as a jump host. */
    val sshOnly: Boolean = false,
    // Secrets are encrypted with an Android Keystore key before being persisted.
    val encryptedPassword: String? = null,
    val encryptedPrivateKey: String? = null,
    val encryptedKeyPassphrase: String? = null,
) {
    val displayUri: String
        get() {
            val driver = libvirtUri.substringBefore(":///", "qemu")
            val path = libvirtUri.substringAfter(":///", "system")
            val portPart = if (port != 22) ":$port" else ""
            if (sshOnly) return "ssh://$username@$host$portPart"
            return "$driver+ssh://$username@$host$portPart/$path"
        }
}

/** Resolves the jump hosts of a profile. */
object JumpChain {
    private const val MAX_HOPS = 8

    /** The profiles to connect through, first jump host first and [target] last. */
    fun resolve(target: ConnectionProfile, lookup: (String) -> ConnectionProfile?): List<ConnectionProfile> {
        val chain = mutableListOf(target)
        var current = target
        while (true) {
            val jumpId = current.jumpHostId ?: return chain.reversed()
            val jump = lookup(jumpId) ?: error("The jump host of ${current.name} no longer exists")
            require(chain.none { it.id == jump.id }) { "The jump hosts of ${target.name} form a loop" }
            require(chain.size < MAX_HOPS) { "Too many jump hosts for ${target.name}" }
            chain += jump
            current = jump
        }
    }

    /** Whether [candidate] can be the jump host of the profile [selfId] without forming a loop. */
    fun canJumpVia(selfId: String, candidate: ConnectionProfile, lookup: (String) -> ConnectionProfile?): Boolean =
        candidate.id != selfId && runCatching { resolve(candidate, lookup) }.getOrNull()?.none { it.id == selfId } == true
}
