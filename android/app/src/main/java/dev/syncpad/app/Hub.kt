package dev.syncpad.app

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.syncpad.app.input.Keys
import dev.syncpad.app.input.PadConfig
import dev.syncpad.app.input.Sink
import dev.syncpad.app.net.Channel
import dev.syncpad.app.net.Entry
import dev.syncpad.app.net.FsClient
import dev.syncpad.app.net.HidLink
import dev.syncpad.app.net.HidPhase
import dev.syncpad.app.net.HidStatus
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.net.Link
import dev.syncpad.app.net.Mode
import dev.syncpad.app.net.Phase
import dev.syncpad.app.net.Status
import dev.syncpad.app.net.Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

class App : Application() {
    lateinit var hub: Hub
    override fun onCreate() {
        super.onCreate()
        hub = Hub(this)
    }
}

class Transfer(val name: String, val size: Long, val up: Boolean, val offer: String = "") {
    var done by mutableStateOf(0L)
    var rate by mutableStateOf(0f)           // bytes per second
    var state by mutableStateOf("")          // "" while running
    var uri by mutableStateOf<Uri?>(null)    // a received file, once it can be opened
    val running get() = state.isEmpty()
    val failed get() = state.startsWith("Failed")
    @Volatile var cancelled = false
    @Volatile var channel: Channel? = null
    var markAt = 0L
    var markBytes = 0L
}

data class DeckKey(val label: String, val type: String, val value: String, val color: Int = 0)    // type: keys, open, text

/** Owns connections and app state. UI state is only written on the main thread. */
class Hub(private val ctx: Context) : Sink {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val files = Executors.newFixedThreadPool(2)
    private val prefs = ctx.getSharedPreferences("syncpad", Context.MODE_PRIVATE)
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ---- observable state
    var status by mutableStateOf(Status(Phase.OFF)); private set
    var hid by mutableStateOf(HidStatus(HidPhase.OFF)); private set
    var laptops by mutableStateOf(loadLaptops()); private set
    var current by mutableStateOf(prefs.getString("current", null)); private set
    var notice by mutableStateOf<String?>(null)
    var pendingPair by mutableStateOf<Laptop?>(null)
    val transfers = mutableStateListOf<Transfer>()
    var clipText by mutableStateOf(""); private set
    var clipFromLaptop by mutableStateOf(false); private set
    var deck by mutableStateOf(loadDeck()); private set
    var recording by mutableStateOf(false); private set
    var filesAllowed by mutableStateOf(false); private set
    var recorded by mutableStateOf<List<String>?>(null)

    // ---- how to connect
    private val modeState = mutableStateOf(runCatching { Mode.valueOf(prefs.getString("mode", null)!!) }
        .getOrDefault(if (prefs.getBoolean("bt", false)) Mode.BT else Mode.AUTO))

    var mode: Mode
        get() = modeState.value
        set(v) {
            releaseHeld()                       // nothing may stay pressed on the transport being left
            modeState.value = v
            prefs.edit().putString("mode", v.name).apply()
            link.mode = v
            if (v == Mode.BT) hidLink.start()
        }

    // ---- preferences
    var speed: Float by pref("speed", 1f)
    var accel: Float by pref("accel", 1f)
    var scrollSpeed: Float by pref("scroll", 1f)
    var airSpeed: Float by pref("air", 1f)
    var smooth: Float by pref("smooth", 0f) { pushOptions() }
    var natural: Boolean by pref("natural", true)
    var tap: Boolean by pref("tap", true)
    var haptics: Boolean by pref("haptics", true)
    var gameMode: Boolean by pref("raw", false) { pushOptions() }
    var clipSync: Boolean by pref("clip", false) { pushOptions() }
    var volumeKeys: Boolean by pref("vol", true)
    var hidOn: Boolean by pref("hid", false)
    var padHint: Boolean by pref("hint", true)
    var shareFiles: Boolean by pref("phonefiles", false)
    var btSpeed: Float by pref("btspeed", 1f) { hidLink.speed = btSpeed }
    var toldUnsteady = false

    val padConfig get() = PadConfig(speed, accel, scrollSpeed, natural, tap)

    private fun <T : Any> pref(key: String, default: T, after: () -> Unit = {}): kotlin.properties.ReadWriteProperty<Any?, T> = object : kotlin.properties.ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        private val state = mutableStateOf(when (default) {
            is Boolean -> prefs.getBoolean(key, default)
            is Float -> prefs.getFloat(key, default)
            else -> error("type")
        } as T)

        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            state.value = value
            prefs.edit().apply { if (value is Boolean) putBoolean(key, value) else putFloat(key, value as Float) }.apply()
            after()
        }
    }

    // ---- transports
    val link = Link(
        model = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" "),
        bindTcp = { s, a -> networkFor(a)?.bindSocket(s) },
        bindUdp = { s, a -> networkFor(a)?.bindSocket(s) },
        extraHosts = ::gateways,
        urgent = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) },
    )
    val fs = FsClient { link.openChannel(Wire.FILE) }
    val thumbs = FsClient { link.openChannel(Wire.FILE) }       // its own channel, so pictures never hold up browsing
    val phoneFiles = PhoneFiles(ctx, { shareFiles }) { link.openChannel(Wire.FILE) }
    val hidLink: HidLink = HidLink(ctx) { s -> main.post { hid = s } }.also { h ->
        h.lastHost = prefs.getString("hidHost", null)
        h.speed = btSpeed
        h.onHost = { prefs.edit().putString("hidHost", it).apply() }
    }

    init {
        link.mode = mode
        link.onStatus = { s -> main.post {
            val joined = s.phase == Phase.CONNECTED && status.phase != Phase.CONNECTED
            status = s
            if (s.phase != Phase.CONNECTED) { recording = false; toldUnsteady = false }
            if (joined) offerClip()
        } }
        link.onMessage = ::message
        link.onIps = { l -> main.post { saveLaptop(l, makeCurrent = false) } }
        pushOptions()
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = link.nudge()
                })
        }
    }

    private fun pushOptions() {
        link.options = JSONObject().put("t", "opt").put("pointer", if (gameMode) "raw" else "precise")
            .put("smooth", smooth.toDouble()).put("clipsync", clipSync)
    }

    /** The network whose subnet holds [a], so a Wi-Fi without internet is still used. */
    @Suppress("DEPRECATION")
    private fun networkFor(a: InetAddress): Network? = cm.allNetworks.firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstOrNull false
        cm.getLinkProperties(n)?.routes?.any { !it.isDefaultRoute && it.matches(a) } == true
    }

    /** When the laptop runs the hotspot it is this phone's gateway. */
    @Suppress("DEPRECATION")
    private fun gateways(): List<String> = cm.allNetworks.flatMap { n ->
        if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) emptyList()
        else cm.getLinkProperties(n)?.routes.orEmpty().filter { it.isDefaultRoute && it.gateway is Inet4Address }
            .mapNotNull { it.gateway?.hostAddress }
    }.distinct()

    @Suppress("DEPRECATION")
    private val wifiLock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createWifiLock(
        if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SyncPad")
        ?.apply { setReferenceCounted(false) }

    private var front = false

    fun foreground() {
        front = true
        filesAllowed = runCatching { phoneFiles.allowed() }.getOrDefault(false)     // may have changed in Settings
        runCatching { wifiLock?.acquire() }       // stops Wi-Fi power saving, the main source of jitter
        laptops.firstOrNull { it.hostId == current }?.let { l -> io.execute { link.start(l) } }
        link.nudge()
        if (hidOn || mode == Mode.BT) hidLink.start()
    }

    fun background() {
        front = false
        releaseHeld()
        runCatching { wifiLock?.release() }
        if (transfers.none { it.running }) io.execute { link.stop() }
        hidLink.stop()
    }

    // ---- laptops
    private fun loadLaptops(): List<Laptop> = runCatching {
        val a = JSONArray(prefs.getString("laptops", "[]"))
        List(a.length()) { i ->
            a.getJSONObject(i).let { o ->
                Laptop(o.getString("h"), o.getString("d"), o.getString("k"), o.getString("n"), o.getInt("p"), Link.strings(o.getJSONArray("a")))
            }
        }
    }.getOrDefault(emptyList())

    private fun store(list: List<Laptop>) {
        laptops = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("h", it.hostId).put("d", it.deviceId).put("k", it.key).put("n", it.name).put("p", it.port).put("a", JSONArray(it.ips))) }
        prefs.edit().putString("laptops", a.toString()).apply()
    }

    private fun saveLaptop(l: Laptop, makeCurrent: Boolean) {
        store(listOf(l) + laptops.filter { it.hostId != l.hostId })
        if (makeCurrent) select(l)
    }

    fun select(l: Laptop) {
        current = l.hostId
        prefs.edit().putString("current", l.hostId).apply()
        io.execute { link.stop(); link.start(l) }
    }

    fun pair(l: Laptop) { pendingPair = null; saveLaptop(l, makeCurrent = true) }

    fun forget(l: Laptop) {
        store(laptops.filter { it.hostId != l.hostId })
        if (current == l.hostId) { current = null; io.execute { link.stop() } }
    }

    // ---- Sink: goes to whichever transport is in use
    /** Automatic falls back to Bluetooth. A mode picked by hand is kept to. */
    val usingBt get() = hidLink.connected && (mode == Mode.BT || mode == Mode.AUTO && status.phase != Phase.CONNECTED)
    /** Files, screen and clipboard work, whatever is moving the pointer. */
    val online get() = status.phase == Phase.CONNECTED
    val connected get() = usingBt || status.phase == Phase.CONNECTED
    private val heldKeys = HashSet<String>()
    private val heldButtons = HashSet<Int>()

    override fun move(dx: Float, dy: Float) = if (usingBt) hidLink.move(dx, dy) else link.move(dx, dy)
    override fun scroll(dx: Float, dy: Float) = if (usingBt) hidLink.scroll(dx, dy) else link.scroll(dx, dy)

    private var zoomAcc = 0f
    override fun zoom(units: Float) {
        if (usingBt) return hidLink.zoom(units)
        zoomAcc += units
        val n = zoomAcc.toInt()
        if (n == 0) return
        zoomAcc -= n
        link.json(JSONObject().put("t", "zoom").put("d", n))
    }

    override fun button(b: Int, down: Boolean) {
        if (down) heldButtons.add(b) else heldButtons.remove(b)
        if (usingBt) hidLink.button(b, down) else link.button(b, down)
    }

    override fun key(k: String, down: Boolean) {
        if (down) heldKeys.add(k) else heldKeys.remove(k)
        if (usingBt) hidLink.key(k, down) else link.json(JSONObject().put("t", "key").put("k", k).put("d", if (down) 1 else 0))
    }

    override fun combo(vararg keys: String) =
        if (usingBt) hidLink.combo(*keys) else link.json(JSONObject().put("t", "combo").put("keys", JSONArray(keys.toList())))

    override fun text(s: String) = if (usingBt) hidLink.text(s) else link.json(JSONObject().put("t", "text").put("s", s))

    override fun pad(b: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) =
        if (usingBt) hidLink.pad(b, lx, ly, rx, ry, lt, rt)
        else link.json(JSONObject().put("t", "pad").put("b", b).put("lx", lx.toDouble()).put("ly", ly.toDouble())
            .put("rx", rx.toDouble()).put("ry", ry.toDouble()).put("lt", lt.toDouble()).put("rt", rt.toDouble()))

    fun releaseHeld() {
        heldButtons.toList().forEach { button(it, false) }
        heldKeys.toList().forEach { key(it, false) }
    }

    fun absolute(x: Float, y: Float, monitor: Int) = link.json(JSONObject().put("t", "abs").put("m", monitor)
        .put("x", (x * 65535).toInt().coerceIn(0, 65535)).put("y", (y * 65535).toInt().coerceIn(0, 65535)))

    fun system(a: String) = link.json(JSONObject().put("t", "sys").put("a", a))
    fun openOnLaptop(v: String) = link.json(JSONObject().put("t", "open").put("v", v))

    // ---- messages from the laptop
    private fun message(m: JSONObject) {
        when (m.optString("t")) {
            "clip" -> main.post { receiveClip(m.optString("s"), m.optBoolean("asked")) }
            "offer" -> main.post { download(m.getString("id"), m.getString("name"), m.getLong("size")) }
            "notice" -> main.post { notice = m.optString("s") }
            "recorded" -> main.post {
                recording = false
                if (m.has("keys")) recorded = Link.strings(m.getJSONArray("keys")) else if (!cancelledRecording) notice = m.optString("err")
                cancelledRecording = false
            }
            "ask" -> files.execute { phoneFiles.handle(m) }
        }
    }

    // ---- recording a shortcut on the laptop
    private var cancelledRecording = false

    fun record(on: Boolean) {
        cancelledRecording = !on
        recording = on
        link.json(JSONObject().put("t", if (on) "record" else "recordstop"))
    }

    // ---- clipboard
    private var lastClip: String? = null
    private var lastClipStamp = 0L

    private fun receiveClip(s: String, asked: Boolean) {
        if (s.isEmpty()) { if (asked) notice = "The laptop clipboard has no text"; return }
        lastClip = s
        clipText = s.take(400); clipFromLaptop = true
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("SyncPad", s)) }
        if (asked) notice = "Copied from laptop"
    }

    /** Android only lets the focused app read the clipboard, so this runs when SyncPad comes to front. */
    fun offerClip() {
        if (!clipSync || status.phase != Phase.CONNECTED) return
        val d = clipboard.primaryClipDescription ?: return
        if (d.timestamp == lastClipStamp) return
        if (Build.VERSION.SDK_INT >= 33 && d.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        if (d.label == "SyncPad") return
        if (sendClip(auto = true)) lastClipStamp = d.timestamp
    }

    fun sendClip(auto: Boolean = false): Boolean {
        val s = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull().orEmpty()
        if (s.isEmpty()) { if (!auto) notice = "Nothing to send: the clipboard is empty"; return false }
        if (auto && s == lastClip) return true
        lastClip = s
        clipText = s.take(400); clipFromLaptop = false
        link.json(JSONObject().put("t", "clip").put("s", s.take(12_000)))
        if (!auto) notice = "Sent to laptop"
        return true
    }

    fun fetchClip() = link.json(JSONObject().put("t", "clipget"))

    // ---- files
    fun upload(uris: List<Uri>) = send(uris, { JSONObject().put("t", "put") })

    /** Into a chosen laptop folder. [after] runs once each file is there. */
    fun uploadTo(uris: List<Uri>, folder: String, after: () -> Unit) = send(uris, { FsClient.op("put", folder).put("t", "fs") }, after)

    private fun send(uris: List<Uri>, header: () -> JSONObject, after: () -> Unit = {}) {
        uris.forEach { uri ->
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
            val t = Transfer(name, size, up = true)
            transfers.add(0, t)
            files.execute {
                finish(t, runCatching {
                    val src = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read this file")
                    src.use { send(t, header(), it) }
                })
                main.post { if (t.state == "Sent") after() }
            }
        }
    }

    fun cancel(t: Transfer) { t.cancelled = true; t.channel?.close() }

    fun clearFinished() { transfers.removeAll { !it.running } }

    fun open(t: Transfer) {
        val u = t.uri ?: return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, ctx.contentResolver.getType(u) ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { notice = "No app can open this file" }
    }

    private fun channel(t: Transfer): Channel {
        repeat(40) {                    // a share can arrive before the link is up
            check(!t.cancelled) { "cancelled" }
            link.openChannel(Wire.FILE)?.let { t.channel = it; if (t.cancelled) it.close(); return it }
            Thread.sleep(250)
        }
        error("Laptop not connected")
    }

    private fun send(t: Transfer, header: JSONObject, src: InputStream) {
        val ch = channel(t)
        try {
            ch.send(header.put("name", t.name).put("size", t.size).toString().toByteArray())
            JSONObject(String(ch.recv())).let { if (it.optString("t") != "go") error(it.optString("s", "The laptop refused it")) }
            val buf = ByteArray(256 * 1024 + 1)
            buf[0] = Wire.DATA
            var total = 0L
            while (true) {
                val n = src.read(buf, 1, buf.size - 1)
                if (n < 0) break
                if (n == 0) continue
                ch.send(buf, 0, n + 1)
                total += n
                progress(t, total)
            }
            if (t.size < 0) ch.send("{}".toByteArray())
            else if (total != t.size) error("The file changed while sending")
            JSONObject(String(ch.recv())).getString("name")
        } finally { ch.close() }
    }

    private fun download(id: String, name: String, size: Long) {
        val safe = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim('.', ' ').ifEmpty { "file" }.take(120)
        // The laptop repeats an offer until it is saved; one copy at a time is enough.
        if (transfers.any { it.offer == id && (it.running || it.uri != null || it.cancelled) }) return
        receive(Transfer(safe, size, up = false, offer = id), JSONObject().put("t", "get").put("id", id), thenOpen = false)
    }

    /** A laptop file or, as a zip, a whole folder. */
    fun fetch(e: Entry, thenOpen: Boolean = false) {
        val name = PhoneFiles.clean(if (e.dir) e.name + ".zip" else e.name)
        receive(Transfer(name, if (e.dir) -1 else e.size, up = false), FsClient.op("get", e.path).put("t", "fs"), thenOpen)
    }

    private fun receive(t: Transfer, request: JSONObject, thenOpen: Boolean) {
        val safe = t.name
        transfers.add(0, t)
        files.execute {
            finish(t, runCatching {
                val ch = channel(t)
                try {
                    ch.send(request.toString().toByteArray())
                    val first = JSONObject(String(ch.recv()))
                    if (first.optString("t") != "go") error(first.optString("s", "The laptop refused it"))
                    var expect = first.getLong("size")
                    val uri = saveTo(safe) { out ->
                        var total = 0L
                        while (true) {
                            val d = ch.recv()
                            if (d.isEmpty() || d[0] != Wire.DATA) {
                                if (expect < 0) expect = JSONObject(String(d)).getLong("size")
                                break
                            }
                            out.write(d, 1, d.size - 1)
                            total += d.size - 1
                            progress(t, total)
                        }
                        check(total == expect) { "the connection dropped" }
                    }
                    runCatching { ch.send("{\"t\":\"saved\"}".toByteArray()) }
                    main.post { t.uri = uri; if (thenOpen) open(t) }
                } finally { ch.close() }
            })
        }
    }

    private fun saveTo(name: String, write: (OutputStream) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SyncPad")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Cannot write to Downloads")
            try {
                cr.openOutputStream(uri)!!.use(write)
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Throwable) {
                cr.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            var f = File(dir, name)
            var i = 1
            while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.', "")}")
            try { f.outputStream().use(write) } catch (e: Throwable) { f.delete(); throw e }
            return null
        }
    }

    /** Runs on the transfer's own thread; only the result is posted. */
    private fun progress(t: Transfer, n: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (t.markAt == 0L) { t.markAt = now; t.markBytes = n; return }
        val dt = now - t.markAt
        if (dt < 120 && n < t.size) return
        val r = (n - t.markBytes) * 1000f / dt.coerceAtLeast(1)
        t.markAt = now; t.markBytes = n
        main.post { t.done = n; t.rate = if (t.rate == 0f) r else t.rate * .7f + r * .3f }
    }

    private fun finish(t: Transfer, r: Result<*>) = main.post {
        t.channel = null
        t.state = when {
            r.isSuccess -> if (t.up) "Sent" else "Saved"
            t.cancelled -> "Cancelled"
            else -> "Failed: " + (r.exceptionOrNull()?.message ?: "connection lost")
        }
        if (!front && transfers.none { it.running }) io.execute { if (!front) link.stop() }
    }

    // ---- deck
    private fun loadDeck(): List<DeckKey> = runCatching {
        val a = JSONArray(prefs.getString("deck", null))
        List(a.length()) { i -> a.getJSONObject(i).let { DeckKey(it.getString("l"), it.getString("t"), it.getString("v"), it.optInt("c")) } }
    }.getOrDefault(listOf(
        DeckKey("Screenshot", "keys", "win+shift+s", 0), DeckKey("Task view", "keys", "win+tab", 0),
        DeckKey("Task Manager", "keys", "ctrl+shift+esc", 0), DeckKey("Files", "keys", "win+e", 1),
        DeckKey("Emoji", "keys", "win+.", 1), DeckKey("Clipboard", "keys", "win+v", 1),
        DeckKey("YouTube", "open", "https://youtube.com", 2), DeckKey("Calculator", "open", "calc.exe", 3),
        DeckKey("Settings", "open", "ms-settings:", 3)))

    fun saveDeck(d: List<DeckKey>) {
        deck = d
        val a = JSONArray()
        d.forEach { a.put(JSONObject().put("l", it.label).put("t", it.type).put("v", it.value).put("c", it.color)) }
        prefs.edit().putString("deck", a.toString()).apply()
    }

    fun run(k: DeckKey) = when (k.type) {
        "open" -> if (!online) notice = "Opening things needs Wi-Fi or USB" else openOnLaptop(k.value)
        "text" -> text(k.value)
        else -> shortcut(k.value)
    }

    /** Steps are spaced out a little: programs need a moment to react to each one. */
    fun shortcut(value: String) = Keys.parse(value).forEachIndexed { i, step ->
        if (i == 0) combo(*step.toTypedArray()) else main.postDelayed({ combo(*step.toTypedArray()) }, 110L * i).let { }
    }
}
