package dev.virtmanager.android.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.virtmanager.android.libvirt.CommandRunner
import dev.virtmanager.android.libvirt.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore
import kotlin.coroutines.coroutineContext

data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: String?,
    val keyPassphrase: String?,
    val compression: Boolean,
)

/** Asks the user whether to trust a host key the app has not seen before. Called on an IO thread. */
fun interface HostKeyVerifier {
    /** @param changed true if a *different* key was stored for this host before (possible MITM). */
    fun verify(host: String, port: Int, keyType: String, fingerprint: String, changed: Boolean): Boolean
}

/**
 * Stores trusted host keys. Implemented on top of the app's private storage; see [KnownHostsStore].
 */
interface KnownHosts {
    fun lookup(hostId: String): Pair<String, String>?   // keyType to base64 key
    fun save(hostId: String, keyType: String, key: String)
}

object SshCrypto {
    @Volatile private var configured = false

    /**
     * Android does not honour multi-release jars, so JSch's JDK 11+/15+ implementations of
     * X25519/Ed25519 are unavailable. Route those algorithms to the Bouncy Castle ones instead.
     */
    fun configure() {
        if (configured) return
        synchronized(this) {
            if (configured) return
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            configured = true
        }
    }

    data class GeneratedKey(val privateKeyPem: String, val publicKeyLine: String, val fingerprint: String)

    fun generateEd25519(comment: String): GeneratedKey {
        configure()
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8.name()).trim()
        val fp = kp.fingerPrint ?: ""
        kp.dispose()
        return GeneratedKey(priv, pub, fp)
    }

    fun publicKeyFor(privateKey: String, passphrase: String?, comment: String): String? = runCatching {
        configure()
        val kp = KeyPair.load(JSch(), privateKey.toByteArray(), null)
        if (kp.isEncrypted && !kp.decrypt(passphrase)) return null
        ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8.name()).trim()
    }.getOrNull()
}

class SshSession private constructor(
    private val target: SshTarget,
    private val session: Session,
) : CommandRunner {

    // libvirtd/sshd limit concurrent sessions (MaxSessions defaults to 10); keep headroom.
    private val channelPermits = Semaphore(6)

    val isConnected: Boolean get() = session.isConnected

    override suspend fun run(command: String, stdin: String?): ExecResult = withContext(Dispatchers.IO) {
        channelPermits.acquire()
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.setErrStream(stderr, true)
            val input = channel.inputStream
            val output = if (stdin != null) channel.outputStream else null
            channel.connect(15_000)
            output?.use { it.write(stdin!!.toByteArray()); it.flush() }
            pump(input, stdout)
            while (!channel.isClosed) {
                coroutineContext.ensureActive()
                Thread.sleep(5)
            }
            val code = channel.exitStatus
            channel.disconnect()
            ExecResult(code, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
        } finally {
            channelPermits.release()
        }
    }

    private fun pump(input: InputStream, out: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    /** An interactive channel with a PTY, used for the serial console. */
    class PtyChannel internal constructor(private val channel: ChannelExec) {
        val input: InputStream = channel.inputStream
        val output: OutputStream = channel.outputStream
        val isOpen: Boolean get() = !channel.isClosed
        fun resize(cols: Int, rows: Int) = channel.setPtySize(cols, rows, cols * 8, rows * 16)
        fun close() = channel.disconnect()
    }

    suspend fun openPty(command: String, cols: Int = 100, rows: Int = 30): PtyChannel = withContext(Dispatchers.IO) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setPty(true)
        channel.setPtyType("xterm", cols, rows, cols * 8, rows * 16)
        val pty = PtyChannel(channel)
        channel.connect(15_000)
        pty
    }

    /** Forwards a free local port to [remoteHost]:[remotePort] as seen from the SSH server. */
    suspend fun forwardLocal(remoteHost: String, remotePort: Int): Int = withContext(Dispatchers.IO) {
        session.setPortForwardingL("127.0.0.1", 0, remoteHost, remotePort)
    }

    /** Forwards a free local port to a UNIX socket on the server (VNC over socket). */
    suspend fun forwardLocalSocket(socketPath: String): Int = withContext(Dispatchers.IO) {
        session.setSocketForwardingL("127.0.0.1", 0, socketPath, null, 15_000)
    }

    fun removeForward(localPort: Int) {
        runCatching { session.delPortForwardingL("127.0.0.1", localPort) }
    }

    fun close() {
        runCatching { session.disconnect() }
    }

    companion object {
        suspend fun connect(
            target: SshTarget,
            knownHosts: KnownHosts,
            verifier: HostKeyVerifier,
        ): SshSession = withContext(Dispatchers.IO) {
            SshCrypto.configure()
            val jsch = JSch()
            if (!target.privateKey.isNullOrBlank()) {
                jsch.addIdentity(
                    "key",
                    target.privateKey.toByteArray(),
                    null,
                    target.keyPassphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                )
            }
            jsch.hostKeyRepository = TofuRepository(jsch, knownHosts, verifier, target.port)
            val session = jsch.getSession(target.username, target.host, target.port)
            target.password?.takeIf { it.isNotEmpty() }?.let { session.setPassword(it) }
            session.userInfo = object : UserInfo {
                override fun getPassphrase() = target.keyPassphrase
                override fun getPassword() = target.password
                override fun promptPassword(message: String?) = !target.password.isNullOrEmpty()
                override fun promptPassphrase(message: String?) = !target.keyPassphrase.isNullOrEmpty()
                override fun promptYesNo(message: String?) = false
                override fun showMessage(message: String?) = Unit
            }
            session.setConfig("StrictHostKeyChecking", "yes")
            if (target.compression) {
                session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
                session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
                session.setConfig("compression_level", "6")
            }
            session.setServerAliveInterval(20_000)
            session.setServerAliveCountMax(3)
            session.connect(20_000)
            SshSession(target, session)
        }
    }
}

/**
 * Trust-on-first-use host key repository. Unknown keys are shown to the user via [verifier];
 * a changed key is reported loudly and only accepted on explicit confirmation.
 */
private class TofuRepository(
    private val jsch: JSch,
    private val store: KnownHosts,
    private val verifier: HostKeyVerifier,
    private val port: Int,
) : HostKeyRepository {

    private fun hostId(host: String): String = if (host.startsWith("[")) host else "[$host]:$port"

    override fun check(host: String, key: ByteArray): Int {
        val hk = HostKey(host, key)
        val id = hostId(host)
        val encoded = hk.key
        val stored = store.lookup(id)
        if (stored != null && stored.second == encoded) return HostKeyRepository.OK
        val name = host.removePrefix("[").substringBefore("]")
        val accepted = verifier.verify(name, port, hk.type, hk.getFingerPrint(jsch), changed = stored != null)
        if (!accepted) return if (stored != null) HostKeyRepository.CHANGED else HostKeyRepository.NOT_INCLUDED
        store.save(id, hk.type, encoded)
        return HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = store.save(hostId(hostkey.host), hostkey.type, hostkey.key)
    override fun remove(host: String, type: String?) = Unit
    override fun remove(host: String, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "virt-manager-android"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String, type: String?): Array<HostKey> {
        val stored = store.lookup(hostId(host)) ?: return emptyArray()
        if (type != null && stored.first != type) return emptyArray()
        return runCatching {
            arrayOf(HostKey(host, java.util.Base64.getDecoder().decode(stored.second)))
        }.getOrDefault(emptyArray())
    }
}
