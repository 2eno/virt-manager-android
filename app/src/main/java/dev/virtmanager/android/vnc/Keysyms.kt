package dev.virtmanager.android.vnc

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** X11 keysyms used by the RFB protocol. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val INSERT = 0xff63
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val END = 0xff57
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9
    const val SUPER_L = 0xffeb
    const val F1 = 0xffbe

    fun function(n: Int) = F1 + (n - 1)

    /** Keysym for a printable character (Latin-1 maps 1:1, everything else via the Unicode range). */
    fun forChar(c: Char): Int {
        val ch = ascii(c)
        return when {
            ch == '\n' -> RETURN
            ch == '\t' -> TAB
            ch.code in 0x20..0xFF -> ch.code
            else -> 0x01000000 or ch.code
        }
    }

    /**
     * Soft keyboards often type look-alikes instead of ASCII: a spacing or combining (dead-key)
     * tilde, typographic quotes and dashes, fullwidth forms. QEMU can only type what a US
     * keyboard has, so these become the ASCII character they stand for.
     */
    fun ascii(c: Char): Char = when (c) {
        '\u02DC', '\u0303', '\u223C', '\u2053', '\u301C' -> '~'
        '\u02CB', '\u0300', '\u2035' -> '`'
        '\u02C6', '\u0302', '\u2038' -> '^'
        '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '\u02BC' -> '\''
        '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> '"'
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> '-'
        '\u00A0', '\u2007', '\u202F' -> ' '
        in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar() // fullwidth ASCII
        else -> c
    }

    /** Printable ASCII that a US keyboard types with Shift (letters aside). */
    private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"

    /**
     * Whether [keysym] needs Shift held to come out right. QEMU's VNC server maps a keysym to
     * a key on a US keyboard but drops the Shift that key needs (it only fakes Caps Lock for
     * letters), so "!" arrives as "1" unless the client presses Shift itself, as a real
     * keyboard would.
     */
    fun needsShift(keysym: Int): Boolean =
        keysym in 'A'.code..'Z'.code || (keysym in 0x20..0x7e && keysym.toChar() in SHIFTED)

    /** The key presses for [keysym] with the [modifiers] held: adds Shift where [needsShift]. */
    fun combo(modifiers: List<Int>, keysym: Int): IntArray {
        val shift = needsShift(keysym) && SHIFT_L !in modifiers
        return (modifiers + listOfNotNull(SHIFT_L.takeIf { shift }) + keysym).toIntArray()
    }

    /**
     * The character a key event types, or null for keys that type none. Dead keys (accents
     * that combine with the next letter) give their accent, which [ascii] turns into e.g. "~".
     */
    fun textOf(event: KeyEvent): String? {
        val c = event.unicodeChar
        if (c == 0) return null
        val code = if (c and KeyCharacterMap.COMBINING_ACCENT != 0) c and KeyCharacterMap.COMBINING_ACCENT_MASK else c
        if (code < 0x20 && code != '\n'.code && code != '\t'.code) return null
        return String(Character.toChars(code))
    }

    fun forAndroidKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_L
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_L
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> ALT_L
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> SUPER_L
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> function(keyCode - KeyEvent.KEYCODE_F1 + 1)
        else -> null
    }
}
