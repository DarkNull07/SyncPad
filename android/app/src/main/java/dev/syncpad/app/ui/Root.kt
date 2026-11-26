package dev.syncpad.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncpad.app.Hub
import dev.syncpad.app.net.HidPhase
import dev.syncpad.app.net.Mode
import dev.syncpad.app.net.Phase
import kotlinx.coroutines.delay

/** What the screens need from the activity. */
interface Shell {
    val hub: Hub
    fun haptic(ms: Int)
    fun scan()
    fun paste()
    fun landscape(on: Boolean)
    fun enableBluetooth()
    fun discoverable()
    fun allowFiles()
}

enum class Tab(val label: String, val icon: ImageVector) {
    PAD("Pad", Icons.Rounded.TouchApp), AIR("Air", Icons.Rounded.ControlCamera), SCREEN("Screen", Icons.Rounded.DesktopWindows),
    DECK("Deck", Icons.Rounded.GridView), MORE("More", Icons.Rounded.MoreHoriz)
}

private val Titles = mapOf("remote" to "Media", "files" to "Share", "browse" to "Laptop files", "connect" to "Connection", "settings" to "Settings")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Root(shell: Shell, start: Tab = Tab.PAD, startSub: String? = null) = Theme {
    var tab by rememberSaveable { mutableStateOf(start) }
    var sub by rememberSaveable { mutableStateOf(startSub) }
    val hub = shell.hub
    val paired = hub.laptops.isNotEmpty() || hub.hid.phase == HidPhase.CONNECTED || hub.mode == Mode.BT
    val full = sub == "gamepad"
    var choosing by remember { mutableStateOf(false) }
    val ime = WindowInsets.isImeVisible
    // Once per connection, and only if it lasts: a passing hiccup is not news.
    LaunchedEffect(hub.status.phase, hub.status.unsteady, hub.usingBt) {
        if (hub.status.phase == Phase.CONNECTED && hub.status.unsteady && !hub.usingBt && !hub.toldUnsteady) {
            delay(4000)
            hub.toldUnsteady = true
            hub.notice = "This Wi-Fi keeps pausing. USB or Bluetooth will be smoother."
        }
    }
    LaunchedEffect(hub.notice) { if (hub.notice != null) { delay(2600); hub.notice = null } }

    // Before the pages, so a page with its own back handling is asked first.
    if (sub != null) BackHandler { sub = null }
    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            if (!full) Header(hub, sub?.let { Titles[it] }, { sub = null }) { choosing = true }
            // Keyed on what is shown. Fades are short: switching must feel instant.
            AnimatedContent(sub ?: if (!paired && tab != Tab.MORE) "welcome" else tab.name, Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(tween(130)) togetherWith fadeOut(tween(70)) }, label = "page") { page ->
                when (page) {
                    "welcome" -> Welcome(shell) { tab = Tab.MORE; sub = "connect" }
                    "remote" -> MediaPage(hub)
                    "gamepad" -> GamepadPage(shell) { sub = null }
                    "files" -> SharePage(shell)
                    "browse" -> FilesPage(hub)
                    "connect" -> ConnectPage(shell)
                    "settings" -> SettingsPage(hub)
                    Tab.PAD.name -> PadPage(shell)
                    Tab.AIR.name -> AirPage(shell)
                    Tab.SCREEN.name -> ScreenPage(shell)
                    Tab.DECK.name -> DeckPage(shell)
                    else -> MorePage(hub) { sub = it }
                }
            }
            if (!full && !ime) Dock(tab) { tab = it; sub = null }
        }
        AnimatedVisibility(choosing, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            Box(Modifier.fillMaxSize().background(Bg.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null) { choosing = false })
        }
        AnimatedVisibility(choosing, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(170)) { it }, exit = slideOutVertically(tween(120)) { it }) {
            ModeSheet(shell, { choosing = false }) { choosing = false; tab = Tab.MORE; sub = "connect" }
        }
        if (choosing) BackHandler { choosing = false }
        var shown by remember { mutableStateOf("") }
        hub.notice?.let { shown = it }
        AnimatedVisibility(hub.notice != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp, start = 24.dp, end = 24.dp),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Text(shown, color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(R18).background(Ink).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
    hub.pendingPair?.let { l ->
        AlertDialog(onDismissRequest = { hub.pendingPair = null }, shape = R24,
            title = { Text("Pair with ${l.name}?") },
            text = {
                val again = hub.laptops.firstOrNull { it.hostId == l.hostId }
                Body((if (again != null) "This replaces ${again.name}, which is already paired. " else "") +
                    "Only pair with a laptop that is showing this code right now. What you type and share in SyncPad will be sent to it.", if (again != null) Warn else Dim)
            },
            confirmButton = { TextButton({ hub.pair(l) }) { Text("Pair") } },
            dismissButton = { TextButton({ hub.pendingPair = null }) { Text("Cancel", color = Dim) } })
    }
}

@Composable
private fun Header(hub: Hub, title: String?, back: () -> Unit, open: () -> Unit) {
    val s = hub.status
    val name = hub.laptops.firstOrNull { it.hostId == hub.current }?.name ?: "Laptop"
    val (icon, text) = when {
        hub.usingBt -> Icons.Rounded.Bluetooth to "Bluetooth"
        s.phase == Phase.CONNECTED -> (if (s.via == "USB") Icons.Rounded.Usb else Icons.Rounded.Wifi) to if (s.rtt >= 0) "${s.rtt} ms" else s.via
        s.phase == Phase.SEARCHING -> null to when (hub.mode) { Mode.USB -> "Finding USB"; Mode.WIFI -> "Finding Wi-Fi"; else -> "Searching" }
        else -> null to "Offline"
    }
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (title != null) Box(Modifier.size(48.dp).tap(CircleShape, color = Bg, onTap = back), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ink, modifier = Modifier.size(24.dp))
        } else Gap(12)
        Text(title ?: if (hub.usingBt) hub.hid.name else if (hub.laptops.isEmpty()) "SyncPad" else name, color = Ink, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.tap(CircleShape, color = S1, onTap = open).height(40.dp).padding(horizontal = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shaky = s.unsteady && !hub.usingBt
            if (icon != null) Icon(icon, if (hub.usingBt) "Bluetooth" else if (shaky) "${s.via}, unsteady" else s.via, tint = if (shaky) Warn else Accent, modifier = Modifier.size(17.dp))
            else Dot(s.phase == Phase.SEARCHING)
            Text(text, color = if (icon != null) Ink else Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium, style = Numbers)
        }
    }
}

/** Where each way of connecting stands, in a few words. */
fun modeNote(hub: Hub, m: Mode): String {
    val s = hub.status
    val here = s.phase == Phase.CONNECTED
    return when (m) {
        Mode.AUTO -> if (hub.remote) "USB first, then Wi-Fi, then the internet when you are away" else "USB first, then Wi-Fi"
        Mode.USB -> if (here && s.via == "USB") "Connected" else if (hub.mode == Mode.USB) "No cable link found yet" else "Smoothest. Needs the cable"
        Mode.WIFI -> if (here && s.via == "Wi-Fi") (if (s.unsteady) "Connected, but unsteady" else "Connected") else if (hub.mode == Mode.WIFI) "Laptop not found on this network yet" else "No cable. Everything works"
        Mode.BT -> when (hub.hid.phase) {
            HidPhase.CONNECTED -> if (here) "Connected. Files still use ${s.via}" else "Connected. No files or screen"
            HidPhase.READY -> "Ready, not connected to a laptop"
            HidPhase.UNAVAILABLE -> "Not available on this phone"
            HidPhase.NO_PERMISSION -> "Needs the Nearby devices permission"
            HidPhase.OFF -> "Off"
        }
    }
}

fun modeIcon(m: Mode) = when (m) { Mode.AUTO -> Icons.Rounded.AutoMode; Mode.USB -> Icons.Rounded.Usb; Mode.WIFI -> Icons.Rounded.Wifi; Mode.BT -> Icons.Rounded.Bluetooth }

fun pickMode(shell: Shell, m: Mode) {
    shell.hub.mode = m
    if (m == Mode.BT && shell.hub.hid.phase != HidPhase.CONNECTED) shell.enableBluetooth()
}

/** One tap from anywhere to change how the phone is connected. */
@Composable
private fun ModeSheet(shell: Shell, close: () -> Unit, more: () -> Unit) {
    val hub = shell.hub
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(S1)
        .clickable(remember { MutableInteractionSource() }, null) {}.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Connect with", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
        Mode.entries.forEach { m ->
            val on = hub.mode == m
            Row(Modifier.fillMaxWidth().tap(R18, color = if (on) S3 else S1) { pickMode(shell, m); if (m != Mode.BT || hub.hid.phase == HidPhase.CONNECTED) close() }
                .heightIn(min = 66.dp).padding(horizontal = 14.dp, vertical = 8.dp).semantics { selected = on },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(modeIcon(m), null, tint = if (on) Accent else Ink, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(modeNote(hub, m), color = Dim, fontSize = 13.sp, lineHeight = 17.sp)
                }
                if (on) Icon(Icons.Rounded.Check, "Selected", tint = Accent, modifier = Modifier.size(22.dp))
            }
        }
        Action("Connection settings", Modifier.fillMaxWidth().padding(top = 6.dp), onTap = more)
    }
}

@Composable
private fun Dot(searching: Boolean) {
    if (!searching) { Box(Modifier.size(8.dp).clip(CircleShape).background(Dim)); return }
    val a by rememberInfiniteTransition(label = "dot").animateFloat(.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Warn))
}

@Composable
private fun Dock(tab: Tab, pick: (Tab) -> Unit) = BoxWithConstraints(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(64.dp).clip(R24).background(S1).padding(6.dp),
) {
    val w = maxWidth / Tab.entries.size
    val x by animateDpAsState(w * tab.ordinal, spring(.8f, Spring.StiffnessMediumLow), label = "dock")
    Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().clip(R18).background(S3))
    Row {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(R18)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { pick(t) }.semantics { selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = if (on) Accent else Dim, modifier = Modifier.size(22.dp))
                Text(t.label, color = if (on) Ink else Dim, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Welcome(shell: Shell, bluetooth: () -> Unit) = Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { if (maxHeight > 210.dp) Illustration() }
    Text("Your phone,\nin control.", color = Ink, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
    Gap(10)
    Body("Open SyncPad on your laptop and scan its code. You only do this once.")
    Gap(24)
    Action("Scan the code", Modifier.fillMaxWidth(), primary = true) { shell.scan() }
    Gap(10)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action("Paste link", Modifier.weight(1f)) { shell.paste() }
        Action("Bluetooth", Modifier.weight(1f), onTap = bluetooth)
    }
    Gap(14)
    Body("On a cable? Press Pair over USB on the laptop.")
    Gap(16)
}

/** A phone steering a cursor on a laptop, drawn with the same shapes the app uses. */
@Composable
private fun Illustration() {
    val t by rememberInfiniteTransition(label = "art").animateFloat(0f, 1f, infiniteRepeatable(tween(1900), RepeatMode.Reverse), label = "art")
    Canvas(Modifier.size(280.dp, 190.dp)) {
        val u = size.width / 280f
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float, c: androidx.compose.ui.graphics.Color) =
            drawRoundRect(c, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
        box(74f, 8f, 200f, 128f, 14f, S2); box(82f, 16f, 184f, 112f, 8f, Bg)
        box(56f, 140f, 224f, 12f, 6f, S3)
        box(0f, 62f, 66f, 124f, 16f, Bg); box(4f, 66f, 58f, 116f, 13f, S3); box(9f, 71f, 48f, 106f, 9f, S1)
        val e = t * t * (3 - 2 * t)
        val fx = 21f + 24f * e; val fy = 148f - 44f * e
        drawCircle(Accent.copy(alpha = .22f), 13f * u, Offset(fx * u, fy * u))
        drawCircle(Accent, 5.5f * u, Offset(fx * u, fy * u))
        val cx = 112f + 112f * e; val cy = 104f - 60f * e
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx * u, cy * u); lineTo(cx * u, (cy + 19) * u); lineTo((cx + 4.6f) * u, (cy + 14.8f) * u)
            lineTo((cx + 8f) * u, (cy + 21.6f) * u); lineTo((cx + 11.4f) * u, (cy + 20f) * u); lineTo((cx + 8.2f) * u, (cy + 13.4f) * u)
            lineTo((cx + 14f) * u, (cy + 13f) * u); close()
        }
        drawPath(p, Ink)
    }
}
