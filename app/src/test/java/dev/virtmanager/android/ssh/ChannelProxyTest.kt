package dev.virtmanager.android.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** The ProxyJump tunnel, with a fake "direct-tcpip" channel. */
class ChannelProxyTest {

    private class FakeChannel(private val reason: Int? = null) : TcpChannel {
        val fromJump = PipedOutputStream()
        override val input = PipedInputStream(fromJump, 64 * 1024)
        override val output = ByteArrayOutputStream()
        var opened: Pair<String, Int>? = null
        var openTimeout = 0
        var closed = false
        override fun open(timeoutMs: Int): Int? { openTimeout = timeoutMs; return reason }
        override fun close() { closed = true; runCatching { fromJump.close() } }

        /** Like JSch, which flushes after each write into the channel's pipe. */
        fun send(bytes: ByteArray) { fromJump.write(bytes); fromJump.flush() }
    }

    private fun proxy(channel: FakeChannel) = ChannelProxy { host, port -> channel.opened = host to port; channel }

    @Test fun carriesTheTargetSessionBothWays() {
        val channel = FakeChannel()
        val proxy = proxy(channel)
        proxy.connect(null, "10.0.20.5", 22, 20_000)
        assertEquals("10.0.20.5" to 22, channel.opened)
        assertEquals(20_000, channel.openTimeout)
        assertNull("no socket, no local port", proxy.socket)

        proxy.outputStream!!.write("SSH-2.0-client\r\n".toByteArray())
        assertEquals("SSH-2.0-client\r\n", channel.output.toString())
        channel.send("SSH-2.0-OpenSSH_9.9\r\n".toByteArray())
        val buf = ByteArray(64)
        val n = proxy.inputStream!!.read(buf)
        assertEquals("SSH-2.0-OpenSSH_9.9\r\n", String(buf, 0, n))

        proxy.close()
        assertTrue(channel.closed)
    }

    @Test fun forwardingForbiddenOnTheJumpHostIsExplained() {
        val channel = FakeChannel(reason = 1)
        try {
            proxy(channel).connect(null, "10.0.20.5", 22, 20_000)
            fail("expected an error")
        } catch (e: TunnelOpenException) {
            assertEquals("Jump host doesn't allow TCP forwarding (AllowTcpForwarding in sshd_config)", e.message)
        }
        assertTrue(channel.closed)
    }

    @Test fun otherOpenFailures() {
        assertEquals("Jump host can't reach 10.0.20.5:22 (connect failed)", TunnelOpenException("10.0.20.5", 22, 2).message)
        assertEquals("Jump host didn't open the tunnel to 10.0.20.5:22 in time", TunnelOpenException("10.0.20.5", 22, -1).message)
        assertEquals("Lost the connection to the jump host", TunnelOpenException("10.0.20.5", 22, -2).message)
    }

    @Test fun silenceTimesOutLikeASocketSoJSchCanSendKeepalives() {
        val channel = FakeChannel()
        val proxy = proxy(channel)
        proxy.connect(null, "pc", 22, 20_000)
        proxy.readTimeoutMs = 50
        val start = System.nanoTime()
        try {
            proxy.inputStream!!.read()
            fail("expected a timeout")
        } catch (e: InterruptedIOException) {
            assertTrue((System.nanoTime() - start) / 1_000_000 >= 40)
        }
        // The stream still works afterwards, as a socket does after SO_TIMEOUT.
        channel.send(byteArrayOf(1, 2, 3))
        val buf = ByteArray(3)
        var got = 0
        while (got < 3) got += proxy.inputStream!!.read(buf, got, 3 - got)
        assertArrayEquals(byteArrayOf(1, 2, 3), buf)
    }

    @Test fun theEndOfTheChannelEndsTheStream() {
        val channel = FakeChannel()
        val proxy = proxy(channel)
        proxy.connect(null, "pc", 22, 20_000)
        channel.send(byteArrayOf(7))
        channel.fromJump.close()
        assertEquals(7, proxy.inputStream!!.read())
        assertEquals(-1, proxy.inputStream!!.read())
        assertEquals(-1, proxy.inputStream!!.read())
    }
}
