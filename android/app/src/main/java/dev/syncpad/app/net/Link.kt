package dev.syncpad.app.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

enum class Phase { OFF, SEARCHING, CONNECTED }

/** Which way to reach the laptop. Bluetooth mode keeps this link too, for files and the screen. */
enum class Mode(val label: String) { AUTO("Automatic"), USB("USB"), WIFI("Wi-Fi"), BT("Bluetooth") }

data class Status(val phase: Phase, val via: String = "", val rtt: Int = -1, val udp: Boolean = false, val unsteady: Boolean = false)

/** Judges a link by its recent round trips. One slow reply is normal; several in 20 seconds is a pattern. */
class Steadiness(private val slow: Int = 80, private val size: Int = 80, private val enough: Int = 5) {
    private val recent = ArrayDeque<Int>()

    /** Turns on at [enough] slow replies and off again only when nearly clear, so it does not flicker. */
    var unsteady = false
        private set

    fun add(ms: Int): Boolean {
        recent.addLast(ms)
        if (recent.size > size) recent.removeFirst()
        val n = recent.count { it > slow }
        unsteady = if (unsteady) n > 1 else n >= enough
        return unsteady
    }

    /** What the link is usually like just now: the middle of the last few. */
    fun typical(): Int = recent.takeLast(9).sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }
}

/**
 * The connection to one laptop: finds it, keeps it, and moves to a better
 * transport when one appears. Pure JVM, so the tests run it against the real host.
 */
class Link(
    private val model: String,
    private val bindTcp: (Socket, InetAddress) -> Unit = { _, _ -> },
    private val bindUdp: (DatagramSocket, InetAddress) -> Unit = { _, _ -> },
    private val extraHosts: () -> List<String> = { emptyList() },
    private val discover: Boolean = true,
    private val urgent: () -> Unit = {},
) {
    @Volatile var onStatus: (Status) -> Unit = {}
    @Volatile var onMessage: (JSONObject) -> Unit = {}
    @Volatile var onIps: (Laptop) -> Unit = {}

    /** Sent on every connect, so the laptop always has this phone's options. */
    @Volatile var options: JSONObject = JSONObject().put("t", "opt")
        set(v) { field = v; json(v) }

    /** Changing it drops a link it rules out, and looks again at once. */
    @Volatile var mode = Mode.AUTO
        set(v) {
            field = v
            live?.let { if (!allowed(it.ip)) it.close() }
            nudge()
        }

    private fun allowed(ip: String) = when (mode) {
        Mode.USB -> rank(ip) < 2
        Mode.WIFI -> rank(ip) == 2
        else -> true
    }

    @Volatile var laptop: Laptop? = null
        private set
    @Volatile var status = Status(Phase.OFF)
        private set
    @Volatile private var running = false
    @Volatile private var live: Live? = null
    private var loop: Thread? = null
    private val random = SecureRandom()
    private val wake = Object()

    /** Lower is better: the adb cable, then a tethering cable, then anything else. */
    private fun rank(ip: String) = if (ip == LOOPBACK) 0 else if (cableTo(ip)) 1 else 2

    private class Raw(val socket: Socket, val ip: String, val clientNonce: ByteArray, val hostNonce: ByteArray)

    // ---- lifecycle
    @Synchronized
    fun start(to: Laptop) {
        if (running && laptop?.deviceId == to.deviceId && laptop?.hostId == to.hostId) return
        stop()
        laptop = to
        running = true
        loop = thread(name = "syncpad-link") { run() }
    }

    @Synchronized
    fun stop() {
        running = false
        live?.close()
        synchronized(wake) { wake.notifyAll() }
        loop?.interrupt()
        loop?.join(1500)
        loop = null
        set(Status(Phase.OFF))
    }

    /** Try again at once: the network changed or the app came back. */
    fun nudge() = synchronized(wake) { wake.notifyAll() }

    private fun set(s: Status) {
        if (s != status) { status = s; onStatus(s) }
    }

    private fun run() {
        val me = Thread.currentThread()
        var miss = 0
        while (running && loop.let { it == null || it === me }) {
            set(Status(Phase.SEARCHING))
            val l = try { connect() } catch (_: InterruptedException) { null }
            if (l == null) {
                synchronized(wake) {
                    if (running) try { wake.wait(minOf(400L + 300L * miss, 2500L)) } catch (_: InterruptedException) { }
                }
                miss++
                continue
            }
            miss = 0
            // The laptop counts from zero for each connection, so must we, or it
            // would replay everything since the app started as one cursor jump.
            synchronized(totals) { totals.fill(0); fraction.fill(0f); q = 0 }
            live = l
            if (running) {
                set(Status(Phase.CONNECTED, l.via))
                l.serve()
            }
            live = null
            l.close()
        }
    }

    // ---- finding the laptop
    private fun connect(): Live? {
        val to = laptop ?: return null
        val hosts = LinkedHashSet<String>().apply {
            add(LOOPBACK)
            addAll(to.ips)
            addAll(extraHosts())
        }
        val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        try {
            val race = ExecutorCompletionService<Raw?>(pool)
            var pending = 0
            for (h in hosts) if (allowed(h)) { race.submit { open(h, to.port, Wire.CONTROL, 1500) }; pending++ }
            if (discover) { race.submit { discovered(to)?.takeIf { it !in hosts && allowed(it) }?.let { open(it, to.port, Wire.CONTROL, 1500) } }; pending++ }
            val end = System.nanoTime() + 2_600_000_000L
            var winner: Raw? = null
            var grace = Long.MAX_VALUE
            while (pending > 0) {
                val left = minOf(end, grace) - System.nanoTime()
                if (left <= 0) break
                val r = race.poll(left, TimeUnit.NANOSECONDS) ?: break
                pending--
                val raw = runCatching { r.get() }.getOrNull() ?: continue
                if (!allowed(raw.ip)) { raw.socket.close(); continue }      // the mode changed while this was connecting
                // First answer wins, but a cable gets a moment to overtake Wi-Fi.
                if (winner == null) { winner = raw; grace = System.nanoTime() + 150_000_000L }
                else if (rank(raw.ip) < rank(winner.ip)) { winner.socket.close(); winner = raw }
                else raw.socket.close()
                if (winner.ip == LOOPBACK) break
            }
            val left = pending
            pool.execute { repeat(left) { race.poll(3, TimeUnit.SECONDS)?.let { f -> runCatching { f.get()?.socket?.close() } } } }
            return winner?.let { w ->
                runCatching { greet(w, to) }.getOrElse { w.socket.close(); null }
            }
        } finally {
            pool.shutdown()
        }
    }

    /** TCP connect plus nonce exchange. Nothing on the laptop changes until hello. */
    private fun open(host: String, port: Int, kind: Int, timeout: Int): Raw? {
        val to = laptop ?: return null
        val s = Socket()
        try {
            val addr = InetAddress.getByName(host)
            if (!addr.isLoopbackAddress) runCatching { bindTcp(s, addr) }
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, port), timeout)
            s.soTimeout = timeout
            val cn = ByteArray(16).also { random.nextBytes(it) }
            s.getOutputStream().write(Wire.MAGIC + byteArrayOf(kind.toByte()) + Wire.unhex(to.deviceId) + cn)
            val reply = ByteArray(20)
            DataInputStream(s.getInputStream()).readFully(reply)
            if (!reply.copyOf(4).contentEquals(Wire.MAGIC)) throw IOException("not SyncPad")
            return Raw(s, host, cn, reply.copyOfRange(4, 20))
        } catch (_: Exception) {
            runCatching { s.close() }
            return null
        }
    }

    private fun channel(r: Raw, to: Laptop): Pair<Channel, SessionKeys> {
        val keys = SessionKeys(Wire.unhex(to.key), r.clientNonce, r.hostNonce)
        val ch = Channel(r.socket, keys.c2s, keys.s2c)
        ch.send(JSONObject().put("t", "hello").put("model", model).toString().toByteArray())
        return ch to keys
    }

    private fun greet(r: Raw, to: Laptop): Live {
        val (ch, keys) = channel(r, to)
        val w = JSONObject(String(ch.recv()))
        if (w.optString("t") != "welcome" || w.optString("h") != to.hostId) throw IOException("wrong host")
        val ips = LinkedHashSet<String>()
        if (r.ip != LOOPBACK) ips += r.ip
        w.optJSONArray("ips")?.let { a -> for (i in 0 until a.length()) ips += a.getString(i) }
        val fresh = to.copy(name = w.optString("name", to.name), ips = (ips + to.ips).take(8))
        if (fresh != to) { laptop = fresh; onIps(fresh) }
        return Live(ch, keys, w.getLong("sid").toInt(), r.ip, w.getInt("udp"))
    }

    private fun discovered(to: Laptop): String? = runCatching {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 350
            val targets = LinkedHashSet<InetAddress>()
            targets += InetAddress.getByName("255.255.255.255")
            for (n in NetworkInterface.getNetworkInterfaces()) if (n.isUp && !n.isLoopback)
                n.interfaceAddresses.mapNotNullTo(targets) { it.broadcast }
            val q = "SPD?".toByteArray()
            val buf = ByteArray(600)
            repeat(3) {
                for (t in targets) runCatching { s.send(DatagramPacket(q, q.size, t, Wire.DISCOVERY_PORT)) }
                val end = System.nanoTime() + 350_000_000L
                while (System.nanoTime() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: IOException) { break }
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith("SPD!") && runCatching { JSONObject(text.drop(4)).getString("h") }.getOrNull() == to.hostId)
                        return@use p.address.hostAddress
                }
            }
            null
        }
    }.getOrNull()

    /** A second encrypted connection, for screen frames or a file. Blocking. */
    fun openChannel(kind: Int): Channel? {
        val to = laptop ?: return null
        val ip = live?.ip ?: return null
        val raw = open(ip, to.port, kind, 4000) ?: return null
        return runCatching {
            raw.socket.soTimeout = 30_000
            channel(raw, to).first.also { it.recv() }
        }.getOrElse { raw.socket.close(); null }
    }

    // ---- input
    private val totals = IntArray(4)
    private val fraction = FloatArray(4)
    private var q = 0

    private fun snapshot(kind: Byte, extra: Int = 0): ByteBuffer = ByteBuffer.allocate(21 + extra).put(kind).putInt(q)
        .putInt(totals[0]).putInt(totals[1]).putInt(totals[2]).putInt(totals[3])

    private fun add(i: Int, v: Float) {
        val f = fraction[i] + v
        val whole = f.toInt()
        fraction[i] = f - whole
        totals[i] += whole
    }

    /** Pixels. Safe from any thread and never blocks. */
    fun move(dx: Float, dy: Float) {
        if (live == null) return
        synchronized(totals) { add(0, dx * 64); add(1, dy * 64); q++ }
        live?.dirty()
    }

    /** Wheel units: 120 is one notch. */
    fun scroll(dx: Float, dy: Float) {
        if (live == null) return
        synchronized(totals) { add(2, dx); add(3, dy); q++ }
        live?.dirty()
    }

    fun button(b: Int, down: Boolean) {
        val l = live ?: return
        val m = synchronized(totals) { q++; snapshot(Wire.BUTTON, 2) }
        l.queue.add(m.put(b.toByte()).put(if (down) 1 else 0).array())
    }

    fun json(m: JSONObject) { live?.queue?.add(m.toString().toByteArray()) }

    // ---- an established connection
    private inner class Live(val ch: Channel, keys: SessionKeys, sid: Int, val ip: String, udpPort: Int) {
        val usb = ip == LOOPBACK
        private val cable = !usb && cableTo(ip)
        val via = if (usb || cable) "USB" else "Wi-Fi"
        val queue = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean()
        private val lock = Object()
        private var pendingState = false
        private var lastState = 0L
        private var tail = TAIL.size          // nothing to resend until something moves
        private val tcpState = AtomicBoolean()
        private val crypto = UdpCrypto(sid, keys.udpC2s, keys.udpS2c)
        @Volatile private var udpSeen = 0L
        private val steady = Steadiness()
        private val addr = InetAddress.getByName(ip)
        // adb forwards TCP only, so the cable carries state over TCP.
        private val udp: DatagramSocket? = if (usb && !udpOnLoopback) null else runCatching {
            DatagramSocket().also { s ->
                runCatching { bindUdp(s, addr) }
                runCatching { s.trafficClass = 0xB8 }      // expedited: Wi-Fi voice queue
                s.connect(addr, udpPort)
            }
        }.getOrNull()

        private fun udpOk() = udp != null && System.nanoTime() - udpSeen < 2_500_000_000L

        fun dirty() = synchronized(lock) { pendingState = true; tail = 0; lock.notify() }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            queue.add(STOP)
            synchronized(lock) { lock.notifyAll() }
            udp?.close()
            ch.close()
        }

        fun serve() {
            queue.add(options.toString().toByteArray())
            if (cable) queue.add(JSONObject().put("t", "link").put("cable", true).toString().toByteArray())
            thread(name = "syncpad-tx") { runCatching { txLoop() }; close() }
            thread(name = "syncpad-state") { urgent(); runCatching { stateLoop() } }
            if (udp != null) thread(name = "syncpad-urx") { urgent(); runCatching { urxLoop() } }
            val beat = thread(name = "syncpad-beat") { runCatching { beatLoop() } }
            try {
                ch.socket.soTimeout = 4000
                while (running && !closed.get()) {
                    val d = ch.recv()
                    if (d.isEmpty() || d[0] != '{'.code.toByte()) continue
                    val m = runCatching { JSONObject(String(d)) }.getOrNull() ?: continue
                    if (m.optString("t") == "pong") { if (!udpOk()) report(m.optLong("ts")) }
                    else runCatching { onMessage(m) }
                }
            } catch (_: IOException) {
            } finally {
                close()
                beat.interrupt()
            }
        }

        private fun report(sentNs: Long) {
            val ms = ((System.nanoTime() - sentNs) / 1_000_000.0)
            val s = synchronized(steady) {
                steady.add(if (ms < 1) 1 else Math.round(ms).toInt())
                Status(Phase.CONNECTED, via, steady.typical(), udpOk(), steady.unsteady && !usb && !cable)
            }
            if (!closed.get()) set(s)
        }

        private fun txLoop() {
            while (!closed.get()) {
                val m = queue.take()
                if (m === STOP) return
                if (m === STATE_MARK) {
                    tcpState.set(false)
                    ch.send(synchronized(totals) { snapshot(Wire.STATE) }.array())
                } else ch.send(m)
            }
        }

        private fun stateLoop() {
            while (!closed.get()) {
                synchronized(lock) {
                    while (!pendingState && !closed.get()) {
                        if (tail >= TAIL.size) lock.wait() else {
                            val due = lastState + TAIL[tail] * 1_000_000L - System.nanoTime()
                            if (due <= 0) break
                            lock.wait(due / 1_000_000, (due % 1_000_000).toInt())
                        }
                    }
                    if (!pendingState) tail++ else { lastState = System.nanoTime(); tail = 0 }
                    pendingState = false
                }
                if (closed.get()) return
                val u = udp
                if (u != null && udpOk()) {
                    val p = synchronized(crypto) { crypto.seal(synchronized(totals) { snapshot(Wire.STATE) }.array()) }
                    try { u.send(DatagramPacket(p, p.size)) } catch (_: IOException) { udpSeen = 0 }
                } else {
                    tail = TAIL.size               // TCP does not lose the last packet
                    if (tcpState.compareAndSet(false, true)) queue.add(STATE_MARK)
                }
            }
        }

        private fun urxLoop() {
            val buf = ByteArray(256)
            while (!closed.get()) {
                val p = DatagramPacket(buf, buf.size)
                try { udp!!.receive(p) } catch (e: IOException) { if (closed.get()) return; Thread.sleep(50); continue }
                val m = crypto.open(buf, p.length) ?: continue
                if (m.size == 9 && m[0] == Wire.UPING) {
                    udpSeen = System.nanoTime()
                    report(ByteBuffer.wrap(m, 1, 8).long)
                }
            }
        }

        private fun beatLoop() {
            var n = 0
            var beat = 0
            while (!closed.get()) {
                // Pings are tiny; four a second is enough to notice a link that keeps pausing.
                val full = beat++ % 3 == 0
                if (full) queue.add(JSONObject().put("t", "ping").put("ts", System.nanoTime()).toString().toByteArray())
                udp?.let { u ->
                    val body = ByteBuffer.allocate(9).put(Wire.UPING).putLong(System.nanoTime()).array()
                    val p = synchronized(crypto) { crypto.seal(body) }
                    runCatching { u.send(DatagramPacket(p, p.size)) }
                }
                // On Wi-Fi, look for a cable now and then. A tethering cable is not worth leaving for adb.
                if (full && mode != Mode.WIFI && !usb && !cable && ++n % 3 == 0) laptop?.let { to ->
                    open(LOOPBACK, to.port, Wire.CONTROL, 300)?.let { it.socket.close(); close(); return }
                    if (discover && n % 6 == 0 && cableUp()) discovered(to)?.takeIf { rank(it) == 1 }?.let { found ->
                        laptop = to.copy(ips = (listOf(found) + to.ips).distinct().take(8))
                        close()
                        return
                    }
                }
                Thread.sleep(260)
            }
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        /** Tests have no second machine; on a phone loopback means adb, which has no UDP. */
        @JvmField var udpOnLoopback = false
        private val STOP = ByteArray(0)
        private val STATE_MARK = ByteArray(0)
        /** Resend the last state this long after motion stops, in case that packet was lost. */
        private val TAIL = intArrayOf(8, 24, 70)

        fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        fun ipv4(a: InetAddress) = a is Inet4Address

        /** Network interfaces Android uses for USB tethering. */
        fun isCable(name: String) = name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm")

        private fun interfaces() = runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback } }.getOrDefault(emptyList())

        fun cableUp() = interfaces().any { isCable(it.name) }

        fun sameNet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            if (a.size != b.size || prefix !in 1..a.size * 8) return false
            for (i in 0 until prefix) if ((a[i / 8].toInt() xor b[i / 8].toInt()) shr (7 - i % 8) and 1 != 0) return false
            return true
        }

        private fun cableTo(ip: String): Boolean = runCatching {
            val to = InetAddress.getByName(ip).address
            interfaces().any { n -> isCable(n.name) && n.interfaceAddresses.any { sameNet(it.address.address, to, it.networkPrefixLength.toInt()) } }
        }.getOrDefault(false)
    }
}
