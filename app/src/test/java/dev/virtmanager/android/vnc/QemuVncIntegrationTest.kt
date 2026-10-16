package dev.virtmanager.android.vnc

import dev.virtmanager.android.ssh.KnownHosts
import dev.virtmanager.android.ssh.SshSession
import dev.virtmanager.android.ssh.SshTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Connects [RfbClient] to a real QEMU VNC server through an SSH tunnel, the same path the app
 * uses. Opt-in via VMA_IT_SSH_PORT / VMA_IT_SSH_KEY plus VMA_IT_VNC_PORT (no password) and
 * optionally VMA_IT_VNC_PW_PORT + VMA_IT_VNC_PW (password protected display).
 */
class QemuVncIntegrationTest {
    private val sshPort = System.getenv("VMA_IT_SSH_PORT")?.toIntOrNull()
    private val key = System.getenv("VMA_IT_SSH_KEY")

    private fun tunnel(remotePort: Int): Pair<SshSession, Int> = runBlocking {
        val known = object : KnownHosts {
            override fun lookup(hostId: String): Pair<String, String>? = null
            override fun save(hostId: String, keyType: String, key: String) = Unit
        }
        val ssh = SshSession.connect(
            SshTarget("127.0.0.1", sshPort!!, "root", null, File(key!!).readText(), null, compression = true),
            known,
        ) { _, _, _, _, _ -> true }
        ssh to ssh.forwardLocal("127.0.0.1", remotePort)
    }

    private class Recorder : RfbClient.Listener {
        val connected = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val done = CountDownLatch(1)
        var size = 0 to 0
        var error: Throwable? = null
        override fun onConnected(width: Int, height: Int, name: String) { size = width to height; connected.countDown() }
        override fun onResize(width: Int, height: Int) { size = width to height }
        override fun onFrame() = frame.countDown()
        override fun onDisconnected(error: Throwable?) { this.error = error; connected.countDown(); done.countDown() }
    }

    @Test fun rendersQemuFramebuffer() {
        val vncPort = System.getenv("VMA_IT_VNC_PORT")?.toIntOrNull()
        assumeTrue(sshPort != null && key != null && vncPort != null)
        val (ssh, local) = tunnel(vncPort!!)
        val rec = Recorder()
        val client = RfbClient("127.0.0.1", local, null, rec)
        thread { client.run() }
        assertTrue(rec.connected.await(10, TimeUnit.SECONDS))
        assertEquals(null, rec.error)
        assertTrue(rec.frame.await(10, TimeUnit.SECONDS))
        // Give SeaBIOS a moment to draw, then request a full refresh and check for non-black pixels.
        Thread.sleep(1500)
        client.requestUpdate(incremental = false)
        Thread.sleep(500)
        val (fb, w, h) = client.snapshot(null)
        assertTrue("framebuffer ${w}x$h", w >= 640 && h >= 400)
        assertTrue("expected text on screen", fb.any { it != 0xFF000000.toInt() })
        client.tapKey(Keysyms.RETURN)
        client.click(10, 10, RfbClient.BUTTON_LEFT)
        client.close()
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        ssh.close()
    }

    @Test fun passwordProtectedDisplay() {
        val port = System.getenv("VMA_IT_VNC_PW_PORT")?.toIntOrNull()
        val pw = System.getenv("VMA_IT_VNC_PW")
        assumeTrue(sshPort != null && key != null && port != null && pw != null)
        val (ssh, local) = tunnel(port!!)

        val bad = Recorder()
        thread { RfbClient("127.0.0.1", local, "wrong", bad).run() }
        assertTrue(bad.done.await(10, TimeUnit.SECONDS))
        assertTrue(bad.error is RfbClient.AuthException)

        val good = Recorder()
        val client = RfbClient("127.0.0.1", local, pw, good)
        thread { client.run() }
        assertTrue(good.frame.await(10, TimeUnit.SECONDS))
        assertEquals(null, good.error)
        client.close()
        ssh.close()
    }
}
