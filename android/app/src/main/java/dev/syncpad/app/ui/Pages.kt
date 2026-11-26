package dev.syncpad.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncpad.app.DeckKey
import dev.syncpad.app.Hub
import dev.syncpad.app.Transfer
import dev.syncpad.app.net.HidPhase
import dev.syncpad.app.net.Phase

// ---- deck

private fun deckIcon(type: String) = when (type) { "open" -> Icons.Rounded.RocketLaunch; "text" -> Icons.AutoMirrored.Rounded.Notes; else -> Icons.Rounded.Keyboard }

@Composable
fun DeckPage(shell: Shell) {
    val hub = shell.hub
    var edit by remember { mutableStateOf<Int?>(null) }
    var arranging by remember { mutableStateOf(false) }
    val deck = hub.deck
    edit?.let { i ->
        DeckEditor(hub, deck.getOrNull(i), i, deck.size, { edit = null }, { by ->
            hub.saveDeck(deck.toMutableList().apply { add(i + by, removeAt(i)) }); edit = i + by
        }) { k ->
            hub.saveDeck(when { k == null -> deck.filterIndexed { n, _ -> n != i }; i < 0 -> deck + k; else -> deck.mapIndexed { n, o -> if (n == i) k else o } })
            edit = null
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The pad takes whatever the keys leave. When they leave too little it keeps a
        // usable size, and the page scrolls: swipe on the keys or the gaps to scroll.
        val rows = (deck.size + 2) / 3
        val scale = maxOf(1f, androidx.compose.ui.platform.LocalDensity.current.fontScale)
        val tile = 74.dp * scale
        val button = 54.dp * scale
        val used = 4.dp + (tile + 10.dp) * rows + button + 10.dp + 8.dp
        val pad = maxOf(maxHeight - used, 216.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            deck.indices.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { i ->
                        DeckTile(deck[i], arranging, Modifier.weight(1f).height(tile), { edit = i }) { if (arranging) edit = i else hub.run(deck[i]) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth().height(button), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("Add key", Modifier.weight(1f), icon = Icons.Rounded.Add) { edit = -1 }
                Action(if (arranging) "Done" else "Edit", Modifier.weight(1f), primary = arranging, icon = if (arranging) Icons.Rounded.Check else Icons.Rounded.Edit) { arranging = !arranging }
            }
            MiniPad(shell, Modifier.fillMaxWidth().height(pad))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckTile(k: DeckKey, editing: Boolean, modifier: Modifier, onLong: () -> Unit, onTap: () -> Unit) {
    val tint = Tints[k.color.coerceIn(Tints.indices)]
    Column(
        modifier.clip(R18).background(tint.copy(alpha = .13f)).combinedClickable(onLongClick = onLong, onClick = onTap).padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(if (editing) Icons.Rounded.Edit else deckIcon(k.type), null, tint = tint, modifier = Modifier.size(23.dp))
        Text(k.label, color = Ink, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeckEditor(hub: Hub, k: DeckKey?, at: Int, count: Int, close: () -> Unit, shift: (Int) -> Unit, save: (DeckKey?) -> Unit) = Page {
    var label by remember { mutableStateOf(k?.label.orEmpty()) }
    var type by remember { mutableStateOf(k?.type ?: "keys") }
    var value by remember { mutableStateOf(k?.value.orEmpty()) }
    var color by remember { mutableIntStateOf(k?.color ?: 0) }
    val types = listOf("keys", "open", "text")
    BackHandler(onBack = close)
    Text(if (k == null) "New key" else "Edit key", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
    OutlinedTextField(label, { label = it.take(24) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = R18)
    // Each kind keeps its own value, so switching back loses nothing.
    val kept = remember { mutableMapOf<String, String>() }
    Segments(listOf("Shortcut", "Open", "Type text"), types.indexOf(type)) { kept[type] = value; type = types[it]; value = kept[type].orEmpty() }
    if (type == "keys") ShortcutEditor(hub, value) { value = it }
    else OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), shape = R18, label = { Text(if (type == "open") "Website, program or file" else "Text to type") })
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Tints.forEachIndexed { i, c ->
            Box(Modifier.size(48.dp).tap(CircleShape, color = Bg) { color = i }.semantics { contentDescription = "Colour ${i + 1}" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c))
                if (i == color) Icon(Icons.Rounded.Check, null, tint = Bg, modifier = Modifier.size(20.dp))
            }
        }
    }
    Action("Save", Modifier.fillMaxWidth(), primary = true, enabled = label.isNotBlank() && value.isNotBlank()) { save(DeckKey(label.trim(), type, value, color)) }
    Action("Cancel", Modifier.fillMaxWidth(), onTap = close)
    if (k != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("Move earlier", Modifier.weight(1f), enabled = at > 0) { shift(-1) }
            Action("Move later", Modifier.weight(1f), enabled = at < count - 1) { shift(1) }
        }
        TextButton({ save(null) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Delete this key", color = Warn, fontSize = 15.sp) }
    }
}

// ---- more

@Composable
fun MorePage(hub: Hub, open: (String) -> Unit) = Page {
    hub.update?.let { v ->
        Row(Modifier.fillMaxWidth().tap(R24, color = Accent) { hub.getUpdate() }.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = OnAccent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("SyncPad $v is out", color = OnAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap to get it from the website", color = OnAccent.copy(alpha = .75f), fontSize = 13.sp)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Feature("Laptop files", "Browse, preview, save", Icons.Rounded.FolderOpen) { open("browse") }
        Feature("Share", "Send and clipboard", Icons.Rounded.SwapVert) { open("files") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Feature("Media", "Playback and power", Icons.Rounded.PlayCircle) { open("remote") }
        Feature("Gamepad", if (hub.usingBt) "Real controller" else "Sends keyboard keys", Icons.Rounded.SportsEsports) { open("gamepad") }
    }
    Card {
        RowItem("Connection", when { hub.usingBt -> "Bluetooth"; hub.status.phase == Phase.CONNECTED -> "Connected over ${hub.status.via}"; else -> "Wi-Fi, USB and Bluetooth" },
            Icons.Rounded.Cable, onTap = { open("connect") })
        RowItem("Settings", "Pointer, scrolling, gestures", Icons.Rounded.Tune, onTap = { open("settings") })
    }
}

@Composable
private fun RowScope.Feature(title: String, note: String, icon: ImageVector, onTap: () -> Unit) = Column(
    Modifier.weight(1f).tap(R24, onTap = onTap).padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Icon(icon, null, tint = Accent, modifier = Modifier.size(32.dp))
    Gap(22)
    Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(note, color = Dim, fontSize = 13.sp)
}

// ---- files and clipboard

@Composable
fun SharePage(shell: Shell) {
    val hub = shell.hub
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) hub.upload(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) hub.upload(it) }
    val net = hub.status.phase == Phase.CONNECTED
    Page {
        if (!net) Row(Modifier.fillMaxWidth().clip(R18).background(Warn.copy(alpha = .13f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.LinkOff, null, tint = Warn, modifier = Modifier.size(20.dp))
            Body("Connect over Wi-Fi or USB to share.", Ink)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SendTile("Photos", Icons.Rounded.PhotoLibrary, net) { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            SendTile("Files", Icons.Rounded.FolderOpen, net) { files.launch("*/*") }
        }
        Card {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Clipboard", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (hub.clipText.isNotEmpty()) Text(if (hub.clipFromLaptop) "From laptop" else "From phone", color = Dim, fontSize = 13.sp)
                }
                Text(hub.clipText.ifEmpty { "Nothing shared yet" }, color = if (hub.clipText.isEmpty()) Dim else Ink, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(R12).background(Bg).padding(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action("Send", Modifier.weight(1f), enabled = net, icon = Icons.Rounded.ArrowUpward) { hub.sendClip() }
                    Action("Get", Modifier.weight(1f), enabled = net, icon = Icons.Rounded.ArrowDownward) { hub.fetchClip() }
                }
            }
            Toggle("Sync automatically", "Your phone's copy is sent when you open SyncPad.", hub.clipSync) { hub.clipSync = it }
        }
        Card {
            Toggle("Phone files", "Lets your laptop browse this phone's storage and add files, while SyncPad is open. It cannot delete anything.", hub.shareFiles) {
                hub.shareFiles = it
                if (it && !hub.filesAllowed) shell.allowFiles()
            }
            if (hub.shareFiles && !hub.filesAllowed) RowItem("Allow file access", "Android needs your permission for this.", Icons.Rounded.Lock, Warn, { shell.allowFiles() })
        }
        if (hub.transfers.isEmpty()) Body("Share from any app and pick SyncPad, or drop files on the SyncPad window on your laptop.", modifier = Modifier.padding(horizontal = 4.dp))
        else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section("Transfers") }
                if (hub.transfers.any { !it.running }) TextButton({ hub.clearFinished() }) { Text("Clear", color = Dim) }
            }
            Card { hub.transfers.forEach { TransferRow(hub, it) } }
            if (hub.transfers.any { it.uri != null }) Body("Received files are in Downloads/SyncPad.", modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun RowScope.SendTile(label: String, icon: ImageVector, enabled: Boolean, onTap: () -> Unit) = Column(
    Modifier.weight(1f).tap(R24, enabled, if (enabled) Accent else S1, onTap).padding(18.dp),
) {
    val ink = if (enabled) OnAccent else Dim
    Icon(icon, null, tint = ink, modifier = Modifier.size(30.dp))
    Gap(24)
    Text("Send", color = ink.copy(alpha = .75f), fontSize = 13.sp)
    Text(label, color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

fun fileSize(n: Long) = when {
    n < 0 -> ""
    n < 1000 -> "$n B"
    n < 1_000_000 -> "${n / 1000} kB"
    n < 1_000_000_000 -> "%.1f MB".format(n / 1e6)
    else -> "%.2f GB".format(n / 1e9)
}

private fun fileIcon(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "svg" -> Icons.Rounded.Image
    "mp4", "mkv", "mov", "webm", "avi" -> Icons.Rounded.Movie
    "mp3", "wav", "flac", "m4a", "ogg" -> Icons.Rounded.MusicNote
    "pdf" -> Icons.Rounded.PictureAsPdf
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    "apk" -> Icons.Rounded.Android
    else -> Icons.Rounded.Description
}

@Composable
private fun TransferRow(hub: Hub, t: Transfer) = Row(
    (if (t.uri != null) Modifier.tap(R18) { hub.open(t) } else Modifier).fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Box(Modifier.size(42.dp).clip(R12).background(S2), contentAlignment = Alignment.Center) {
        Icon(fileIcon(t.name), null, tint = if (t.failed) Warn else Ink, modifier = Modifier.size(22.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(t.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        if (t.running) {
            Bar(if (t.size > 0) t.done.toFloat() / t.size else 0f)
            val left = if (t.rate > 1 && t.size > 0) ((t.size - t.done) / t.rate).toLong() else -1
            Text(when {
                t.done == 0L -> "Starting"
                t.size <= 0 -> fileSize(t.done)
                t.rate <= 1 -> "${fileSize(t.done)} of ${fileSize(t.size)}"
                else -> "${t.done * 100 / t.size}%, ${fileSize(t.rate.toLong())}/s" + when { left >= 90 -> ", ${left / 60} min left"; left > 1 -> ", $left s left"; else -> "" }
            }, color = Dim, fontSize = 13.sp, style = Numbers, maxLines = 1)
        } else Text(listOf(if (t.state == "Saved" && t.uri != null) "Saved, tap to open" else t.state, fileSize(t.size)).filter { it.isNotEmpty() }.joinToString(", "),
            color = if (t.failed) Warn else Dim, fontSize = 13.sp, maxLines = 2, style = Numbers)
    }
    if (t.running) Box(Modifier.size(48.dp).tap(CircleShape) { hub.cancel(t) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Cancel", tint = Dim, modifier = Modifier.size(20.dp)) }
    else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(if (t.failed || t.state == "Cancelled") Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
            tint = if (t.failed) Warn else if (t.state == "Cancelled") Dim else Accent, modifier = Modifier.size(22.dp))
    }
}

// ---- connection

@Composable
fun ConnectPage(shell: Shell) {
    val hub = shell.hub
    Page {
        if (hub.laptops.isNotEmpty()) Card {
            hub.laptops.forEach { l ->
                val here = l.hostId == hub.current
                RowItem(l.name, if (!here) "Tap to use" else when (hub.status.phase) {
                    Phase.CONNECTED -> "Connected over ${hub.status.via}" + if (hub.status.udp) ", low latency" else ""
                    Phase.SEARCHING -> "Searching"
                    Phase.OFF -> "Not connected"
                }, Icons.Rounded.Laptop, if (here && hub.status.phase == Phase.CONNECTED) Accent else Ink, { hub.select(l) }) {
                    TextButton({ hub.forget(l) }) { Text("Forget", color = Dim) }
                }
            }
        }
        if (hub.status.unsteady && !hub.usingBt) Row(Modifier.fillMaxWidth().clip(R18).background(Warn.copy(alpha = .13f)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Wifi, null, tint = Warn, modifier = Modifier.size(20.dp))
            Body("This Wi-Fi keeps pausing, which makes the cursor stutter. A USB cable or Bluetooth will be smoother. So will 5 GHz Wi-Fi, if you have it.", Ink)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("Scan code", Modifier.weight(1f), primary = hub.laptops.isEmpty(), icon = Icons.Rounded.QrCodeScanner) { shell.scan() }
            Action("Paste link", Modifier.weight(1f), icon = Icons.Rounded.ContentPaste) { shell.paste() }
        }
        Section("Connect with")
        Segments(dev.syncpad.app.net.Mode.entries.map { if (it.ordinal == 0) "Auto" else it.label }, hub.mode.ordinal) { pickMode(shell, dev.syncpad.app.net.Mode.entries[it]) }
        Body(modeNote(hub, hub.mode) + ".", modifier = Modifier.padding(horizontal = 4.dp))
        Body("If the Wi-Fi blocks devices from seeing each other, connect the laptop to this phone's hotspot.",
            modifier = Modifier.padding(horizontal = 4.dp))
        Card {
            Toggle("Connect from anywhere", "On another Wi-Fi or mobile data, SyncPad reaches the laptop over the internet. It goes direct when the networks allow, otherwise through a relay that cannot read anything. Pair at home once first.", hub.remote) { hub.remote = it }
        }
        if (hub.status.via.startsWith("Internet")) Body(
            if (hub.status.via.endsWith("relayed")) "Through a relay, so a little slower. It switches to a direct path by itself when it can."
            else "Direct over the internet. Speed depends on both networks.", modifier = Modifier.padding(horizontal = 4.dp))
        Section("Bluetooth")
        Card {
            val b = hub.hid
            RowItem(when (b.phase) { HidPhase.CONNECTED -> b.name; HidPhase.READY -> "Ready to connect"; HidPhase.UNAVAILABLE -> "Not available"; else -> "Bluetooth mouse" },
                when (b.phase) {
                    HidPhase.UNAVAILABLE -> "This phone's Bluetooth cannot act as a mouse."
                    HidPhase.CONNECTED -> "Connected"
                    HidPhase.READY -> "First time: make the phone visible, then add it in the laptop's Bluetooth settings. If it is already listed there, remove it first."
                    else -> "An ordinary Bluetooth mouse and keyboard. Works on computers without SyncPad."
                }, Icons.Rounded.Bluetooth, if (b.phase == HidPhase.CONNECTED) Accent else Ink)
            Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.phase == HidPhase.READY) {
                    Action("Make phone visible", Modifier.fillMaxWidth()) { shell.discoverable() }
                    hub.hidLink.bonded().forEach { d -> Action("Connect to ${hub.hidLink.name(d)}", Modifier.fillMaxWidth()) {
                        if (!hub.hidLink.connect(d)) hub.notice = "Could not start the connection"
                    } }
                }
                if (b.phase == HidPhase.OFF || b.phase == HidPhase.NO_PERMISSION) Action("Turn on", Modifier.fillMaxWidth()) { shell.enableBluetooth() }
            }
        }
    }
}

// ---- settings

@Composable
fun SettingsPage(hub: Hub) = Page {
    Section("Pointer")
    Card {
        Dial("Speed", hub.speed, 0.3f..3f) { hub.speed = it }
        Dial("Acceleration", hub.accel, 0f..2f, "At 0, distance does not depend on how fast you swipe.") { hub.accel = it }
        Dial("Smoothing", hub.smooth, 0f..1f, "Evens out a choppy network. Adds a few milliseconds.") { hub.smooth = it }
        Dial("Air mouse", hub.airSpeed, 0.3f..3f) { hub.airSpeed = it }
        Dial("Bluetooth speed", hub.btSpeed, 0.3f..3f, "Only for Bluetooth. Lower it if Windows adds its own acceleration.") { hub.btSpeed = it }
        Toggle("Game mode", "Raw motion for first-person games. Windows acceleration applies.", hub.gameMode) { hub.gameMode = it }
    }
    Section("Scrolling and taps")
    Card {
        Dial("Scroll speed", hub.scrollSpeed, 0.3f..3f) { hub.scrollSpeed = it }
        Toggle("Natural scrolling", "Content follows your fingers.", hub.natural) { hub.natural = it }
        Toggle("Tap to click", null, hub.tap) { hub.tap = it }
        Toggle("Vibration", null, hub.haptics) { hub.haptics = it }
        Toggle("Volume keys control the laptop", null, hub.volumeKeys) { hub.volumeKeys = it }
        Toggle("Check for updates", "Asks GitHub for the newest version now and then. Nothing else is sent.", hub.checkUpdates) { hub.checkUpdates = it }
    }
    Section("Gestures")
    Card {
        listOf("Tap" to "Click", "Two-finger tap" to "Right click", "Three-finger tap" to "Middle click", "Tap, then drag" to "Select or drag",
            "Two fingers" to "Scroll", "Pinch" to "Zoom", "Three fingers up" to "Task view", "Three fingers down" to "Desktop",
            "Three fingers sideways" to "Switch app", "Four fingers sideways" to "Switch desktop").forEach { (a, b) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp)) {
                Text(a, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(b, color = Dim, fontSize = 15.sp)
            }
        }
    }
    Body("SyncPad ${hub.version}", modifier = Modifier.padding(4.dp))
}
