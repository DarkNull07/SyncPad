package dev.syncpad.app.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The newest SyncPad on GitHub, compared with this one. Pure JVM. */
object Updates {
    const val SITE = "https://syncpad-dark.vercel.app"
    private const val LATEST = "https://api.github.com/repos/DarkNull07/SyncPad/releases/latest"

    fun parse(v: String): List<Int>? = v.trim().removePrefix("v").split('.').take(3).map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    fun newer(latest: String, current: String): Boolean {
        val a = parse(latest) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The latest release's version, or null. Blocking. */
    fun latest(current: String): String? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "SyncPad/$current")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.inputStream.use { JSONObject(it.reader().readText()) }.let { o ->
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) null else o.optString("tag_name").removePrefix("v").ifEmpty { null }
        }
    }.getOrNull()
}
