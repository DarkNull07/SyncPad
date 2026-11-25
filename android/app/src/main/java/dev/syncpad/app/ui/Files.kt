package dev.syncpad.app.ui

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncpad.app.Hub
import dev.syncpad.app.net.Entry
import dev.syncpad.app.net.Folder
import dev.syncpad.app.net.Phase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pictures = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "tif", "tiff")
private val Texts = setOf("txt", "md", "log", "csv", "json", "xml", "yml", "yaml", "ini", "cfg", "toml", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "cs", "go", "rs",
    "html", "css", "sql", "sh", "bat", "ps1", "srt", "gradle", "properties", "tex")

private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
private val Stamp = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/** Small pictures only, so the whole cache stays a few megabytes. */
private val Thumbs = object : LruCache<String, ImageBitmap>(6 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2 / 1024 + 1
}

private val Main = Handler(Looper.getMainLooper())

private enum class Sort(val label: String) { NAME("Name"), DATE("Newest"), SIZE("Largest") }

@Composable
fun FilesPage(hub: Hub) {
    var path by rememberSaveable { mutableStateOf("") }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var roots by remember { mutableStateOf<Pair<List<Entry>, List<Entry>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val picked = remember { mutableStateListOf<String>() }
    var sheet by remember { mutableStateOf<Entry?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    val net = hub.status.phase == Phase.CONNECTED
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) hub.uploadTo(it, path) { tick++ } }

    /** Answers that arrive after moving on are ignored. */
    fun <T> run(work: dev.syncpad.app.net.FsClient.() -> T, then: (T) -> Unit) {
        hub.fs.go(work = work) { r -> Main.post { r.fold(then) { hub.notice = it.message ?: "That did not work" } } }
    }

    LaunchedEffect(path, tick, hidden, net) {
        if (!net) return@LaunchedEffect
        busy = true; error = null
        val want = path
        hub.fs.go(work = { if (want.isEmpty()) roots() else list(want, hidden) }) { r ->
            Main.post {
                if (path != want) return@post
                busy = false
                r.onSuccess { @Suppress("UNCHECKED_CAST") if (it is Folder) folder = it else roots = it as Pair<List<Entry>, List<Entry>> }
                    .onFailure { error = it.message ?: "Could not open this folder" }
            }
        }
    }

    fun go(p: String) { picked.clear(); searching = false; query = ""; if (p != folder?.path) folder = null; path = p }
    fun up() = go(folder?.parent ?: path.trimEnd('\\').substringBeforeLast('\\', "").let { if (it.length == 2) "$it\\" else it })

    BackHandler(path.isNotEmpty() || sheet != null || picked.isNotEmpty() || searching) {
        when {
            sheet != null -> sheet = null
            picked.isNotEmpty() -> picked.clear()
            searching -> { searching = false; query = ""; tick++ }
            else -> up()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (path.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tool(Icons.Rounded.ArrowUpward, "Up one folder") { up() }
                    if (searching) SearchField(query, { query = it }) {
                        val q = query; val at = path
                        busy = true
                        hub.fs.go(work = { search(at, q) }) { r -> Main.post { busy = false; if (path == at) r.fold({ folder = it }) { error = it.message } } }
                    } else Text(path.trimEnd('\\').substringAfterLast('\\').ifEmpty { path }, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                    if (!searching) {
                        Tool(Icons.Rounded.Search, "Search here") { searching = true }
                        Tool(Icons.Rounded.CreateNewFolder, "New folder") { ask = Ask("New folder", "", "Create") { n -> run({ mkdir(path, n) }) { tick++ } } }
                        Tool(Icons.Rounded.UploadFile, "Upload here") { upload.launch("*/*") }
                    } else Tool(Icons.Rounded.Close, "Close search") { searching = false; query = ""; tick++ }
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp)) { if (busy && net) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = S1) }
            when {
                !net -> Notice("Connect over Wi-Fi or USB to see your laptop's files.")
                error != null -> Notice(error!!) { tick++ }
                path.isEmpty() -> roots?.let { Roots(it) { e -> go(e.path) } }
                else -> folder?.let { f ->
                    val list = remember(f, sort) {
                        f.items.sortedWith(compareByDescending<Entry> { it.dir }.then(when (sort) {
                            Sort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                            Sort.DATE -> compareByDescending { it.time }
                            Sort.SIZE -> compareByDescending { it.size }
                        }))
                    }
                    val state = rememberLazyListState()
                    LaunchedEffect(f.path) { state.scrollToItem(0) }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${list.size}${if (f.more) "+" else ""} items", color = Dim, fontSize = 13.sp, style = Numbers, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        TextButton({ hidden = !hidden }) { Text(if (hidden) "Hide hidden" else "Show hidden", color = Dim, fontSize = 13.sp) }
                        TextButton({ sort = Sort.entries[(sort.ordinal + 1) % 3] }) { Text(sort.label, color = Ink, fontSize = 13.sp) }
                    }
                    if (list.isEmpty() && !busy) Notice(if (searching) "Nothing found here." else "This folder is empty.")
                    LazyColumn(Modifier.fillMaxSize(), state, contentPadding = PaddingValues(bottom = if (picked.isEmpty()) 16.dp else 96.dp)) {
                        items(list, { it.path }) { e ->
                            Line(hub, e, e.path in picked, { if (!picked.remove(e.path)) picked.add(e.path) }) {
                                when {
                                    picked.isNotEmpty() -> if (!picked.remove(e.path)) picked.add(e.path)
                                    e.dir -> go(e.path)
                                    else -> sheet = e
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(picked.isNotEmpty(), Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(140)) { it } + fadeIn(), exit = slideOutVertically(tween(110)) { it } + fadeOut()) {
            val chosen = folder?.items.orEmpty().filter { it.path in picked }
            Row(Modifier.padding(12.dp).clip(R24).background(S3).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${chosen.size}", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = Numbers, modifier = Modifier.padding(horizontal = 14.dp))
                Tool(Icons.Rounded.Download, "Save to phone") { chosen.forEach { hub.fetch(it) }; hub.notice = "Saving ${chosen.size} to your phone"; picked.clear() }
                if (chosen.size == 1) Tool(Icons.Rounded.DriveFileRenameOutline, "Rename") { chosen[0].let { e -> ask = Ask("Rename", e.name, "Rename") { n -> run({ rename(e.path, n) }) { picked.clear(); tick++ } } } }
                Tool(Icons.Rounded.DeleteOutline, "Delete") { ask = deleteAsk(chosen) { run({ chosen.forEach { trash(it.path) } }) { picked.clear(); tick++ } } }
                Tool(Icons.Rounded.Close, "Clear selection") { picked.clear() }
            }
        }

        var last by remember { mutableStateOf<Entry?>(null) }
        sheet?.let { last = it }
        AnimatedVisibility(sheet != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) {
            Box(Modifier.fillMaxSize().background(Bg.copy(alpha = .7f)).clickable(remember { MutableInteractionSource() }, null) { sheet = null })
        }
        AnimatedVisibility(sheet != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(170)) { it }, exit = slideOutVertically(tween(120)) { it }) {
            last?.let { e ->
                Sheet(hub, e,
                    open = { run({ open(e.path) }) { note -> hub.notice = note.ifEmpty { "Opened on the laptop" } }; sheet = null },
                    save = { hub.fetch(e); hub.notice = "Saving to your phone"; sheet = null },
                    view = { hub.fetch(e, thenOpen = true); hub.notice = "Getting the file"; sheet = null },
                    reveal = { run({ reveal(e.path) }) { hub.notice = "Shown in File Explorer" }; sheet = null },
                    rename = { sheet = null; ask = Ask("Rename", e.name, "Rename") { n -> run({ rename(e.path, n) }) { tick++ } } },
                    delete = { sheet = null; ask = deleteAsk(listOf(e)) { run({ trash(e.path) }) { tick++ } } })
            }
        }
    }
    ask?.let { a -> AskDialog(a) { ask = null } }
}

private class Ask(val title: String, val start: String?, val yes: String, val note: String = "", val act: (String) -> Unit)

private fun deleteAsk(what: List<Entry>, act: () -> Unit) = Ask(if (what.size == 1) "Delete ${what[0].name}?" else "Delete ${what.size} items?", null, "Delete",
    "Moves to the laptop's Recycle Bin.") { act() }

@Composable
private fun AskDialog(a: Ask, close: () -> Unit) {
    var text by remember { mutableStateOf(a.start.orEmpty()) }
    AlertDialog(onDismissRequest = close, shape = R24, title = { Text(a.title) },
        text = {
            if (a.start == null) Body(a.note) else BasicTextField(text, { text = it.take(200) }, Modifier.fillMaxWidth().clip(R12).background(Bg).padding(14.dp),
                textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Accent), singleLine = true)
        },
        confirmButton = { TextButton({ close(); a.act(text.trim()) }, enabled = a.start == null || text.isNotBlank()) { Text(a.yes, color = if (a.start == null) Warn else Accent) } },
        dismissButton = { TextButton(close) { Text("Cancel", color = Dim) } })
}

@Composable
private fun Tool(icon: ImageVector, label: String, onTap: () -> Unit) = Box(Modifier.size(44.dp).tap(CircleShape, color = androidx.compose.ui.graphics.Color.Transparent, onTap = onTap),
    contentAlignment = Alignment.Center) { Icon(icon, label, tint = Ink, modifier = Modifier.size(22.dp)) }

@Composable
private fun RowScope.SearchField(value: String, set: (String) -> Unit, go: () -> Unit) = Box(Modifier.weight(1f).clip(R12).background(S1).padding(horizontal = 14.dp, vertical = 11.dp)) {
    if (value.isEmpty()) Text("Search this folder", color = Dim, fontSize = 16.sp)
    BasicTextField(value, set, Modifier.fillMaxWidth().semantics { contentDescription = "Search this folder" }, textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Accent), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (value.isNotBlank()) go() }))
}

@Composable
private fun Notice(text: String, retry: (() -> Unit)? = null) = Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(text, color = Dim, fontSize = 15.sp, lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    if (retry != null) { Gap(14); Action("Try again", onTap = retry) }
}

@Composable
private fun Roots(r: Pair<List<Entry>, List<Entry>>, open: (Entry) -> Unit) = Page {
    r.first.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { e ->
                Row(Modifier.weight(1f).tap(R18) { open(e) }.height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(when (e.kind) { "desktop" -> Icons.Rounded.DesktopWindows; "download" -> Icons.Rounded.Download; "document" -> Icons.Rounded.Description
                        "picture" -> Icons.Rounded.Image; "video" -> Icons.Rounded.Movie; "music" -> Icons.Rounded.MusicNote; else -> Icons.Rounded.Folder }, null, tint = Accent, modifier = Modifier.size(24.dp))
                    Text(e.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Section("Drives")
    Card {
        r.second.forEach { d ->
            Column(Modifier.tap(R18) { open(d) }.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${fileSize(d.free)} free", color = Dim, fontSize = 13.sp, style = Numbers)
                }
                if (d.total > 0) Bar((d.total - d.free).toFloat() / d.total)
            }
        }
    }
}

private fun iconFor(e: Entry): ImageVector = if (e.dir) Icons.Rounded.Folder else when (ext(e.name)) {
    in Pictures, "heic", "svg" -> Icons.Rounded.Image
    "mp4", "mkv", "mov", "webm", "avi" -> Icons.Rounded.Movie
    "mp3", "wav", "flac", "m4a", "ogg" -> Icons.Rounded.MusicNote
    "pdf" -> Icons.Rounded.PictureAsPdf
    "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.FolderZip
    "exe", "msi", "bat", "lnk" -> Icons.Rounded.Terminal
    else -> Icons.Rounded.Description
}

/** The picture for [path], from the cache or the laptop. Cancelled if it scrolls away first. */
@Composable
private fun preview(hub: Hub, path: String, width: Int, on: Boolean): State<ImageBitmap?> {
    val key = "$width $path"
    return produceState(Thumbs.get(key), key) {
        if (!on || value != null) return@produceState
        var alive = true
        hub.thumbs.go(wanted = { alive }, work = { thumb(path, width) }) { r ->
            val b = r.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }) }?.asImageBitmap()
            if (b != null) Main.post { if (width < 400) Thumbs.put(key, b); if (alive) value = b }
        }
        awaitDispose { alive = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Line(hub: Hub, e: Entry, picked: Boolean, onLong: () -> Unit, onTap: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(R18).background(if (picked) S2 else Bg).combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 6.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    val pic by preview(hub, e.path, 132, !e.dir && ext(e.name) in Pictures)
    Box(Modifier.size(44.dp).clip(R12).background(S1), contentAlignment = Alignment.Center) {
        val p = pic
        when {
            picked -> Icon(Icons.Rounded.Check, "Selected", tint = Accent, modifier = Modifier.size(24.dp))
            p != null -> Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Icon(iconFor(e), null, tint = if (e.dir) Accent else Ink, modifier = Modifier.size(23.dp))
        }
    }
    Column(Modifier.weight(1f)) {
        Text(e.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        val when_ = if (e.time > 0) Stamp.format(Date(e.time)) else ""
        Text(listOf(if (e.dir) "" else fileSize(e.size), e.inside.ifEmpty { when_ }).filter { it.isNotEmpty() }.joinToString(", "),
            color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = Numbers)
    }
}

@Composable
private fun Sheet(hub: Hub, e: Entry, open: () -> Unit, save: () -> Unit, view: () -> Unit, reveal: () -> Unit, rename: () -> Unit, delete: () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(S1).clickable(remember { MutableInteractionSource() }, null) {}.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    val big by preview(hub, e.path, 1080, ext(e.name) in Pictures)
    val text by produceState<String?>(null, e.path) {
        if (ext(e.name) !in Texts) return@produceState
        var alive = true
        hub.fs.go(wanted = { alive }, work = { text(e.path) }) { r -> Main.post { if (alive) value = r.getOrNull() } }
        awaitDispose { alive = false }
    }
    big?.let { Image(it, e.name, Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(R18).background(Bg), contentScale = ContentScale.Fit) }
    text?.let { Text(it, color = Ink, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(R18).background(Bg).verticalScroll(rememberScrollState()).padding(14.dp)) }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(e.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        Text("${fileSize(e.size)}, ${Stamp.format(Date(e.time))}", color = Dim, fontSize = 14.sp, style = Numbers)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action("Open on laptop", Modifier.weight(1f), primary = true, onTap = open)
        Action("View here", Modifier.weight(1f), onTap = view)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Small("Save", Icons.Rounded.Download, save)
        Small("Show in folder", Icons.Rounded.FolderOpen, reveal)
        Small("Rename", Icons.Rounded.DriveFileRenameOutline, rename)
        Small("Delete", Icons.Rounded.DeleteOutline, delete)
    }
}

@Composable
private fun RowScope.Small(label: String, icon: ImageVector, onTap: () -> Unit) = Column(Modifier.weight(1f).tap(R18, color = S1, onTap = onTap).padding(vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(icon, null, tint = Ink, modifier = Modifier.size(22.dp))
    Text(label, color = Dim, fontSize = 12.sp, maxLines = 1)
}
