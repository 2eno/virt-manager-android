package dev.virtmanager.android.ui.console

import android.app.Activity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.virtmanager.android.ui.app
import dev.virtmanager.android.ui.components.PasswordDialog
import dev.virtmanager.android.vnc.Keysyms
import dev.virtmanager.android.vnc.RfbClient
import kotlin.math.abs
import kotlin.math.min

private const val SENTINEL = "  "

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VncScreen(connectionId: String, vmName: String, onBack: () -> Unit) {
    val app = app()
    val vm: VncViewModel = viewModel(key = "vnc-$connectionId-$vmName") { VncViewModel(app, connectionId, vmName) }
    val state by vm.state.collectAsState()
    val client = vm.client

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var toolbar by remember { mutableStateOf(true) }
    var keyboard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val modifiers = remember { StickyModifiers() }
    var shiftHeld by remember { mutableStateOf(false) }
    var imeValue by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    val focus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    // Immersive mode while the console is open.
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun scale(): Float {
        if (state.width == 0 || viewSize.width == 0) return 1f
        return min(viewSize.width / state.width.toFloat(), viewSize.height / state.height.toFloat()) * zoom
    }

    fun origin(): Offset {
        val s = scale()
        return Offset(
            (viewSize.width - state.width * s) / 2f + pan.x,
            (viewSize.height - state.height * s) / 2f + pan.y,
        )
    }

    fun toFb(p: Offset): Pair<Int, Int> {
        val o = origin()
        val s = scale()
        return ((p.x - o.x) / s).toInt() to ((p.y - o.y) / s).toInt()
    }

    fun clampPan(p: Offset): Offset {
        val s = scale()
        val maxX = ((state.width * s - viewSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((state.height * s - viewSize.height) / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    // Typed text and extra keys both take the sticky modifiers (Ctrl+C from the soft keyboard).
    fun sendText(text: String) {
        val c = client ?: return
        // A hardware Shift that is held already counts; don't press it a second time.
        val held = listOfNotNull(Keysyms.SHIFT_L.takeIf { shiftHeld })
        for (ch in text) c.sendCombo(*Keysyms.combo(modifiers.consume() + held, Keysyms.forChar(ch)))
    }

    fun sendSpecial(keysym: Int) {
        val c = client ?: return
        c.sendCombo(*Keysyms.combo(modifiers.consume(), keysym))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(client, state.width, state.height) {
                        val c = client ?: return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var last = start
                            var multi = false
                            var dragging = false
                            var longPressed = false
                            var scrollAccum = 0f
                            val (sx, sy) = toFb(start)
                            c.sendPointer(sx, sy, 0)
                            while (true) {
                                val event = if (!dragging && !multi && !longPressed) {
                                    withTimeoutOrNull(450) { awaitPointerEvent() }
                                } else awaitPointerEvent()
                                if (event == null) {
                                    // Held still: long press = right click.
                                    longPressed = true
                                    c.click(sx, sy, RfbClient.BUTTON_RIGHT)
                                    continue
                                }
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    if (dragging) {
                                        val (lx, ly) = toFb(last)
                                        c.sendPointer(lx, ly, 0)
                                        dragging = false
                                    }
                                    multi = true
                                    val z = event.calculateZoom()
                                    val p = event.calculatePan()
                                    if (zoom <= 1.01f && abs(z - 1f) < 0.01f) {
                                        // At fit zoom, two-finger vertical drag scrolls the guest.
                                        scrollAccum += p.y
                                        val (cx, cy) = toFb(pressed[0].position)
                                        while (abs(scrollAccum) > 40f) {
                                            c.scroll(cx, cy, up = scrollAccum > 0)
                                            scrollAccum -= 40f * Math.signum(scrollAccum)
                                        }
                                    } else {
                                        zoom = (zoom * z).coerceIn(1f, 6f)
                                        pan = clampPan(pan + p)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multi) {
                                    val ch = pressed[0]
                                    last = ch.position
                                    val moved = (ch.position - start).getDistance() > viewConfiguration.touchSlop
                                    if (moved && !longPressed) {
                                        val (x, y) = toFb(ch.position)
                                        if (!dragging) {
                                            dragging = true
                                            c.sendPointer(sx, sy, RfbClient.BUTTON_LEFT)
                                        }
                                        c.sendPointer(x, y, RfbClient.BUTTON_LEFT)
                                    }
                                    ch.consume()
                                }
                            }
                            if (!multi) {
                                when {
                                    dragging -> { val (x, y) = toFb(last); c.sendPointer(x, y, 0) }
                                    !longPressed -> c.click(sx, sy, RfbClient.BUTTON_LEFT)
                                }
                            }
                        }
                    },
            ) {
                val frame = state.frame ?: return@Canvas
                if (state.frameId < 0) return@Canvas
                val s = scale()
                val o = origin()
                drawImage(
                    image = frame.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset(o.x.toInt(), o.y.toInt()),
                    dstSize = IntSize((frame.width * s).toInt(), (frame.height * s).toInt()),
                    filterQuality = if (s >= 1.5f) FilterQuality.None else FilterQuality.Low,
                )
            }

            // Invisible field that receives soft-keyboard input.
            BasicTextField(
                value = imeValue,
                onValueChange = { v ->
                    val text = v.text
                    when {
                        text.length < SENTINEL.length -> repeat(SENTINEL.length - text.length) { sendSpecial(Keysyms.BACKSPACE) }
                        text.startsWith(SENTINEL) -> sendText(text.removePrefix(SENTINEL))
                        else -> sendText(text.trimStart())
                    }
                    imeValue = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        val sym = Keysyms.forAndroidKey(e.key.nativeKeyCode) ?: return@onPreviewKeyEvent false
                        if (sym == Keysyms.SHIFT_L) shiftHeld = e.type == KeyEventType.KeyDown
                        client?.sendKey(sym, e.type == KeyEventType.KeyDown)
                        true
                    },
            )

            if (!state.connected) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.error == null) CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(state.error ?: state.status, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    if (state.error != null && !state.needsPassword) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.reconnect(null) }) { Text("Reconnect") }
                    }
                }
            }

            // Top toolbar
            // Qualified: inside the Column the ColumnScope overload would be picked and refused.
            androidx.compose.animation.AnimatedVisibility(toolbar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(
                    color = Color(0xCC1B1D22),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Column(Modifier.weight(1f)) {
                                Text(vmName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (state.connected) "${state.width}×${state.height}" else state.status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB8BCC6),
                                )
                            }
                            IconButton(onClick = {
                                // The bar stays when the soft keyboard is dismissed with Back; then the
                                // button brings the keyboard back instead of closing the bar.
                                keyboard = !keyboard || !imeVisible
                                if (keyboard) { focus.requestFocus(); keyboardController?.show() }
                                else keyboardController?.hide()
                            }) { Icon(if (keyboard) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard, "Keyboard") }
                            IconButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Icon(Icons.Outlined.Fullscreen, "Fit to screen") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Ctrl+Alt+Delete") }, onClick = {
                                        menu = false
                                        client?.sendCombo(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.DELETE)
                                    })
                                    DropdownMenuItem(text = { Text("Ctrl+Alt+Backspace") }, onClick = {
                                        menu = false
                                        client?.sendCombo(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.BACKSPACE)
                                    })
                                    HorizontalDivider()
                                    (1..8).forEach { n ->
                                        DropdownMenuItem(text = { Text("Ctrl+Alt+F$n") }, onClick = {
                                            menu = false
                                            client?.sendCombo(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.function(n))
                                        })
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Reconnect") },
                                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                        onClick = { menu = false; vm.reconnect(null) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Small handle to bring the toolbar back.
            Surface(
                onClick = { toolbar = !toolbar },
                color = Color(0x991B1D22),
                contentColor = Color.White,
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.TouchApp, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (toolbar) "Hide controls" else "Controls", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // Termux-style special keys, docked above the soft keyboard.
        if (keyboard) ExtraKeysBar(modifiers, onKey = ::sendSpecial, modifier = Modifier.navigationBarsPadding())
    }

    if (state.needsPassword) {
        PasswordDialog(
            title = "VNC password",
            message = state.error ?: "The display of $vmName is password protected.",
            onConfirm = { vm.reconnect(it) },
            onDismiss = onBack,
        )
    }
}
