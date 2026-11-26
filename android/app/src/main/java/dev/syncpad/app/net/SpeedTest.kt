package dev.syncpad.app.net

import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Internet speed, measured the way speed.cloudflare.com does, against Cloudflare's nearest server.
 *
 * Ping is the middle of fifteen tiny requests, with Cloudflare's own processing time taken out.
 * Download and upload each run six connections at once, ignore the first moments while they
 * speed up, and stop as soon as the rate holds steady (at most twelve seconds). Loaded ping is
 * measured while they run: it shows how much a busy connection delays everything else.
 * Pure JVM, so it can be tested on a computer.
 */
class SpeedTest(private val base: String = "https://speed.cloudflare.com") {
    data class State(
        val phase: String = "",            // "", "ping", "download", "upload", "done", "failed"
        val ping: Int? = null, val jitter: Int? = null,
        val down: Double? = null, val up: Double? = null,     // Mbit/s
        val live: Double = 0.0, val loaded: Int? = null,
        val usedMb: Double = 0.0, val server: String = "", val error: String = "",
    )

    @Volatile var state = State(); private set
    @Volatile var onChange: (State) -> Unit = {}
    private val stop = AtomicBoolean()
    private val used = AtomicLong()

    private fun set(s: State) { state = s; onChange(s) }

    fun cancel() = stop.set(true)

    fun run() {
        stop.set(false); used.set(0)
        set(State(phase = "ping"))
        try {
            val pings = Pinger().use { p -> p.once(); (1..15).mapNotNull { if (stop.get()) null else runCatching { p.once() }.getOrNull() } }
            if (pings.isEmpty()) throw java.io.IOException("No answer from the test server")
            val sorted = pings.sorted()
            val jitter = pings.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().takeIf { !it.isNaN() } ?: 0.0
            set(state.copy(ping = Math.round(sorted[sorted.size / 2]).toInt(), jitter = Math.round(jitter).toInt(), phase = "download"))
            val (down, loadedDown) = measure(download = true)
            set(state.copy(down = down, phase = "upload", live = 0.0))
            val (up, loadedUp) = measure(download = false)
            val loaded = listOf(loadedDown, loadedUp).filterNotNull().maxOrNull()
            set(state.copy(up = up, loaded = loaded, phase = if (stop.get()) "" else "done", live = 0.0))
        } catch (e: Exception) {
            set(state.copy(phase = "failed", error = e.message ?: "The test did not finish"))
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 10000; useCaches = false
        setRequestProperty("User-Agent", "SyncPad")
    }

    /**
     * Round trips over one kept-open connection, so each is the network's time and nothing else:
     * the connection and its encryption are set up once, before measuring.
     */
    private inner class Pinger : AutoCloseable {
        private val host = URL(base).host
        private val sock = (javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, 443) as javax.net.ssl.SSLSocket).apply { soTimeout = 8000; tcpNoDelay = true; startHandshake() }
        private val ins = java.io.BufferedInputStream(sock.inputStream)
        private val req = "GET /__down?bytes=0 HTTP/1.1\r\nHost: $host\r\nUser-Agent: SyncPad\r\n\r\n".toByteArray()

        /** Milliseconds, minus the time Cloudflare says it spent. */
        fun once(): Double {
            val t = System.nanoTime()
            sock.outputStream.write(req); sock.outputStream.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val b = ins.read(); if (b < 0) throw java.io.IOException("closed"); head.append(b.toChar()) }
            val ms = (System.nanoTime() - t) / 1e6
            val lines = head.lines()
            val len = lines.firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            repeat(len) { ins.read() }
            if (state.server.isEmpty()) lines.firstOrNull { it.startsWith("cf-ray:", true) }?.substringAfterLast('-', "")?.trim()?.takeIf { it.length == 3 }?.let { state = state.copy(server = it) }
            // Cloudflare reports its own time in parts; all of it is theirs, not the network's.
            val server = lines.filter { it.startsWith("server-timing:", true) }.sumOf { l -> Regex("""dur=([\d.]+)""").findAll(l).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 } }
            return maxOf(0.1, ms - server)
        }

        override fun close() { runCatching { sock.close() } }
    }

    /** Mbit/s over the steady part, and the highest ping seen meanwhile. */
    private fun measure(download: Boolean): Pair<Double?, Int?> {
        val bytes = AtomicLong()
        val done = AtomicBoolean()
        val limited = AtomicBoolean()
        val block = ByteArray(65536).also { SecureRandom().nextBytes(it) }      // random, so nothing compresses it
        val cap = if (download) 400_000_000L else 150_000_000L
        val workers = List(6) {
            thread(isDaemon = true, name = "syncpad-speed") {
                while (!done.get() && !stop.get()) runCatching {
                    if (download) {
                        val c = open("$base/__down?bytes=25000000")
                        if (c.responseCode == 429) { limited.set(true); done.set(true); return@runCatching }
                        c.inputStream.use { s ->
                            val buf = ByteArray(65536)
                            while (!done.get() && !stop.get()) { val n = s.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) }
                        }
                        c.disconnect()
                    } else {
                        val size = 4_000_000
                        val c = open("$base/__up")
                        c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(size)
                        c.setRequestProperty("Content-Type", "application/octet-stream")
                        c.outputStream.use { o ->
                            var left = size
                            while (left > 0 && !done.get() && !stop.get()) { val n = minOf(left, block.size); o.write(block, 0, n); left -= n; bytes.addAndGet(n.toLong()) }
                        }
                        if (!done.get()) { if (c.responseCode == 429) { limited.set(true); done.set(true) } else c.inputStream.use { it.readBytes() } }
                        c.disconnect()
                    }
                }.onFailure { if (!done.get()) Thread.sleep(200) }
            }
        }
        val loaded = mutableListOf<Double>()
        val pinger = thread(isDaemon = true) {
            runCatching { Pinger().use { p -> while (!done.get()) { runCatching { p.once() }.getOrNull()?.let { synchronized(loaded) { loaded += it } }; Thread.sleep(400) } } }
        }
        // Samples every 200 ms. The first 1.5 s are the connections speeding up, so they do not count.
        val start = System.nanoTime()
        val samples = mutableListOf<Pair<Double, Long>>()
        var result: Double? = null
        while (!stop.get()) {
            Thread.sleep(200)
            val t = (System.nanoTime() - start) / 1e9
            val b = bytes.get()
            samples += t to b
            val recent = samples.filter { it.first >= t - 1.0 }
            val live = if (recent.size > 1) (recent.last().second - recent.first().second) * 8 / (recent.last().first - recent.first().first) / 1e6 else 0.0
            val from = samples.firstOrNull { it.first >= 1.5 }
            val steady = if (from != null && t - from.first > 0.5) (b - from.second) * 8 / (t - from.first) / 1e6 else null
            set(state.copy(live = live, usedMb = (used.get() + b) / 1e6))
            result = steady
            // Stop once the last two seconds agree with the two before, or at the limits.
            val a = rate(samples, t - 2, t); val p = rate(samples, t - 4, t - 2)
            if (t >= 12 || b >= cap || (t >= 6 && a != null && p != null && kotlin.math.abs(a - p) <= .06 * maxOf(a, p))) break
        }
        done.set(true)
        workers.forEach { it.join(1500) }
        pinger.join(1500)
        used.addAndGet(bytes.get())
        if (limited.get()) throw java.io.IOException("Cloudflare is limiting tests from this network. Try again in a few minutes.")
        if (!stop.get() && (result == null || result <= 0.0)) throw java.io.IOException("No data came through. Check the connection and try again.")
        val worst = synchronized(loaded) { loaded.sorted().let { if (it.isEmpty()) null else it[(it.size * 3 / 4).coerceAtMost(it.size - 1)] } }
        return result to worst?.let { Math.round(it).toInt() }
    }

    private fun rate(s: List<Pair<Double, Long>>, from: Double, to: Double): Double? {
        val a = s.firstOrNull { it.first >= from } ?: return null
        val b = s.lastOrNull { it.first <= to } ?: return null
        if (b.first - a.first < 1.5) return null
        return (b.second - a.second) * 8 / (b.first - a.first) / 1e6
    }
}
