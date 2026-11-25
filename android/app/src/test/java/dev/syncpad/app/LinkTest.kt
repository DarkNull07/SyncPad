package dev.syncpad.app

import dev.syncpad.app.net.Laptop
import dev.syncpad.app.net.Link
import dev.syncpad.app.net.Phase
import dev.syncpad.app.net.Wire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** The phone's real networking code against the real Windows host, in dry-run mode. */
class LinkTest {
    private val host = File(System.getProperty("syncpad.host")!!)
    private val python = File(host, ".venv/Scripts/python.exe")
    private val cfg = Files.createTempDirectory("syncpad").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val out = CopyOnWriteArrayList<String>()
    private var proc: Process? = null
    private val link = Link("JUnit", discover = false)

    private fun startHost(): String {
        val p = ProcessBuilder(python.path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--port", "$port", "--config", cfg.path)
            .directory(host).redirectErrorStream(true).start()
        proc = p
        val r = p.inputStream.bufferedReader()
        val first = r.readLine() ?: error("host did not start")
        thread(isDaemon = true) { runCatching { r.forEachLine { out += it } } }
        return first
    }

    private fun await(what: String, ms: Long = 6000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out: $what\nstatus ${link.status}\n${out.takeLast(15).joinToString("\n")}"); Thread.sleep(10) }
    }

    private fun moved(): Pair<Int, Int> {
        var x = 0; var y = 0
        out.filter { it.startsWith("[dry-run] move ") }.forEach { l -> l.split(' ').let { x += it[2].toInt(); y += it[3].toInt() } }
        return x to y
    }

    @Before fun up() {
        org.junit.Assume.assumeTrue("needs the host virtualenv", python.exists())
        Link.udpOnLoopback = true
    }

    @After fun down() { link.stop(); proc?.destroyForcibly(); cfg.deleteRecursively(); Link.udpOnLoopback = false }

    @Test fun fullSession() {
        link.start(Laptop.parse(startHost())!!)
        await("connect") { link.status.phase == Phase.CONNECTED }
        await("udp") { link.status.udp }
        repeat(400) { link.move(1.25f, -0.5f); Thread.sleep(1) }
        link.button(0, true); link.button(0, false)
        await("click") { out.any { it.contains("button 0 False") } }
        assertEquals(500 to -200, moved())
        assertTrue(out.any { it == "[dry-run] button 0 True (1000, 300)" })
        link.scroll(0f, 45.5f); link.scroll(0f, 74.5f)
        await("scroll") { out.filter { it.contains(" wheel ") }.sumOf { it.substringAfterLast(' ').toInt() } == 120 }
        link.json(JSONObject().put("t", "text").put("s", "hello"))
        await("text") { out.any { it == "[dry-run] text hello" } }
    }

    @Test fun tcpOnlyLikeACable() {
        Link.udpOnLoopback = false
        link.start(Laptop.parse(startHost())!!)
        await("connect") { link.status.phase == Phase.CONNECTED && link.status.rtt >= 0 }
        assertEquals("USB", link.status.via); assertFalse(link.status.udp)
        repeat(300) { link.move(2f, 1f); if (it % 10 == 0) Thread.sleep(1) }
        link.button(1, true); link.button(1, false)
        await("click") { out.any { it.contains("button 1 False") } }
        assertEquals(600 to 300, moved())
    }

    @Test fun reconnectsAfterHostRestartAndReleasesKeys() {
        val l = Laptop.parse(startHost())!!
        link.start(l)
        await("connect") { link.status.phase == Phase.CONNECTED }
        link.json(JSONObject().put("t", "key").put("k", "w").put("d", 1))
        await("held") { out.any { it == "[dry-run] key w True" } }
        link.stop()
        await("released") { out.any { it == "[dry-run] key w False" } }
        link.start(l)
        await("connect again") { link.status.phase == Phase.CONNECTED }
        proc!!.destroyForcibly().waitFor()
        await("noticed") { link.status.phase != Phase.CONNECTED }
        startHost()
        val t = System.currentTimeMillis()
        await("reconnect", 10_000) { link.status.phase == Phase.CONNECTED }
        println("reconnected ${System.currentTimeMillis() - t} ms after host start")
        out.clear()
        link.move(10f, 0f)
        await("moves after reconnect") { moved() == (10 to 0) }
    }

    /** Found on a real phone: earlier movement was replayed as one jump after reconnecting. */
    @Test fun reconnectDoesNotReplayOldMovement() {
        val l = Laptop.parse(startHost())!!
        link.start(l)
        await("connect") { link.status.phase == Phase.CONNECTED }
        repeat(50) { link.move(8f, -6f); Thread.sleep(1) }
        await("first moves") { moved() == (400 to -300) }
        repeat(2) {
            link.stop()
            Thread.sleep(300)
            out.clear()                 // before reconnecting: the jump happens on connect
            link.start(l)
            await("connect again") { link.status.phase == Phase.CONNECTED }
            Thread.sleep(300)
            assertEquals("nothing moves by itself", 0 to 0, moved())
            link.move(5f, 0f)
            link.button(1, true); link.button(1, false)
            await("click") { out.any { it.contains("button 1 False") } }
            assertEquals(5 to 0, moved())
        }
    }

    @Test fun filesAndScreenChannels() {
        link.start(Laptop.parse(startHost())!!)
        await("connect") { link.status.phase == Phase.CONNECTED }
        val ch = link.openChannel(Wire.SCREEN)!!
        ch.send("""{"t":"next","w":640,"q":50}""".toByteArray())
        val f = ch.recv()
        assertEquals(0x10, f[0].toInt()); assertEquals(0xFF.toByte(), f[10]); assertEquals(0xD8.toByte(), f[11])
        ch.close()
        val up = link.openChannel(Wire.FILE)!!
        up.send("""{"t":"put","name":"x.bin","size":1}""".toByteArray())
        assertEquals("go", JSONObject(String(up.recv())).getString("t"))
        up.close()                              // abandoned: the host must discard it
    }

    /** Loopback counts as the cable here, so Wi-Fi mode must refuse it. */
    @Test fun modeDecidesWhichWayIsUsed() {
        val l = Laptop.parse(startHost())!!.copy(ips = emptyList())
        link.mode = dev.syncpad.app.net.Mode.WIFI
        link.start(l)
        Thread.sleep(2500)
        assertNotEquals(Phase.CONNECTED, link.status.phase)
        link.mode = dev.syncpad.app.net.Mode.USB
        await("usb", 4000) { link.status.phase == Phase.CONNECTED && link.status.via == "USB" }
        link.json(JSONObject().put("t", "key").put("k", "shift").put("d", 1))
        await("held") { out.any { it == "[dry-run] key shift True" } }
        link.mode = dev.syncpad.app.net.Mode.WIFI
        await("dropped", 3000) { link.status.phase != Phase.CONNECTED }
        await("released") { out.any { it == "[dry-run] key shift False" } }
        Thread.sleep(1500)
        assertNotEquals(Phase.CONNECTED, link.status.phase)
        link.mode = dev.syncpad.app.net.Mode.BT                // Bluetooth still keeps the link, for files
        await("back", 4000) { link.status.phase == Phase.CONNECTED }
        link.mode = dev.syncpad.app.net.Mode.AUTO
        Thread.sleep(800)
        assertEquals(Phase.CONNECTED, link.status.phase)       // an allowed link is left alone
    }

    @Test fun wrongKeyNeverConnects() {
        val l = Laptop.parse(startHost())!!
        link.start(l.copy(key = "00".repeat(32)))
        Thread.sleep(2500)
        assertNotEquals(Phase.CONNECTED, link.status.phase)
        assertTrue(out.isEmpty())
    }

    @Test fun latency() {
        link.start(Laptop.parse(startHost())!!)
        await("udp") { link.status.udp }
        val got = ArrayList<Double>()
        repeat(60) {
            val n = out.size
            val t = System.nanoTime()
            link.move(1f, 0f)
            while (out.size == n && System.nanoTime() - t < 500_000_000) Thread.onSpinWait()
            got += (System.nanoTime() - t) / 1e6
            Thread.sleep(12)
        }
        got.sort()
        println("touch to injection on this machine: median %.2f ms, p95 %.2f ms".format(got[30], got[56]))
        assertTrue(got[30] < 10)
    }
}
