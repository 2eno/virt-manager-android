package dev.virtmanager.android.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SerialUiState(
    val lines: List<String> = emptyList(),
    val connected: Boolean = false,
    val error: String? = null,
)

class SerialViewModel(
    private val app: VirtManagerApp,
    private val connectionId: String,
    val vmName: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SerialUiState())
    val state = _state.asStateFlow()
    private var pty: SshSession.PtyChannel? = null
    private val terminal = LineTerminal()

    init {
        connect()
    }

    fun connect() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val conn = app.connections.connect(connectionId)
            val channel = conn.ssh.openPty(conn.virsh.consoleCommand(vmName))
            pty = channel
            _state.update { it.copy(connected = true, error = null) }
            // A Reader decodes UTF-8 sequences that straddle read boundaries correctly.
            val reader = channel.input.reader(Charsets.UTF_8)
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val text = String(buf, 0, n)
                synchronized(terminal) { terminal.feed(text) }
                _state.update { it.copy(lines = synchronized(terminal) { terminal.snapshot() }) }
            }
            _state.update { it.copy(connected = false) }
        } catch (t: Throwable) {
            _state.update { it.copy(connected = false, error = ConnectionManager.friendly(t)) }
        }
    }

    fun send(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun write(bytes: ByteArray) {
        val p = pty ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { p.output.write(bytes); p.output.flush() }
        }
    }

    fun clear() {
        synchronized(terminal) { terminal.clear() }
        _state.update { it.copy(lines = emptyList()) }
    }

    override fun onCleared() {
        pty?.close()
    }
}

/**
 * A deliberately tiny terminal model: tracks lines and a cursor column, understands CR, LF,
 * backspace and tabs, and drops ANSI escape sequences. Enough for boot logs, getty logins and
 * shells on a serial line without pulling in a full VT100 emulator.
 */
class LineTerminal(private val maxLines: Int = 2000) {
    private val lines = ArrayDeque<StringBuilder>().apply { add(StringBuilder()) }
    private var col = 0
    private var escState = 0 // 0 none, 1 after ESC, 2 CSI, 3 OSC

    fun feed(text: String) {
        for (ch in text) {
            when (escState) {
                1 -> escState = when (ch) {
                    '[' -> 2
                    ']' -> 3
                    else -> 0
                }
                2 -> if (ch in '@'..'~') {
                    escState = 0
                    if (ch == 'K') { // erase to end of line
                        val line = lines.last()
                        if (col < line.length) line.setLength(col)
                    } else if (ch == 'J') {
                        clear()
                    }
                }
                3 -> if (ch == '\u0007') escState = 0 else if (ch == '\u001b') escState = 1
                else -> handle(ch)
            }
        }
    }

    private fun handle(ch: Char) {
        when (ch) {
            '\u001b' -> escState = 1
            '\n' -> newLine()
            '\r' -> col = 0
            '\b' -> if (col > 0) col--
            '\t' -> repeat(8 - col % 8) { put(' ') }
            '\u0007' -> Unit
            else -> if (ch >= ' ') put(ch)
        }
    }

    private fun put(ch: Char) {
        val line = lines.last()
        while (line.length < col) line.append(' ')
        if (col < line.length) line.setCharAt(col, ch) else line.append(ch)
        col++
    }

    private fun newLine() {
        lines.addLast(StringBuilder())
        col = 0
        while (lines.size > maxLines) lines.removeFirst()
    }

    fun clear() {
        lines.clear()
        lines.add(StringBuilder())
        col = 0
    }

    fun snapshot(): List<String> = lines.map { it.toString() }
}
