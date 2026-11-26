package dev.syncpad.app

import dev.syncpad.app.net.IrohTunnel
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.net.Link
import dev.syncpad.app.net.Phase
import dev.syncpad.app.net.Wire
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Away from home: the phone's real code reaches the real laptop app only through
 * iroh, as it would from mobile data. Both run on this computer, so iroh finds a
 * direct path; what is tested is the bridging on both sides.
 */
class RemoteTest {
    private val host = File(System.getProperty("syncpad.host")!!)
    private val python = File(host, ".venv/Scripts/python.exe")
    private val cfg = Files.createTempDirectory("syncpad").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val out = CopyOnWriteArrayList<String>()
    private var proc: Process? = null
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val tunnel = IrohTunnel { key }
    private val links = mutableListOf<Link>()

    private fun await(what: String, ms: Long = 15000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) fail("timed out: $what\n${links.map { it.status }}\n${out.takeLast(15).joinToString("\n")}"); Thread.sleep(20) }
    }

    private fun link() = Link("JUnit", discover = false, tunnel = tunnel).also { links += it }

    @After fun down() { links.forEach { it.stop() }; tunnel.shutdown(); proc?.destroyForcibly(); cfg.deleteRecursively() }

    @Test fun reachesTheLaptopFromAnotherNetwork() {
        assumeTrue("needs the host virtualenv", python.exists())
        val p = ProcessBuilder(python.path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--remote", "--port", "$port", "--config", cfg.path)
            .directory(host).redirectErrorStream(true).start()
        proc = p
        val r = p.inputStream.bufferedReader()
        val pairing = Laptop.parse(r.readLine() ?: error("host did not start"))!!
        thread(isDaemon = true) { runCatching { r.forEachLine { out += it } } }
        tunnel.start()
        await("phone id") { tunnel.id.isNotEmpty() }

        // At home: pair over the usual way. The laptop says where to find it; the phone says who it is.
        val home = link()
        home.start(pairing)
        await("home") { home.status.phase == Phase.CONNECTED && home.laptop!!.remote.isNotEmpty() }
        Thread.sleep(500)
        home.stop()

        // Away: nothing nearby answers (a port nobody listens on), so only the tunnel can reach it.
        val dead = ServerSocket(0).use { it.localPort }
        val away = link()
        away.start(home.laptop!!.copy(port = dead, ips = emptyList()))
        await("away") { away.status.phase == Phase.CONNECTED }
        assertEquals("Internet", away.status.via)
        await("udp through datagrams") { away.status.udp }
        repeat(200) { away.move(1f, 2f); Thread.sleep(1) }
        away.button(0, true); away.button(0, false)
        await("click") { out.any { it.contains("button 0 False") } }
        var x = 0; var y = 0
        out.filter { it.startsWith("[dry-run] move ") }.forEach { l -> l.split(' ').let { x += it[2].toInt(); y += it[3].toInt() } }
        assertEquals(200 to 400, x to y)
        // Files and the screen open more channels: each is another stream.
        val ch = away.openChannel(Wire.FILE)
        assertNotNull(ch)
        ch!!.close()
        assertEquals("direct", tunnel.route())
    }

    @Test fun anUnknownPhoneIsTurnedAway() {
        assumeTrue("needs the host virtualenv", python.exists())
        val p = ProcessBuilder(python.path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--remote", "--port", "$port", "--config", cfg.path)
            .directory(host).redirectErrorStream(true).start()
        proc = p
        val r = p.inputStream.bufferedReader()
        val pairing = Laptop.parse(r.readLine() ?: error("host did not start"))!!
        // Pair without the tunnel, so the laptop never learns this phone's id.
        val home = Link("JUnit", discover = false).also { links += it }
        home.start(pairing)
        await("home") { home.status.phase == Phase.CONNECTED && home.laptop!!.remote.isNotEmpty() }
        home.stop()
        tunnel.start()
        await("phone id") { tunnel.id.isNotEmpty() }
        val away = link()
        away.start(home.laptop!!.copy(port = ServerSocket(0).use { it.localPort }, ips = emptyList()))
        Thread.sleep(6000)
        assertNotEquals(Phase.CONNECTED, away.status.phase)
    }
}
