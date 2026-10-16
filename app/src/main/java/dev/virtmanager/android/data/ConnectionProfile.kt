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
            return "$driver+ssh://$username@$host$portPart/$path"
        }
}
