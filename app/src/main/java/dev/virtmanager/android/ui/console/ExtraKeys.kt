package dev.virtmanager.android.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.virtmanager.android.vnc.Keysyms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ctrl, Alt, Super and Shift as on Termux's extra keys: a tap applies the modifier to the next
 * key only (also one typed on the soft keyboard), a long press locks it until tapped again.
 */
class StickyModifiers {
    enum class Mode { OFF, ONCE, LOCKED }

    private val modes = mutableStateMapOf<Int, Mode>()

    fun mode(keysym: Int): Mode = modes[keysym] ?: Mode.OFF

    fun tap(keysym: Int) {
        modes[keysym] = if (mode(keysym) == Mode.OFF) Mode.ONCE else Mode.OFF
    }

    fun lock(keysym: Int) {
        modes[keysym] = Mode.LOCKED
    }

    /** The modifiers to hold for the next key, in press order; one-shot ones are used up. */
    fun consume(): List<Int> {
        val active = ORDER.filter { mode(it) != Mode.OFF }
        active.filter { mode(it) == Mode.ONCE }.forEach { modes[it] = Mode.OFF }
        return active
    }

    companion object {
        val ORDER = listOf(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.SUPER_L, Keysyms.SHIFT_L)
    }
}

/** One key of the bar; [popup] is sent instead when the key is swiped up (Termux's popups). */
data class ExtraKey(
    val label: String,
    val keysym: Int,
    val popup: ExtraKey? = null,
    val modifier: Boolean = false,
    /** Held down, the key repeats like on a hardware keyboard (arrows, paging). */
    val repeats: Boolean = false,
)

object ExtraKeys {
    private fun key(label: String, sym: Int, popup: ExtraKey? = null) = ExtraKey(label, sym, popup)
    private fun char(c: Char, popup: Char? = null): ExtraKey = ExtraKey(c.toString(), Keysyms.forChar(c), popup?.let { char(it) })
    private fun mod(label: String, sym: Int) = ExtraKey(label, sym, modifier = true)
    private fun nav(label: String, sym: Int, popup: ExtraKey? = null) = ExtraKey(label, sym, popup, repeats = true)

    /** Marks the key that shows the function-key row. */
    const val FN = -1

    /** Termux's default layout, plus Super, Delete and Fn for desktops and installers. */
    val rows: List<List<ExtraKey>> = listOf(
        listOf(
            key("ESC", Keysyms.ESCAPE), char('/', '\\'), char('-', '|'), nav("HOME", Keysyms.HOME),
            nav("↑", Keysyms.UP), nav("END", Keysyms.END), nav("PGUP", Keysyms.PAGE_UP), key("FN", FN),
        ),
        listOf(
            key("TAB", Keysyms.TAB), mod("CTRL", Keysyms.CONTROL_L), mod("ALT", Keysyms.ALT_L),
            nav("←", Keysyms.LEFT), nav("↓", Keysyms.DOWN), nav("→", Keysyms.RIGHT), nav("PGDN", Keysyms.PAGE_DOWN),
            nav("DEL", Keysyms.DELETE),
        ),
    )

    /** Shown above the two rows while FN is on. */
    val fnRow: List<ExtraKey> = listOf(mod("SUPER", Keysyms.SUPER_L), mod("SHIFT", Keysyms.SHIFT_L), key("INS", Keysyms.INSERT)) +
        (1..12).map { key("F$it", Keysyms.function(it)) }
}

/**
 * The extra-keys bar under the VNC display, docked above the soft keyboard. [onKey] gets the
 * keysym to press together with the modifiers that are on.
 */
@Composable
fun ExtraKeysBar(modifiers: StickyModifiers, onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
    var fn by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Color(0xFF101114)).padding(vertical = 2.dp)) {
        if (fn) {
            Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState())) {
                ExtraKeys.fnRow.forEach { k -> KeyCell(k, modifiers, onKey, Modifier.width(56.dp)) }
            }
        }
        ExtraKeys.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                row.forEach { k ->
                    if (k.keysym == ExtraKeys.FN) {
                        KeyCell(k, modifiers, onKey = { fn = !fn }, Modifier.weight(1f), highlighted = fn)
                    } else {
                        KeyCell(k, modifiers, onKey, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(
    key: ExtraKey,
    modifiers: StickyModifiers,
    onKey: (Int) -> Unit,
    modifier: Modifier,
    highlighted: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    val mode = if (key.modifier) modifiers.mode(key.keysym) else StickyModifiers.Mode.OFF
    val accent = MaterialTheme.colorScheme.primary
    val background = when {
        mode == StickyModifiers.Mode.LOCKED -> accent
        pressed -> Color(0xFF2C2F36)
        else -> Color.Transparent
    }
    val textColor = when {
        mode == StickyModifiers.Mode.LOCKED -> MaterialTheme.colorScheme.onPrimary
        mode == StickyModifiers.Mode.ONCE || highlighted -> accent
        else -> Color.White
    }
    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .background(background, RoundedCornerShape(6.dp))
            .semantics {
                contentDescription = key.label
                role = Role.Button
                if (key.modifier) selected = mode != StickyModifiers.Mode.OFF
            }
            .pointerInput(key) {
                val swipe = 24.dp.toPx()
                val scope = CoroutineScope(currentCoroutineContext())
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    var popupChosen = false
                    when {
                        key.modifier -> {
                            // Held: lock. Tapped: next key only.
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUp() }
                            if (up == null) {
                                modifiers.lock(key.keysym)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                waitForUp()
                            } else {
                                modifiers.tap(key.keysym)
                            }
                        }
                        key.repeats && key.popup == null -> {
                            // Fires at once, then repeats while held, like a hardware key.
                            onKey(key.keysym)
                            val repeat = scope.launch {
                                delay(400)
                                while (true) {
                                    onKey(key.keysym)
                                    delay(50)
                                }
                            }
                            waitForUp()
                            repeat.cancel()
                        }
                        else -> {
                            // Fires on release; swiped up it sends the popup key instead.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (key.popup != null) {
                                    popupChosen = down.position.y - change.position.y > swipe
                                    showPopup = popupChosen
                                }
                                if (!change.pressed) break
                                change.consume()
                            }
                            onKey(if (popupChosen) key.popup!!.keysym else key.keysym)
                        }
                    }
                    pressed = false
                    showPopup = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label,
            color = textColor,
            fontSize = if (key.label.length > 3) 13.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        // Termux shows nothing for popups; a small hint makes them discoverable.
        key.popup?.let { p ->
            Text(
                p.label,
                color = Color(0xFF8A8F99),
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp),
            )
            if (showPopup) {
                Surface(
                    color = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-40).dp),
                ) {
                    Text(p.label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 16.sp)
                }
            }
        }
    }
}

/** Waits until every pointer of the current gesture is up. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}
