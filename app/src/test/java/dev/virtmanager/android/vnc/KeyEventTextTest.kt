package dev.virtmanager.android.vnc

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyEventTextTest {
    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)

    @Test fun charactersOfKeyEvents() {
        // The digits row: typed by the console itself, not also by its text field.
        assertEquals("1", Keysyms.textOf(key(KeyEvent.KEYCODE_1)))
        assertEquals("!", Keysyms.textOf(key(KeyEvent.KEYCODE_1, KeyEvent.META_SHIFT_ON)))
        assertEquals("a", Keysyms.textOf(key(KeyEvent.KEYCODE_A)))
        assertEquals(" ", Keysyms.textOf(key(KeyEvent.KEYCODE_SPACE)))
        assertNull(Keysyms.textOf(key(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertNull(Keysyms.textOf(key(KeyEvent.KEYCODE_DPAD_LEFT)))
    }
}
