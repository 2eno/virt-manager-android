package dev.virtmanager.android.data

import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.ssh.HostKeyVerifier
import dev.virtmanager.android.ssh.SshSession
import dev.virtmanager.android.ssh.SshTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
}

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean,
    internal val answer: CompletableDeferred<Boolean>,
) {
    fun respond(trust: Boolean) { answer.complete(trust) }
}

class LiveConnection(
    val profile: ConnectionProfile,
    val ssh: SshSession,
    val virsh: VirshClient,
)

/** Owns the SSH sessions for all open connections (the "connection list" of virt-manager). */
class ConnectionManager(private val repository: ConnectionRepository) {

    private val live = mutableMapOf<String, LiveConnection>()
    private val credentials = mutableMapOf<String, String>()
    private val mutex = Mutex()

    private val _status = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val status: StateFlow<Map<String, ConnectionStatus>> = _status.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    private val verifier = HostKeyVerifier { host, port, type, fp, changed ->
        val deferred = CompletableDeferred<Boolean>()
        _hostKeyPrompt.value = HostKeyPrompt(host, port, type, fp, changed, deferred)
        try {
            runBlocking { deferred.await() }
        } finally {
            _hostKeyPrompt.value = null
        }
    }

    private fun setStatus(id: String, s: ConnectionStatus) = _status.update { it + (id to s) }

    fun needsPassword(profile: ConnectionProfile): Boolean =
        profile.authMethod == AuthMethod.PASSWORD &&
            profile.encryptedPassword == null && credentials[profile.id] == null

    fun get(id: String): LiveConnection? = live[id]?.takeIf { it.ssh.isConnected }

    /**
     * Returns an open connection, (re)connecting if necessary. [password] is a one-off password
     * entered by the user for profiles that don't store one.
     */
    suspend fun connect(id: String, password: String? = null): LiveConnection = mutex.withLock {
        get(id)?.let { return it }
        live.remove(id)?.ssh?.close()
        val profile = repository.get(id) ?: error("Unknown connection")
        if (password != null) credentials[id] = password
        setStatus(id, ConnectionStatus.Connecting)
        try {
            val target = SshTarget(
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = SecretCipher.decrypt(profile.encryptedPassword) ?: credentials[id],
                privateKey = if (profile.authMethod == AuthMethod.KEY) SecretCipher.decrypt(profile.encryptedPrivateKey) else null,
                keyPassphrase = SecretCipher.decrypt(profile.encryptedKeyPassphrase),
                compression = profile.compression,
            )
            val ssh = SshSession.connect(target, repository, verifier)
            val conn = LiveConnection(profile, ssh, VirshClient(ssh, profile.libvirtUri, profile.useSudo))
            // Validate that virsh works before declaring success.
            runCatching { conn.virsh.virsh("uri") }.onFailure {
                ssh.close()
                throw IllegalStateException("Connected via SSH but virsh failed: ${it.message}")
            }
            live[id] = conn
            setStatus(id, ConnectionStatus.Connected)
            conn
        } catch (t: Throwable) {
            credentials.remove(id)
            setStatus(id, ConnectionStatus.Failed(friendly(t)))
            throw t
        }
    }

    fun disconnect(id: String) {
        live.remove(id)?.ssh?.close()
        setStatus(id, ConnectionStatus.Disconnected)
    }

    fun disconnectAll() {
        live.keys.toList().forEach(::disconnect)
    }

    companion object {
        fun friendly(t: Throwable): String {
            val msg = t.message ?: t.javaClass.simpleName
            return when {
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) -> "Authentication failed"
                msg.contains("UnknownHostException", true) || t is java.net.UnknownHostException -> "Unknown host"
                msg.contains("timeout", true) -> "Connection timed out"
                msg.contains("Connection refused", true) -> "Connection refused"
                msg.contains("EHOSTUNREACH", true) || msg.contains("No route to host", true) ->
                    "The host can't be reached (no route to host): it is down, hanging, rebooting or not on this network"
                msg.contains("reject HostKey", true) || msg.contains("HostKey has been changed", true) -> "Host key rejected"
                else -> msg
            }
        }
    }
}
