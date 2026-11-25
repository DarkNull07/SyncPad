package dev.syncpad.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.syncpad.app.Hub
import dev.syncpad.app.input.AirMouse
import kotlinx.coroutines.delay

// Outer corners follow the pad, inner corners are tight, so the row reads as one part.
private val Lead = RoundedCornerShape(24.dp, 8.dp, 8.dp, 24.dp)
private val Mid = RoundedCornerShape(8.dp)
private val Trail = RoundedCornerShape(8.dp, 24.dp, 24.dp, 8.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadPage(shell: Shell) {
    val hub = shell.hub
    var keyboard by rememberSaveable { mutableStateOf(false) }
    var extras by rememberSaveable { mutableStateOf(false) }
    var extrasBefore by rememberSaveable { mutableStateOf(false) }
    val mods = remember { mutableStateListOf<String>() }
    val catcher = remember { arrayOfNulls<KeyCatcher>(1) }
    val ime = WindowInsets.isImeVisible
    var wasUp by remember { mutableStateOf(false) }
    // Back closes the phone keyboard without telling us, so follow it.
    LaunchedEffect(ime) { if (wasUp && !ime && keyboard) { keyboard = false; extras = extrasBefore }; wasUp = ime }

    fun press(k: String) {
        if (mods.isEmpty()) hub.combo(k) else { hub.combo(*(mods + k).toTypedArray()); mods.clear() }
    }
    fun type(s: String) {
        if (mods.isNotEmpty() && s.length == 1) press(s.lowercase()) else hub.text(s)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(S1)) {
            PadSurface(shell, Modifier.fillMaxSize())
            AndroidView({ c -> KeyCatcher(c, ::type, ::press).also { catcher[0] = it } }, Modifier.size(1.dp))
            androidx.compose.animation.AnimatedVisibility(hub.padHint, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text("Swipe to move. Tap to click.\nTwo fingers scroll.\nTap, then drag to select.", color = Dim, fontSize = 16.sp,
                    lineHeight = 25.sp, textAlign = TextAlign.Center)
            }
        }
        AnimatedVisibility(extras, enter = expandVertically(tween(160)) + fadeIn(tween(160)), exit = shrinkVertically(tween(120)) + fadeOut(tween(90))) {
            KeyRows(mods, ::press)
        }
        Row(Modifier.fillMaxWidth().height(if (keyboard) 54.dp else 72.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MouseKey(shell, "Left click", 0, Modifier.weight(1.5f), Lead)
            MouseKey(shell, "Middle click", 2, Modifier.weight(.55f), Mid)
            MouseKey(shell, "Right click", 1, Modifier.weight(1.5f), Mid)
            Key("Extra keys", Modifier.weight(.8f).fillMaxHeight(), text = "Keys", lit = extras, shape = Mid, size = 14) {
                shell.haptic(8)
                extras = !extras
                extrasBefore = extras
            }
            Key("Keyboard", Modifier.weight(.8f).fillMaxHeight(), Icons.Rounded.Keyboard, lit = keyboard, shape = Trail) {
                shell.haptic(8)
                keyboard = !keyboard
                // The extra keys come up with the keyboard, and go back to how they were after it.
                if (keyboard) { extrasBefore = extras; extras = true } else extras = extrasBefore
                catcher[0]?.show(keyboard)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { catcher[0]?.show(false) } }
}

@Composable
private fun PadSurface(shell: Shell, modifier: Modifier) {
    val hub = shell.hub
    AndroidView({ c -> PadView(c, hub, shell::haptic).also { v -> v.onTouch = { if (hub.padHint) hub.padHint = false } } }, modifier)
}

/** The same pad with its mouse buttons, for pages that have room for one. */
@Composable
fun MiniPad(shell: Shell, modifier: Modifier) = Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(S1).semantics { contentDescription = "Mouse pad" }) {
        PadSurface(shell, Modifier.fillMaxSize())
    }
    Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MouseKey(shell, "Left click", 0, Modifier.weight(1.5f), Lead)
        MouseKey(shell, "Middle click", 2, Modifier.weight(.6f), Mid)
        MouseKey(shell, "Right click", 1, Modifier.weight(1.5f), Trail)
    }
}

@Composable
private fun MouseKey(shell: Shell, label: String, b: Int, modifier: Modifier, shape: RoundedCornerShape) =
    Box(modifier.fillMaxHeight()) {
        Key(label, Modifier.fillMaxSize(), size = 0, shape = shape, onUp = { shell.hub.button(b, false) }) { shell.haptic(9); shell.hub.button(b, true) }
        // A notch instead of words: which side is which needs no label.
        Box(Modifier.align(Alignment.Center).size(if (b == 2) 5.dp else 26.dp, if (b == 2) 18.dp else 5.dp).clip(CircleShape).background(S3))
    }

@Composable
private fun KeyRows(mods: MutableList<String>, press: (String) -> Unit) {
    var fn by rememberSaveable { mutableStateOf(false) }
    val h = Modifier.height(44.dp)
    val gap = Arrangement.spacedBy(5.dp)
    Column(verticalArrangement = gap) {
        Row(Modifier.clip(R12).horizontalScroll(rememberScrollState()), horizontalArrangement = gap) {
            val keys = if (fn) (1..12).map { "f$it" to "F$it" } else listOf("home" to "Home", "end" to "End", "pageup" to "PgUp",
                "pagedown" to "PgDn", "insert" to "Ins", "delete" to "Del", "printscreen" to "PrtSc", "menu" to "Menu")
            keys.forEach { (k, l) ->
                Box(Modifier.tap(R12, color = S1) { press(k) }.height(44.dp).widthIn(min = 56.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(l, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Key("Esc", h.weight(1f), size = 14, shape = R12) { press("esc") }
            Key("Tab", h.weight(1f), size = 14, shape = R12) { press("tab") }
            listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win").forEach { (k, l) ->
                Key(l, h.weight(1.1f), lit = k in mods, size = 14, shape = R12) { if (!mods.remove(k)) mods.add(k) }
            }
            Key("Fn", h.weight(.9f), lit = fn, size = 14, shape = R12) { fn = !fn }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            Key("Left arrow", h.weight(1f), Icons.AutoMirrored.Rounded.ArrowBack, shape = R12, iconSize = 20, repeat = true) { press("left") }
            Key("Up arrow", h.weight(1f), Icons.Rounded.ArrowUpward, shape = R12, iconSize = 20, repeat = true) { press("up") }
            Key("Down arrow", h.weight(1f), Icons.Rounded.ArrowDownward, shape = R12, iconSize = 20, repeat = true) { press("down") }
            Key("Right arrow", h.weight(1f), Icons.AutoMirrored.Rounded.ArrowForward, shape = R12, iconSize = 20, repeat = true) { press("right") }
            Key("Enter", h.weight(1.4f), Icons.AutoMirrored.Rounded.KeyboardReturn, shape = R12, iconSize = 20) { press("enter") }
        }
    }
}

// ---- air mouse and slides

@Composable
fun AirPage(shell: Shell) {
    val hub = shell.hub
    val ctx = LocalContext.current
    val air = remember { AirMouse(hub) }
    var aiming by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyro = remember { sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    air.speed = hub.airSpeed

    DisposableEffect(aiming) {
        if (!aiming || gyro == null) return@DisposableEffect onDispose {}
        val t = HandlerThread("syncpad-air", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_GYROSCOPE) air.gyro(e.values[0], e.values[1], e.values[2], e.timestamp)
                else air.gravity(e.values[0], e.values[1], e.values[2])
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        air.reset()
        val h = Handler(t.looper)
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sm.registerListener(l, g, 10_000, 0, h)
        sm.registerListener(l, gyro, 5_000, 0, h)
        onDispose { sm.unregisterListener(l); t.quitSafely() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segments(listOf("Pointer", "Slides"), mode) { mode = it }
        if (mode == 0) Pointer(shell, gyro != null, aiming) { aiming = it } else Slides(shell, gyro != null, aiming) { aiming = it }
    }
}

@Composable
private fun ColumnScope.Pointer(shell: Shell, works: Boolean, aiming: Boolean, aim: (Boolean) -> Unit) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { AimButton(shell, works, aiming, Modifier.fillMaxSize(), aim) }
        ScrollStrip(shell, Modifier.width(60.dp).fillMaxHeight())
    }
    Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MouseKey(shell, "Left click", 0, Modifier.weight(1f), Lead)
        MouseKey(shell, "Right click", 1, Modifier.weight(1f), Trail)
    }
}

/** Hold to steer. Rings spread while it is live, so it is clear the phone is being read. */
@Composable
private fun AimButton(shell: Shell, works: Boolean, aiming: Boolean, modifier: Modifier, aim: (Boolean) -> Unit) {
    val live by animateFloatAsState(if (aiming) 1f else 0f, tween(180), label = "aim")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(size.width, size.height) / 2
            val w = 2.dp.toPx()
            for (i in 0..2) drawCircle(if (aiming) Accent.copy(alpha = .5f - .14f * i) else S2, r * (.56f + i * .14f + live * .02f * i), Offset(center.x, center.y), style = Stroke(w))
        }
        BoxWithConstraints {
            val d = minOf(maxWidth, maxHeight) * .5f
            Key(if (works) "Hold to point" else "No gyroscope on this phone", Modifier.size(d), Icons.Rounded.ControlCamera, shape = CircleShape, iconSize = 40,
                lit = aiming, caption = if (!works) "No gyroscope" else if (aiming) "Move the phone" else "Hold to point",
                onUp = { aim(false) }) { shell.haptic(12); if (works) aim(true) }
        }
    }
}

@Composable
private fun ScrollStrip(shell: Shell, modifier: Modifier) {
    val hub = shell.hub
    Box(
        modifier.clip(R24).background(S1).semantics { contentDescription = "Scroll strip" }.pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { shell.haptic(6) }) { c, dy ->
                c.consume()
                hub.scroll(0f, dy * 1.5f * hub.scrollSpeed * if (hub.natural) 1 else -1)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Dim, modifier = Modifier.size(22.dp))
            repeat(7) { i -> Box(Modifier.size(if (i == 3) 22.dp else if (i == 2 || i == 4) 18.dp else 12.dp, 3.dp).clip(CircleShape).background(S3)) }
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Dim, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ColumnScope.Slides(shell: Shell, works: Boolean, aiming: Boolean, aim: (Boolean) -> Unit) {
    val hub = shell.hub
    var started by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) { while (started != 0L) { now = System.currentTimeMillis(); delay(500) } }
    val s = if (started == 0L) 0 else ((now - started) / 1000).coerceAtLeast(0)
    Row(Modifier.tap(R24) { started = if (started == 0L) System.currentTimeMillis().also { now = it } else 0L }.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("%d:%02d".format(s / 60, s % 60), color = if (started == 0L) Dim else Ink, fontSize = 40.sp, fontWeight = FontWeight.Light, style = Numbers, modifier = Modifier.weight(1f))
        Text(if (started == 0L) "Start timer" else "Reset", color = Dim, fontSize = 14.sp)
    }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("Previous slide", Modifier.weight(1f).fillMaxHeight(), Icons.AutoMirrored.Rounded.ArrowBack, shape = R24, iconSize = 30, caption = "Back") { shell.haptic(16); hub.combo("left") }
        Key("Next slide", Modifier.weight(1.9f).fillMaxHeight(), Icons.AutoMirrored.Rounded.ArrowForward, shape = R24, iconSize = 48, tint = Accent, caption = "Next slide") { shell.haptic(16); hub.combo("right") }
    }
    if (works) Key("Hold to point", Modifier.fillMaxWidth().height(64.dp), lit = aiming, onUp = { aim(false) }) { shell.haptic(12); aim(true) }
    Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("Start", Modifier.weight(1f).fillMaxHeight()) { hub.combo("f5"); if (started == 0L) { started = System.currentTimeMillis(); now = started } }
        Key("Laser", Modifier.weight(1f).fillMaxHeight()) { hub.combo("ctrl", "l") }
        Key("Blank", Modifier.weight(1f).fillMaxHeight()) { hub.combo("b") }
        Key("End", Modifier.weight(1f).fillMaxHeight()) { hub.combo("esc") }
    }
}

// ---- laptop screen

@Composable
fun ScreenPage(shell: Shell) {
    val view = remember { arrayOfNulls<ScreenView>(1) }
    var monitors by remember { mutableIntStateOf(1) }
    var monitor by remember { mutableIntStateOf(0) }
    var sharp by remember { mutableStateOf(false) }
    var hint by rememberSaveable { mutableStateOf(true) }
    var wide by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(wide) { shell.landscape(wide); onDispose { shell.landscape(false) } }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ c ->
            ScreenView(c, shell.hub, shell::haptic).also { v -> view[0] = v; v.onMonitors = { monitors = it }; v.onTouch = { hint = false } }
        }, Modifier.fillMaxSize().clip(R24))
        AnimatedVisibility(hint && !wide, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Text("Tap to click. Two-finger tap to right click.\nHold, then drag. Pinch to zoom.", color = Dim, fontSize = 14.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp, start = 24.dp, end = 24.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).clip(R24).background(S1).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("Rotate", Modifier.size(64.dp, 48.dp), Icons.Rounded.ScreenRotation, lit = wide) { wide = !wide }
            Key("Sharper picture", Modifier.size(64.dp, 48.dp), Icons.Rounded.HighQuality, lit = sharp) { sharp = !sharp; view[0]?.quality = if (sharp) 85 else 60 }
            if (monitors > 1) Key("Monitor ${monitor + 1}", Modifier.size(112.dp, 48.dp), size = 14) { monitor = (monitor + 1) % monitors; view[0]?.monitor = monitor }
        }
    }
}

// ---- media

@Composable
fun MediaPage(hub: Hub) = Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        Key("Previous", Modifier.size(76.dp), Icons.Rounded.SkipPrevious, shape = CircleShape, iconSize = 34) { hub.combo("prev") }
        Key("Play or pause", Modifier.size(116.dp), Icons.Rounded.PlayArrow, shape = CircleShape, iconSize = 56, accent = true) { hub.combo("play_pause") }
        Key("Next", Modifier.size(76.dp), Icons.Rounded.SkipNext, shape = CircleShape, iconSize = 34) { hub.combo("next") }
    }
    Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Key("Volume down", Modifier.weight(1.3f).fillMaxHeight(), Icons.Rounded.Remove, shape = Lead, iconSize = 28, repeat = true) { hub.combo("vol_down") }
        Key("Mute", Modifier.weight(1f).fillMaxHeight(), Icons.AutoMirrored.Rounded.VolumeOff, shape = Mid) { hub.combo("mute") }
        Key("Volume up", Modifier.weight(1.3f).fillMaxHeight(), Icons.Rounded.Add, shape = Trail, iconSize = 28, repeat = true) { hub.combo("vol_up") }
    }
    Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Key("Back", Modifier.weight(1f).fillMaxHeight(), Icons.Rounded.FastRewind, shape = Lead, repeat = true) { hub.combo("left") }
        Key("Full screen", Modifier.weight(1f).fillMaxHeight(), Icons.Rounded.Fullscreen, shape = Mid) { hub.combo("f") }
        Key("Forward", Modifier.weight(1f).fillMaxHeight(), Icons.Rounded.FastForward, shape = Trail, repeat = true) { hub.combo("right") }
    }
    Section("Laptop")
    if (!hub.online) Body("Power controls need Wi-Fi or USB.")
    else {
        var confirm by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Power("Lock", Icons.Rounded.Lock) { hub.system("lock") }
            Power("Screen off", Icons.Rounded.DesktopAccessDisabled) { hub.system("monitor_off") }
            Power("Sleep", Icons.Rounded.Bedtime) { confirm = true }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, shape = R24, title = { Text("Put the laptop to sleep?") },
            text = { Body("You will need to wake it by hand.") },
            confirmButton = { TextButton({ confirm = false; hub.system("sleep") }) { Text("Sleep") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = Dim) } })
    }
    Gap(4)
}

@Composable
private fun RowScope.Power(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onTap: () -> Unit) = Column(
    Modifier.weight(1f).fillMaxHeight().tap(R18, onTap = onTap), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
) {
    Icon(icon, null, tint = Ink, modifier = Modifier.size(24.dp))
    Text(label, color = Dim, fontSize = 13.sp)
}

// ---- gamepad

@Composable
fun GamepadPage(shell: Shell, close: () -> Unit) {
    DisposableEffect(Unit) { shell.landscape(true); onDispose { shell.landscape(false) } }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ GamepadView(it, shell.hub, shell::haptic) }, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopCenter).size(44.dp).tap(CircleShape, color = Bg, onTap = close), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close gamepad", tint = Dim, modifier = Modifier.size(20.dp))
        }
    }
}
