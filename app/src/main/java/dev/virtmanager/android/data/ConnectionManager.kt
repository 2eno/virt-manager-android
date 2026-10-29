package dev.virtmanager.android.data

import dev.virtmanager.android.libvirt.VirshClient
import dev.virtmanager.android.ssh.HostKeyVerifier
import dev.virtmanager.android.ssh.SshChain
import dev.virtmanager.android.ssh.SshHop
import dev.virtmanager.android.ssh.SshHopException
import dev.virtmanager.android.ssh.SshSession
import dev.virtmanager.android.ssh.SshTarget
import dev.virtmanager.android.ssh.TunnelOpenException
import kotlinx.coroutines.flow.first
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
    /** Which hop asks, e.g. "Jump host nas"; null without jump hosts. */
    val hop: String?,
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

    private fun verifier(hop: String?) = HostKeyVerifier { host, port, type, fp, changed ->
        val deferred = CompletableDeferred<Boolean>()
        _hostKeyPrompt.value = HostKeyPrompt(host, port, type, fp, changed, hop, deferred)
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

    /** The first profile on the way to [profile] (jump hosts first) that still needs a password. */
    fun passwordNeededFor(profile: ConnectionProfile, all: List<ConnectionProfile>): ConnectionProfile? =
        runCatching { JumpChain.resolve(profile) { id -> all.firstOrNull { it.id == id } } }
            .getOrDefault(listOf(profile)).firstOrNull(::needsPassword)

    /** Keeps a password the user typed for [id] until it fails or the app ends. */
    fun rememberPassword(id: String, password: String) {
        credentials[id] = password
    }

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
            val all = repository.connections.first()
            val chain = JumpChain.resolve(profile) { jumpId -> all.firstOrNull { it.id == jumpId } }
            val hops = chain.mapIndexed { i, p ->
                val label = when {
                    chain.size == 1 -> null
                    i < chain.lastIndex -> "Jump host ${p.name}"
                    else -> "Host ${p.name}"
                }
                SshHop(p.id, p.name, sshTarget(p), verifier(label))
            }
            val ssh = try {
                SshChain.connect(hops, repository)
            } catch (e: SshHopException) {
                credentials.remove(e.hop.id)
                throw e
            }
            val conn = LiveConnection(profile, ssh, VirshClient(ssh, profile.libvirtUri, profile.useSudo))
            // Validate that virsh works before declaring success.
            if (!profile.sshOnly) runCatching { conn.virsh.virsh("uri") }.onFailure {
                ssh.close()
                throw IllegalStateException("Connected via SSH but virsh failed: ${it.message}")
            }
            live[id] = conn
            setStatus(id, ConnectionStatus.Connected)
            conn
        } catch (t: Throwable) {
            // A failing hop has already dropped its own password; keep the others.
            if (t !is SshHopException) credentials.remove(id)
            setStatus(id, ConnectionStatus.Failed(friendly(t)))
            throw t
        }
    }

    private fun sshTarget(p: ConnectionProfile) = SshTarget(
        host = p.host,
        port = p.port,
        username = p.username,
        password = SecretCipher.decrypt(p.encryptedPassword) ?: credentials[p.id],
        privateKey = if (p.authMethod == AuthMethod.KEY) SecretCipher.decrypt(p.encryptedPrivateKey) else null,
        keyPassphrase = SecretCipher.decrypt(p.encryptedKeyPassphrase),
        compression = p.compression,
    )

    fun disconnect(id: String) {
        live.remove(id)?.ssh?.close()
        setStatus(id, ConnectionStatus.Disconnected)
    }

    fun disconnectAll() {
        live.keys.toList().forEach(::disconnect)
    }

    companion object {
        fun friendly(t: Throwable): String {
            if (t is SshHopException) {
                val target = "${t.hop.target.host}" + if (t.hop.target.port != 22) ":${t.hop.target.port}" else ""
                return "${if (t.isJump) "Jump host" else "Host"} ${t.hop.name} ($target): ${friendly(t.cause ?: t)}"
            }
            generateSequence(t) { it.cause }.filterIsInstance<TunnelOpenException>().firstOrNull()?.let { return it.message!! }
            val msg = t.message ?: t.javaClass.simpleName
            return when {
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) -> "Authentication failed"
                msg.contains("UnknownHostException", true) || t is java.net.UnknownHostException -> "Unknown host"
                msg.contains("timeout", true) || msg.contains("timed out", true) -> "Connection timed out"
                msg.contains("Connection refused", true) -> "Connection refused"
                msg.contains("EHOSTUNREACH", true) || msg.contains("No route to host", true) ->
                    "The host can't be reached (no route to host): it is down, hanging, rebooting or not on this network"
                msg.contains("reject HostKey", true) || msg.contains("HostKey has been changed", true) -> "Host key rejected"
                else -> msg
            }
        }
    }
}
