package dev.syncpad.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import dev.syncpad.app.net.Channel
import dev.syncpad.app.net.Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Answers the laptop's requests for this phone's files. Off until the owner turns
 * it on, limited to shared storage, and able to add files but not delete any.
 */
class PhoneFiles(private val ctx: Context, private val on: () -> Boolean, private val open: () -> Channel?) {
    fun allowed() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    class No(message: String) : Exception(message)

    /** Name shown to the person, and the folder. */
    fun roots(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        out += "Internal storage" to Environment.getExternalStorageDirectory()
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            sm.storageVolumes.filter { !it.isPrimary }.forEach { v -> v.directory?.let { out += v.getDescription(ctx) to it } }
        }
        return out
    }

    fun handle(m: JSONObject) {
        val rid = m.optString("rid")
        val ch = open() ?: return
        try {
            try {
                if (!on()) throw No("Turn on Phone files on your phone: SyncPad, More, Share")
                if (!allowed()) throw No("Allow SyncPad to access files on your phone: SyncPad, More, Share")
                answer(ch, rid, m)
            } catch (e: No) {
                ch.send(JSONObject().put("t", "reply").put("rid", rid).put("err", e.message).toString().toByteArray())
            }
        } catch (_: Exception) {
            // The laptop sees the channel close and reports it.
        } finally {
            ch.close()
        }
    }

    private fun answer(ch: Channel, rid: String, m: JSONObject) {
        val path = m.optString("path")
        val head = JSONObject().put("t", "reply").put("rid", rid)
        when (m.optString("op")) {
            "list" -> ch.send(list(path, roots(), head).toString().toByteArray())
            "get" -> {
                val f = resolve(path, roots())
                if (!f.isFile) throw No("That file no longer exists")
                f.inputStream().use { src ->
                    val size = f.length()
                    ch.send(head.put("size", size).toString().toByteArray())
                    val buf = ByteArray(256 * 1024 + 1)
                    buf[0] = Wire.DATA
                    var left = size
                    while (left > 0) {
                        val n = src.read(buf, 1, minOf(left, buf.size - 1L).toInt())
                        if (n <= 0) return
                        ch.send(buf, 0, n + 1)
                        left -= n
                    }
                }
                ch.send("{\"t\":\"done\"}".toByteArray())
            }
            "put" -> {
                val dir = resolve(path, roots())
                if (!dir.isDirectory) throw No("That folder no longer exists")
                val part = File(dir, ".syncpad-$rid.part")
                try {
                    var got = 0L
                    part.outputStream().use { out ->
                        ch.send(head.toString().toByteArray())
                        while (true) {
                            val d = ch.recv()
                            if (d.isEmpty() || d[0] != Wire.DATA) break
                            out.write(d, 1, d.size - 1)
                            got += d.size - 1
                        }
                    }
                    if (got != m.optLong("size", -1)) return
                    val to = free(dir, clean(m.optString("name")))
                    if (!part.renameTo(to)) return
                    MediaScannerConnection.scanFile(ctx, arrayOf(to.path), null, null)
                    ch.send(JSONObject().put("t", "saved").put("name", to.name).toString().toByteArray())
                } finally {
                    part.delete()
                }
            }
            else -> throw No("This phone's SyncPad is too old for that")
        }
    }

    companion object {
        fun clean(name: String) = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim('.', ' ').take(120).ifEmpty { "file" }

        fun free(dir: File, name: String): File {
            var f = File(dir, name)
            var i = 1
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            while (f.exists()) f = File(dir, "$stem (${i++})$ext")
            return f
        }

        /** Canonical, so neither ".." nor a symlink can leave shared storage. */
        fun resolve(path: String, roots: List<Pair<String, File>>): File {
            if ('\u0000' in path) throw No("That folder no longer exists")
            val f = File(path).canonicalFile
            if (roots.none { (_, r) -> r.canonicalFile.let { f == it || f.path.startsWith(it.path + File.separator) } }) throw No("That folder is not shared")
            return f
        }

        fun list(path: String, roots: List<Pair<String, File>>, head: JSONObject): JSONObject {
            val items = JSONArray()
            if (path.isEmpty()) {
                roots.forEach { (n, f) -> items.put(JSONObject().put("n", n).put("d", true).put("p", f.path)) }
                return head.put("items", items)
            }
            val dir = resolve(path, roots)
            val files = dir.listFiles() ?: throw No("Android does not allow access to that folder")
            files.asSequence().filter { !it.name.startsWith(".") }.take(4000).forEach { f ->
                val d = f.isDirectory
                items.put(JSONObject().put("n", f.name).put("d", d).put("s", if (d) 0 else f.length()).put("m", f.lastModified() / 1000).put("p", f.path))
            }
            val root = roots.firstOrNull { it.second.canonicalFile == dir }
            return head.put("items", items).put("name", root?.first ?: dir.name).put("parent", if (root != null) "" else dir.parent)
        }
    }
}
