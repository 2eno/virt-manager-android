package dev.virtmanager.android.vnc

import android.annotation.SuppressLint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * A small RFB (VNC) 3.3/3.7/3.8 client. Supports None and VNC authentication and the Raw,
 * CopyRect and DesktopSize encodings, which is everything QEMU needs. The connection is expected
 * to run over an SSH tunnel, so the transport itself is a plain localhost socket.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(width: Int, height: Int, name: String)
        fun onResize(width: Int, height: Int)
        /** Called after a FramebufferUpdate has been fully applied to [framebuffer]. */
        fun onFrame()
        fun onBell() {}
        fun onClipboard(text: String) {}
        fun onDisconnected(error: Throwable?)
    }

    class AuthException(message: String) : IOException(message)

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    /** ARGB_8888 pixels, row-major. Guarded by `synchronized(this)`. */
    @Volatile var framebuffer = IntArray(0); private set

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var running = false
    @Volatile private var closedByUser = false
    private val writeLock = Any()
    private var buttonMask = 0

    /**
     * Input (keys, pointer, clipboard) is written by this thread, never by the caller: callers
     * are the UI thread, where Android forbids network I/O (NetworkOnMainThreadException).
     * One thread keeps the messages in order.
     */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "rfb-writer").apply { isDaemon = true } }

    /** Test hook: runs on the writer thread before each queued message is written. */
    internal var beforeWrite: (() -> Unit)? = null

    /** Blocks for the lifetime of the connection; run on a background thread. */
    fun run() {
        var error: Throwable? = null
        try {
            val s = Socket().apply { tcpNoDelay = true }
            socket = s
            s.connect(InetSocketAddress(host, port), 10_000)
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            handshake()
            running = true
            requestUpdate(incremental = false)
            messageLoop()
        } catch (t: Throwable) {
            error = t
        } finally {
            running = false
            writer.shutdown()
            runCatching { socket?.close() }
            listener.onDisconnected(if (closedByUser) null else error)
        }
    }

    fun close() {
        closedByUser = true
        running = false
        writer.shutdown()
        runCatching { socket?.close() }
    }

    // --- Handshake -------------------------------------------------------------------------

    private fun handshake() {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val version = String(versionBytes, Charsets.US_ASCII)
        if (!version.startsWith("RFB ")) throw IOException("Not a VNC server")
        val minor = version.substring(8, 11).toIntOrNull() ?: 3
        val useMinor = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        output.write("RFB 003.00$useMinor\n".toByteArray(Charsets.US_ASCII))
        output.flush()

        val securityType: Int
        if (useMinor >= 7) {
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException(readReason())
            val types = (0 until count).map { input.readUnsignedByte() }
            securityType = when {
                types.contains(SEC_NONE) && password.isNullOrEmpty() -> SEC_NONE
                types.contains(SEC_VNC) -> SEC_VNC
                types.contains(SEC_NONE) -> SEC_NONE
                else -> throw IOException("Unsupported VNC security types: $types")
            }
            output.writeByte(securityType)
            output.flush()
        } else {
            securityType = input.readInt()
            if (securityType == 0) throw IOException(readReason())
        }

        if (securityType == SEC_VNC) {
            if (password.isNullOrEmpty()) throw AuthException("This display requires a VNC password")
            val challenge = ByteArray(16).also { input.readFully(it) }
            output.write(vncAuthResponse(password, challenge))
            output.flush()
        }
        if (securityType == SEC_VNC || useMinor >= 8) {
            val result = input.readInt()
            if (result != 0) {
                val reason = if (useMinor >= 8) readReason() else "Authentication failed"
                throw AuthException(reason)
            }
        }

        output.writeByte(1) // ClientInit: shared, so a desktop virt-manager can stay connected too
        output.flush()

        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; we override it below
        val nameLen = input.readInt()
        val name = ByteArray(nameLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
        resize(w, h)

        setPixelFormat()
        setEncodings(intArrayOf(ENC_COPY_RECT, ENC_RAW, ENC_DESKTOP_SIZE))
        listener.onConnected(w, h, name)
    }

    private fun readReason(): String {
        val len = input.readInt()
        return ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun resize(w: Int, h: Int) {
        synchronized(this) {
            width = w
            height = h
            framebuffer = IntArray(w * h) { 0xFF000000.toInt() }
        }
    }

    private fun setPixelFormat() = synchronized(writeLock) {
        output.writeByte(0)          // SetPixelFormat
        output.write(ByteArray(3))
        output.writeByte(32)         // bits-per-pixel
        output.writeByte(24)         // depth
        output.writeByte(0)          // big-endian: no
        output.writeByte(1)          // true colour
        output.writeShort(255); output.writeShort(255); output.writeShort(255)
        output.writeByte(16); output.writeByte(8); output.writeByte(0) // shifts -> 0x00RRGGBB
        output.write(ByteArray(3))
        output.flush()
    }

    private fun setEncodings(encodings: IntArray) = synchronized(writeLock) {
        output.writeByte(2)
        output.writeByte(0)
        output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
    }

    // --- Server messages -------------------------------------------------------------------

    private fun messageLoop() {
        while (running) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    readFramebufferUpdate()
                    listener.onFrame()
                    requestUpdate(incremental = true)
                }
                1 -> { // SetColourMapEntries (unused with true colour, but must be consumed)
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val n = input.readUnsignedShort()
                    input.skipBytes(n * 6)
                }
                2 -> listener.onBell()
                3 -> {
                    input.skipBytes(3)
                    val len = input.readInt()
                    val text = ByteArray(len).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
                    listener.onClipboard(text)
                }
                else -> throw IOException("Unknown server message $type")
            }
        }
    }

    private var rowBuffer = ByteArray(0)

    private fun readFramebufferUpdate() {
        input.readUnsignedByte()
        val rects = input.readUnsignedShort()
        repeat(rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPY_RECT -> {
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    copyRect(sx, sy, x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> {
                    resize(w, h)
                    listener.onResize(w, h)
                }
                else -> throw IOException("Server sent unsupported encoding $enc")
            }
        }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        if (rowBuffer.size < rowBytes) rowBuffer = ByteArray(rowBytes)
        for (row in 0 until h) {
            input.readFully(rowBuffer, 0, rowBytes)
            synchronized(this) {
                val dy = y + row
                if (dy < height) {
                    val fb = framebuffer
                    var off = dy * width + x
                    var i = 0
                    val limit = minOf(w, width - x)
                    for (col in 0 until limit) {
                        val b = rowBuffer[i].toInt() and 0xFF
                        val g = rowBuffer[i + 1].toInt() and 0xFF
                        val r = rowBuffer[i + 2].toInt() and 0xFF
                        fb[off++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        i += 4
                    }
                }
            }
        }
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) = synchronized(this) {
        val fb = framebuffer
        val fbW = width
        val tmp = IntArray(w * h)
        for (row in 0 until h) System.arraycopy(fb, (sy + row) * fbW + sx, tmp, row * w, w)
        for (row in 0 until h) System.arraycopy(tmp, row * w, fb, (dy + row) * fbW + dx, w)
    }

    /** Copies the current framebuffer into [dest] (resized if needed) and returns its size. */
    fun snapshot(dest: IntArray?): Triple<IntArray, Int, Int> = synchronized(this) {
        val buf = if (dest != null && dest.size == framebuffer.size) dest else IntArray(framebuffer.size)
        System.arraycopy(framebuffer, 0, buf, 0, framebuffer.size)
        Triple(buf, width, height)
    }

    // --- Client messages -------------------------------------------------------------------

    fun requestUpdate(incremental: Boolean) = send {
        writeByte(3)
        writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
    }

    fun sendKey(keysym: Int, down: Boolean) = send {
        writeByte(4)
        writeByte(if (down) 1 else 0)
        writeShort(0)
        writeInt(keysym)
    }

    fun tapKey(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    /** Sends a key combination, e.g. Ctrl+Alt+Delete: press all in order, release in reverse. */
    fun sendCombo(vararg keysyms: Int) {
        keysyms.forEach { sendKey(it, true) }
        keysyms.reversed().forEach { sendKey(it, false) }
    }

    fun sendPointer(x: Int, y: Int, mask: Int = buttonMask) {
        buttonMask = mask
        send {
            writeByte(5)
            writeByte(mask)
            writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
            writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
        }
    }

    fun click(x: Int, y: Int, button: Int) {
        sendPointer(x, y, 0)
        sendPointer(x, y, button)
        sendPointer(x, y, 0)
    }

    fun scroll(x: Int, y: Int, up: Boolean) = click(x, y, if (up) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN)

    fun sendClipboard(text: String) = send {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        writeByte(6)
        write(ByteArray(3))
        writeInt(bytes.size)
        write(bytes)
    }

    // Not inline: the stand-alone copy of an inline function holding a monitor contains Kotlin's
    // inliner markers outside the catch-all, and ART's verifier then rejects the whole class.
    /** Encodes a message on the caller's thread and queues it for [writer]. */
    private fun send(block: DataOutputStream.() -> Unit) {
        if (!running) return
        val bytes = ByteArrayOutputStream(32).also { DataOutputStream(it).block() }.toByteArray()
        try {
            writer.execute {
                beforeWrite?.invoke()
                try {
                    synchronized(writeLock) {
                        output.write(bytes)
                        output.flush()
                    }
                } catch (_: IOException) {
                    // The read loop will notice the broken connection and report it.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    companion object {
        const val SEC_NONE = 1
        const val SEC_VNC = 2
        const val ENC_RAW = 0
        const val ENC_COPY_RECT = 1
        const val ENC_DESKTOP_SIZE = -223

        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16

        /**
         * VNC authentication: DES-encrypt the challenge with the bit-reversed password bytes.
         * ECB/DES is what RFB mandates; the transport itself is protected by the SSH tunnel.
         */
        @SuppressLint("GetInstance")
        fun vncAuthResponse(password: String, challenge: ByteArray): ByteArray {
            val key = ByteArray(8)
            val pw = password.toByteArray(Charsets.ISO_8859_1)
            for (i in 0 until minOf(8, pw.size)) key[i] = reverseBits(pw[i])
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }

        private fun reverseBits(b: Byte): Byte {
            var v = b.toInt() and 0xFF
            var r = 0
            repeat(8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r.toByte()
        }
    }
}
