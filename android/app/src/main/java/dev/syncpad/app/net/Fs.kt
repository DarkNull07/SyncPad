package dev.syncpad.app.net

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors

/** The laptop said no; the message is for the person. */
class Refused(message: String) : Exception(message)

data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val time: Long, val kind: String = "", val inside: String = "", val total: Long = 0, val free: Long = 0)

data class Folder(val path: String, val parent: String, val items: List<Entry>, val more: Boolean)

/** Laptop files over one reused channel. Calls are queued and answered off the main thread. */
class FsClient(private val open: () -> Channel?) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "syncpad-fs").apply { isDaemon = true } }
    private var ch: Channel? = null
    private var usedAt = 0L

    private fun channel(): Channel {
        // The laptop drops idle channels after two minutes; do not find that out mid-request.
        if (System.nanoTime() - usedAt > 60_000_000_000L) drop()
        return ch ?: (open() ?: throw IOException("Laptop not connected")).also { ch = it }
    }

    private fun drop() { ch?.close(); ch = null }

    private fun once(req: ByteArray): ByteArray {
        val c = channel()
        try {
            c.send(req)
            return c.recv().also { usedAt = System.nanoTime() }
        } catch (e: IOException) {
            drop()
            throw e
        }
    }

    /** Blocking. Only [safe] requests are repeated on a fresh channel: the first try may have been carried out. */
    fun ask(req: JSONObject, safe: Boolean = true): ByteArray {
        val bytes = req.put("t", "fs").toString().toByteArray()
        val r = try { once(bytes) } catch (e: IOException) { if (safe) once(bytes) else throw e }
        if (r.isNotEmpty() && r[0] == '{'.code.toByte()) {
            val m = JSONObject(String(r))
            if (m.optString("t") == "err") throw Refused(m.optString("s"))
        }
        return r
    }

    fun json(req: JSONObject, safe: Boolean = true) = JSONObject(String(ask(req, safe)))

    /** [wanted] is checked when the turn comes, so scrolled-away work costs nothing. */
    fun <T> go(wanted: () -> Boolean = { true }, work: FsClient.() -> T, done: (Result<T>) -> Unit) {
        exec.execute { if (wanted()) done(runCatching { work() }) }
    }

    fun close() { exec.execute { drop() } }

    // ---- requests
    fun roots(): Pair<List<Entry>, List<Entry>> {
        val m = json(op("roots"))
        fun read(key: String) = m.getJSONArray(key).let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { Entry(it.getString("n"), it.getString("p"), true, 0, 0, it.optString("k"), total = it.optLong("total"), free = it.optLong("free")) } }
        }
        return read("folders") to read("drives")
    }

    fun list(path: String, hidden: Boolean) = folder(json(op("list", path).put("hidden", hidden)))
    fun search(path: String, q: String) = folder(json(op("search", path).put("q", q)))
    fun thumb(path: String, w: Int): ByteArray = ask(op("thumb", path).put("w", w)).let { it.copyOfRange(1, it.size) }
    fun text(path: String): String = json(op("text", path)).let { it.getString("s") + if (it.optBoolean("more")) "\n…" else "" }
    fun open(path: String): String = json(op("open", path), false).optString("note")
    fun reveal(path: String) { json(op("reveal", path), false) }
    fun mkdir(path: String, name: String) { json(op("mkdir", path).put("name", name), false) }
    fun rename(path: String, name: String) { json(op("rename", path).put("name", name), false) }
    fun trash(path: String) { json(op("trash", path), false) }

    private fun folder(m: JSONObject): Folder {
        val base = m.getString("path")
        val a = m.getJSONArray("items")
        return Folder(base, m.optString("parent"), List(a.length()) { i ->
            a.getJSONObject(i).let {
                val at = it.optString("in").ifEmpty { base }
                Entry(it.getString("n"), join(at, it.getString("n")), it.optBoolean("d"), it.optLong("s"), it.optLong("m") * 1000, inside = it.optString("in"))
            }
        }, m.optBoolean("more"))
    }

    companion object {
        fun op(name: String, path: String? = null): JSONObject = JSONObject().put("op", name).also { if (path != null) it.put("path", path) }
        fun join(folder: String, name: String) = folder.trimEnd('\\') + "\\" + name
    }
}
