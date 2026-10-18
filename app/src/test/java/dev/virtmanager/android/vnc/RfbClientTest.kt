package dev.virtmanager.android.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class RfbClientTest {

    @Test fun vncAuthMatchesReferenceKeySchedule() {
        val challenge = ByteArray(16) { it.toByte() }
        val response = RfbClient.vncAuthResponse("secret", challenge)
        // Reference: each password byte has its bits mirrored before being used as the DES key.
        val key = "secret".toByteArray().copyOf(8).map { b ->
            Integer.reverse(b.toInt() and 0xFF).ushr(24).toByte()
        }.toByteArray()
        val cipher = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES")) }
        assertArrayEquals(challenge, cipher.doFinal(response))
    }

    /** Runs a minimal RFB 3.8 server and checks handshake, a Raw rect, CopyRect and input. */
    @Test fun endToEndAgainstFakeServer() {
        val server = ServerSocket(0)
        val clientEvents = mutableListOf<IntArray>()
        val serverDone = CountDownLatch(1)
        val serverError = arrayOfNulls<Throwable>(1)
        thread {
            try {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = DataOutputStream(s.getOutputStream())
                    out.write("RFB 003.008\n".toByteArray()); out.flush()
                    val ver = ByteArray(12).also { input.readFully(it) }
                    assertEquals("RFB 003.008\n", String(ver))
                    out.writeByte(1); out.writeByte(1); out.flush() // one type: None
                    assertEquals(1, input.readUnsignedByte())
                    out.writeInt(0); out.flush()                    // SecurityResult OK
                    assertEquals(1, input.readUnsignedByte())       // shared flag
                    out.writeShort(4); out.writeShort(2)            // 4x2 framebuffer
                    out.write(ByteArray(16))
                    val name = "test".toByteArray()
                    out.writeInt(name.size); out.write(name); out.flush()
                    // SetPixelFormat (20 bytes) + SetEncodings (4 + 3*4) + FramebufferUpdateRequest (10)
                    input.readFully(ByteArray(20))
                    assertEquals(2, input.readUnsignedByte()); input.readUnsignedByte()
                    val n = input.readUnsignedShort()
                    repeat(n) { input.readInt() }
                    assertEquals(3, input.readUnsignedByte()); input.readFully(ByteArray(9))

                    // FramebufferUpdate: one raw 1x1 red pixel at (0,0), then CopyRect it to (3,1)
                    out.writeByte(0); out.writeByte(0); out.writeShort(2)
                    out.writeShort(0); out.writeShort(0); out.writeShort(1); out.writeShort(1); out.writeInt(0)
                    out.write(byteArrayOf(0, 0, 0xFF.toByte(), 0)) // B G R X
                    out.writeShort(3); out.writeShort(1); out.writeShort(1); out.writeShort(1); out.writeInt(1)
                    out.writeShort(0); out.writeShort(0)
                    out.flush()

                    // The client's incremental update request (its read thread) and the test's input
                    // events (this test's thread) race, so accept them in any order.
                    var pointer: IntArray? = null
                    var key: IntArray? = null
                    var incrementalRequests = 0
                    while (pointer == null || key == null) {
                        when (val type = input.readUnsignedByte()) {
                            3 -> {
                                assertEquals(1, input.readUnsignedByte())
                                input.readFully(ByteArray(8))
                                incrementalRequests++
                            }
                            5 -> pointer = intArrayOf(input.readUnsignedByte(), input.readUnsignedShort(), input.readUnsignedShort())
                            4 -> {
                                val down = input.readUnsignedByte(); input.readUnsignedShort()
                                key = intArrayOf(down, input.readInt())
                            }
                            else -> error("unexpected client message $type")
                        }
                    }
                    clientEvents += pointer
                    clientEvents += key
                    if (incrementalRequests == 0) {
                        assertEquals(3, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                    }
                    serverDone.countDown()
                    Thread.sleep(200)
                }
            } catch (t: Throwable) {
                // Assertions on this thread would otherwise only surface as a timeout below.
                serverError[0] = t
                serverDone.countDown()
            }
        }

        val connected = CountDownLatch(1)
        val framed = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val errorHolder = arrayOfNulls<Throwable>(1)
        val client = RfbClient("127.0.0.1", server.localPort, null, object : RfbClient.Listener {
            override fun onConnected(width: Int, height: Int, name: String) {
                assertEquals(4, width); assertEquals(2, height); assertEquals("test", name)
                connected.countDown()
            }
            override fun onResize(width: Int, height: Int) = Unit
            override fun onFrame() = framed.countDown()
            override fun onDisconnected(error: Throwable?) {
                errorHolder[0] = error
                disconnected.countDown()
            }
        })
        thread { client.run() }
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertTrue(framed.await(5, TimeUnit.SECONDS))
        val (fb, w, _) = client.snapshot(null)
        assertEquals(0xFFFF0000.toInt(), fb[0])
        assertEquals(0xFFFF0000.toInt(), fb[1 * w + 3])
        assertEquals(0xFF000000.toInt(), fb[1])

        client.sendPointer(100, 1, RfbClient.BUTTON_LEFT) // x is clamped to width-1
        client.sendKey(Keysyms.RETURN, true)
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        serverError[0]?.let { throw AssertionError("fake VNC server failed", it) }
        assertArrayEquals(intArrayOf(1, 3, 1), clientEvents[0])
        assertArrayEquals(intArrayOf(1, Keysyms.RETURN), clientEvents[1])
        client.close()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertNull(errorHolder[0])
        server.close()
    }
}
