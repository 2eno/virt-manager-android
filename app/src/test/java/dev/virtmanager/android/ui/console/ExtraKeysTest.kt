package dev.virtmanager.android.ui.console

import dev.virtmanager.android.ui.console.StickyModifiers.Mode
import dev.virtmanager.android.vnc.Keysyms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysTest {
    @Test fun tapAppliesToTheNextKeyOnly() {
        val m = StickyModifiers()
        m.tap(Keysyms.CONTROL_L)
        assertEquals(Mode.ONCE, m.mode(Keysyms.CONTROL_L))
        assertEquals(listOf(Keysyms.CONTROL_L), m.consume())
        assertEquals(emptyList<Int>(), m.consume())
        // Tapping again before a key turns it off.
        m.tap(Keysyms.ALT_L); m.tap(Keysyms.ALT_L)
        assertEquals(emptyList<Int>(), m.consume())
    }

    @Test fun longPressLocksUntilTapped() {
        val m = StickyModifiers()
        m.lock(Keysyms.CONTROL_L)
        m.tap(Keysyms.ALT_L)
        // Ctrl+Alt in a fixed order, whatever order they were tapped in.
        assertEquals(listOf(Keysyms.CONTROL_L, Keysyms.ALT_L), m.consume())
        assertEquals(listOf(Keysyms.CONTROL_L), m.consume())
        m.tap(Keysyms.CONTROL_L)
        assertEquals(Mode.OFF, m.mode(Keysyms.CONTROL_L))
    }

    @Test fun layoutLikeTermux() {
        val labels = ExtraKeys.rows.map { row -> row.map { it.label } }
        assertEquals(listOf("ESC", "/", "-", "HOME", "↑", "END", "PGUP", "FN"), labels[0])
        assertEquals(listOf("TAB", "CTRL", "ALT", "←", "↓", "→", "PGDN", "DEL"), labels[1])
        // ↑ sits above ↓, as on a keyboard.
        assertEquals(labels[0].indexOf("↑"), labels[1].indexOf("↓"))
        assertEquals("|", ExtraKeys.rows[0][2].popup?.label)
        assertTrue(ExtraKeys.rows.flatten().filter { it.label in setOf("↑", "↓", "←", "→") }.all { it.repeats })
        assertEquals((1..12).map { "F$it" }, ExtraKeys.fnRow.map { it.label }.filter { it.startsWith("F") })
    }

    @Test fun shiftedCharactersHoldShift() {
        val shift = Keysyms.SHIFT_L
        assertEquals(listOf(shift, '!'.code), Keysyms.combo(emptyList(), '!'.code).toList())
        assertEquals(listOf(shift, 'A'.code), Keysyms.combo(emptyList(), 'A'.code).toList())
        assertEquals(listOf('1'.code), Keysyms.combo(emptyList(), '1'.code).toList())
        assertEquals(listOf(Keysyms.ESCAPE), Keysyms.combo(emptyList(), Keysyms.ESCAPE).toList())
        // Ctrl+Shift+... keeps one Shift, after the sticky modifiers.
        assertEquals(listOf(Keysyms.CONTROL_L, shift, '|'.code), Keysyms.combo(listOf(Keysyms.CONTROL_L), '|'.code).toList())
        assertEquals(listOf(shift, '~'.code), Keysyms.combo(listOf(shift), '~'.code).toList())
        assertTrue("~!@#$%^&*()_+{}|:\"<>?".all { Keysyms.needsShift(it.code) })
        assertTrue("`1234567890-=[]\\;',./ az".none { Keysyms.needsShift(it.code) })
    }
}
