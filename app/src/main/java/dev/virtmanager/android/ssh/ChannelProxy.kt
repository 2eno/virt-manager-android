package dev.virtmanager.android.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A "direct-tcpip" channel on an SSH session, reduced to what [ChannelProxy] needs. */
interface TcpChannel {
    val input: InputStream
    val output: OutputStream

    /**
     * Opens the channel: null on success, else the SSH open-failure reason (1 administratively
     * prohibited, 2 connect failed, …), -1 if there was no answer in time, -2 if the session closed.
     */
    fun open(timeoutMs: Int): Int?
    fun close()
}

internal class JschTcpChannel(private val channel: ChannelDirectTCPIP) : TcpChannel {
    // JSch wants the streams before connect().
    override val input: InputStream = channel.inputStream
    override val output: OutputStream = channel.outputStream

    override fun open(timeoutMs: Int): Int? = try {
        channel.connect(timeoutMs)
        null
    } catch (e: JSchException) {
        // JSch drops the reason of SSH_MSG_CHANNEL_OPEN_FAILURE into the exit status.
        if (!channel.session.isConnected) -2 else channel.exitStatus.takeIf { it > 0 } ?: -1
    }

    override fun close() = channel.disconnect()
}

/** The jump host refused or could not open the tunnel to the next hop. */
class TunnelOpenException(val host: String, val port: Int, val reason: Int) : IOException(
    when (reason) {
        1 -> "Jump host doesn't allow TCP forwarding (AllowTcpForwarding in sshd_config)"
        2 -> "Jump host can't reach $host:$port (connect failed)"
        -1 -> "Jump host didn't open the tunnel to $host:$port in time"
        -2 -> "Lost the connection to the jump host"
        else -> "Jump host refused the tunnel to $host:$port (reason $reason)"
    },
)

/**
 * Lets a JSch session run over a "direct-tcpip" channel of another session, like OpenSSH's
 * ProxyJump: no local port is opened.
 *
 * Without a socket JSch has no read timeout, which it relies on both to give up on a target that
 * doesn't answer and to send keepalives. [readTimeoutMs] restores that: the connect timeout while
 * connecting, then the keepalive interval.
 */
class ChannelProxy(private val openChannel: (host: String, port: Int) -> TcpChannel) : Proxy {
    @Volatile var readTimeoutMs = 0
    private var channel: TcpChannel? = null
    private var input: InputStream? = null

    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        val c = openChannel(host, port)
        channel = c
        val reason = c.open(if (timeout > 0) timeout else DEFAULT_TIMEOUT)
        if (reason != null) {
            c.close()
            throw TunnelOpenException(host, port, reason)
        }
        if (readTimeoutMs == 0) readTimeoutMs = timeout
        input = TimedInputStream(c.input) { readTimeoutMs }
    }

    override fun getInputStream(): InputStream? = input
    override fun getOutputStream(): OutputStream? = channel?.output
    override fun getSocket(): Socket? = null

    override fun close() {
        runCatching { input?.close() }
        runCatching { channel?.close() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT = 20_000
    }
}

/**
 * Reads [source] on its own thread so a read can time out with an [java.io.InterruptedIOException],
 * just like a socket with SO_TIMEOUT. Bounded, so a slow reader still slows down the channel.
 */
internal class TimedInputStream(private val source: InputStream, private val timeoutMs: () -> Int) : InputStream() {
    private val chunks = ArrayBlockingQueue<ByteArray>(64)
    private var current: ByteArray? = null
    private var pos = 0
    @Volatile private var closed = false

    private val pump = thread(isDaemon = true, name = "ssh-jump-reader") {
        val buf = ByteArray(32 * 1024)
        try {
            while (!closed) {
                val n = source.read(buf)
                if (n < 0) break
                if (n > 0) chunks.put(buf.copyOf(n))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { chunks.put(EOF) }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var chunk = current
        if (chunk == null || pos >= chunk.size) {
            if (closed) return -1
            val timeout = timeoutMs()
            chunk = (if (timeout > 0) chunks.poll(timeout.toLong(), TimeUnit.MILLISECONDS) else chunks.take())
                ?: throw SocketTimeoutException("Read timed out")
            if (chunk === EOF) {
                chunks.offer(EOF) // later reads see the end too
                return -1
            }
            current = chunk
            pos = 0
        }
        val n = minOf(len, chunk.size - pos)
        System.arraycopy(chunk, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = current?.let { it.size - pos } ?: 0

    override fun close() {
        closed = true
        runCatching { source.close() }
        pump.interrupt()
        chunks.clear()
        chunks.offer(EOF)
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
