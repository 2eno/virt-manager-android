package dev.virtmanager.android.ui.console

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.ssh.SshSession
import dev.virtmanager.android.vnc.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

data class VncUiState(
    val status: String = "Connecting…",
    val connected: Boolean = false,
    val needsPassword: Boolean = false,
    val error: String? = null,
    val desktopName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frame: Bitmap? = null,
    val frameId: Long = 0,
)

class VncViewModel(
    private val app: VirtManagerApp,
    private val connectionId: String,
    val vmName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(VncUiState())
    val state = _state.asStateFlow()

    var client: RfbClient? = null
        private set
    private var ssh: SshSession? = null
    private var localPort: Int? = null
    private val frames = Channel<Unit>(Channel.CONFLATED)
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var pixels: IntArray? = null
    private var back = 0

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (unit in frames) render()
        }
        start(null)
    }

    fun start(password: String?) = viewModelScope.launch {
        _state.update { it.copy(status = "Opening tunnel…", error = null, needsPassword = false) }
        try {
            val conn = app.connections.connect(connectionId)
            val def = conn.virsh.definition(vmName)
            val vnc = def.vnc ?: error("This VM has no VNC display")
            if (vnc.hasPassword && password == null) {
                _state.update { it.copy(needsPassword = true, status = "Password required") }
                return@launch
            }
            // Forward afresh on every (re)connect: after a restart of the VM its automatic port
            // may have changed, and after an SSH reconnect the old forward is gone.
            localPort?.let { p -> ssh?.removeForward(p) }
            localPort = null
            ssh = conn.ssh
            val port = if (vnc.socket != null) {
                conn.ssh.forwardLocalSocket(vnc.socket)
            } else {
                val remote = vnc.port ?: conn.virsh.vncDisplay(vmName)?.let { d ->
                    d.substringAfterLast(':').toIntOrNull()?.let { 5900 + it }
                } ?: error("The VM's VNC port is unknown (is it running?)")
                val listen = vnc.listen?.takeUnless { it == "0.0.0.0" || it == "::" || it.isBlank() } ?: "127.0.0.1"
                conn.ssh.forwardLocal(listen, remote)
            }
            localPort = port
            _state.update { it.copy(status = "Connecting to display…") }
            val c = RfbClient("127.0.0.1", port, password, listener(++generation))
            client = c
            thread(name = "vnc-$vmName", isDaemon = true) { c.run() }
        } catch (t: Throwable) {
            _state.update { it.copy(status = "Failed", error = ConnectionManager.friendly(t)) }
        }
    }

    /** Bumped per client, so a closed client's late events can't overwrite the new one's state. */
    @Volatile private var generation = 0

    private fun listener(gen: Int) = object : RfbClient.Listener {
        private val current get() = gen == generation

        override fun onConnected(width: Int, height: Int, name: String) {
            if (!current) return
            _state.update { it.copy(connected = true, status = "Connected", desktopName = name, width = width, height = height) }
        }

        override fun onResize(width: Int, height: Int) {
            if (current) _state.update { it.copy(width = width, height = height) }
        }

        override fun onFrame() {
            if (current) frames.trySend(Unit)
        }

        override fun onDisconnected(error: Throwable?) {
            if (!current) return
            val auth = error is RfbClient.AuthException
            _state.update {
                it.copy(
                    connected = false,
                    status = "Disconnected",
                    needsPassword = auth,
                    error = error?.let { e -> if (auth) e.message else ConnectionManager.friendly(e) },
                )
            }
        }
    }

    /** Double-buffered: write pixels into the back bitmap off the main thread, then publish it. */
    private fun render() {
        val c = client ?: return
        val (buf, w, h) = c.snapshot(pixels)
        pixels = buf
        if (w <= 0 || h <= 0) return
        var bmp = bitmaps[back]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmaps[back] = bmp
        }
        bmp!!.setPixels(buf, 0, w, 0, 0, w, h)
        _state.update { it.copy(frame = bmp, frameId = it.frameId + 1) }
        back = 1 - back
    }

    fun reconnect(password: String?) {
        generation++ // the old client's "disconnected" must not show over the new attempt
        client?.close()
        start(password)
    }

    override fun onCleared() {
        client?.close()
        frames.close()
        localPort?.let { ssh?.removeForward(it) }
    }
}
