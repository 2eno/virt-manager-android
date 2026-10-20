package dev.virtmanager.android.vnc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What a QEMU guest's keyboard receives for typed characters: QEMU's VNC server maps keysyms
 * to US keys and drops the Shift a symbol needs, so "!" arrived as "1" in the Debian installer.
 * Starts its own QEMU with key tracing; opt-in: VMA_IT_QEMU=1 (qemu-system-x86_64 installed).
 */
class QemuKeyboardIntegrationTest {
    private var qemu: Process? = null
    private val log = File.createTempFile("qemu-keys", ".log")

    @After fun stop() {
        qemu?.destroy()
        log.delete()
    }

    @Test fun shiftedSymbolsArriveShifted() {
        assumeTrue(System.getenv("VMA_IT_QEMU") != null)
        val display = 23
        qemu = ProcessBuilder(
            "qemu-system-x86_64", "-machine", "pc", "-display", "none", "-vnc", "127.0.0.1:$display",
            "-trace", "vnc_key_event_map", "-D", log.path,
        ).redirectErrorStream(true).start()

        val connected = CountDownLatch(1)
        val client = RfbClient("127.0.0.1", 5900 + display, null, object : RfbClient.Listener {
            override fun onConnected(width: Int, height: Int, name: String) = connected.countDown()
            override fun onResize(width: Int, height: Int) = Unit
            override fun onFrame() = Unit
            override fun onDisconnected(error: Throwable?) = Unit
        })
        // Wait for QEMU's VNC port before connecting.
        val deadline = System.currentTimeMillis() + 5000
        while (runCatching { java.net.Socket("127.0.0.1", 5900 + display).close() }.isFailure) {
            check(System.currentTimeMillis() < deadline) { "QEMU didn't open its VNC port" }
            Thread.sleep(100)
        }
        thread(isDaemon = true) { client.run() }
        assertTrue(connected.await(10, TimeUnit.SECONDS))

        // What the VNC screen sends for text typed on the soft keyboard.
        for (ch in "!~|?A=") client.sendCombo(*Keysyms.combo(emptyList(), Keysyms.forChar(ch)))
        Thread.sleep(1000)
        client.close()

        // "down 1, sym 0x21 -> keycode 0x2 [1]": the keys pressed, in order.
        val presses = log.readLines().mapNotNull { Regex("down 1, sym \\S+ -> keycode \\S+ \\[(\\w+)]").find(it)?.groupValues?.get(1) }
        assertEquals(
            listOf("shift", "1", "shift", "grave_accent", "shift", "backslash", "shift", "slash", "shift", "a", "equal"),
            presses,
        )
    }
}
