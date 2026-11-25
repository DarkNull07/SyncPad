package dev.syncpad.app

import dev.syncpad.app.input.Keys
import dev.syncpad.app.net.FsClient
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.net.Link
import dev.syncpad.app.net.Phase
import dev.syncpad.app.net.Refused
import dev.syncpad.app.net.Wire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Browsing the real laptop app with the phone's own code. */
class FilesTest {
    private val host = File(System.getProperty("syncpad.host")!!)
    private val python = File(host, ".venv/Scripts/python.exe")
    private val cfg = Files.createTempDirectory("syncpad").toFile()
    private val box = Files.createTempDirectory("box").toFile().canonicalFile
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var proc: Process
    private val link = Link("JUnit", discover = false)
    private val fs = FsClient { link.openChannel(Wire.FILE) }

    @Before fun up() {
        org.junit.Assume.assumeTrue("needs the host virtualenv", python.exists())
        File(box, "sub").mkdir()
        File(box, "a.txt").writeText("héllo")
        File(box, "sub/big.bin").writeBytes(ByteArray(700_000) { it.toByte() })
        proc = ProcessBuilder(python.path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--port", "$port", "--config", cfg.path)
            .directory(host).redirectErrorStream(true).start()
        link.start(Laptop.parse(proc.inputStream.bufferedReader().readLine())!!)
        val end = System.currentTimeMillis() + 8000
        while (link.status.phase != Phase.CONNECTED) { check(System.currentTimeMillis() < end); Thread.sleep(10) }
    }

    @After fun down() { link.stop(); if (::proc.isInitialized) proc.destroyForcibly(); cfg.deleteRecursively(); box.deleteRecursively() }

    @Test fun browse() {
        val (folders, drives) = fs.roots()
        assertTrue(folders.any { it.name == "Desktop" } && drives.any { it.path == "C:\\" && it.total > it.free })
        val f = fs.list(box.path, false)
        assertEquals(setOf("sub", "a.txt"), f.items.map { it.name }.toSet())
        assertEquals(File(box, "a.txt").path, f.items.first { !it.dir }.path)
        assertEquals("C:\\Users", fs.list("C:\\", false).items.first { it.name == "Users" }.path)     // no doubled backslash
        assertEquals("héllo", fs.text(File(box, "a.txt").path))
        assertEquals("big.bin", fs.search(box.path, "BIG").items.single().name)
        fs.mkdir(box.path, "new"); fs.rename(File(box, "new").path, "newer")
        assertTrue(File(box, "newer").isDirectory)
        try { fs.mkdir(box.path, "newer"); fail() } catch (e: Refused) { assertTrue(e.message!!.contains("already")) }
        try { fs.list("\\\\server\\x", false); fail() } catch (_: Refused) { }
        assertEquals(3, fs.list(box.path, false).items.size)          // the channel survived the refusals
    }

    @Test fun readsRecoverAfterTheChannelDies() {
        val mine = ArrayList<dev.syncpad.app.net.Channel>()
        val c = FsClient { link.openChannel(Wire.FILE)?.also { mine += it } }
        c.list(box.path, false)
        mine.last().close()
        assertEquals(2, c.list(box.path, false).items.size)
        mine.last().close()
        try { c.mkdir(box.path, "once"); fail("must not be repeated blindly") } catch (_: java.io.IOException) { }
        c.mkdir(box.path, "once")
    }

    @Test fun folderArrivesAsZip() {
        val ch = link.openChannel(Wire.FILE)!!
        ch.send(FsClient.op("get", box.path).put("t", "fs").toString().toByteArray())
        assertEquals("${box.name}.zip", JSONObject(String(ch.recv())).getString("name"))
        val out = java.io.ByteArrayOutputStream()
        var d = ch.recv()
        while (d[0] == Wire.DATA) { out.write(d, 1, d.size - 1); d = ch.recv() }
        assertEquals(out.size().toLong(), JSONObject(String(d)).getLong("size"))
        val names = HashMap<String, Int>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().size } }
        assertEquals(700_000, names["${box.name}/sub/big.bin"])
    }

    @Test fun phoneFolderRules() {
        val roots = listOf("Internal storage" to box)
        assertEquals(1, PhoneFiles.list("", roots, JSONObject()).getJSONArray("items").length())
        val l = PhoneFiles.list(box.path, roots, JSONObject())
        assertEquals("Internal storage", l.getString("name")); assertEquals("", l.getString("parent"))
        assertEquals(box.path, PhoneFiles.list(File(box, "sub").path, roots, JSONObject()).getString("parent"))
        for (bad in listOf(box.parent, box.path + "/../", "/etc", box.path + "x", "relative", "C:\\Windows", box.path + "\u0000")) {
            try { PhoneFiles.list(bad, roots, JSONObject()); fail(bad) } catch (_: PhoneFiles.No) { }
        }
        assertEquals("x.txt", PhoneFiles.clean("../../x.txt")); assertEquals("file", PhoneFiles.clean("..")); assertEquals("a_b", PhoneFiles.clean("a:b"))
        assertEquals("a (1).txt", PhoneFiles.free(box, "a.txt").name); assertEquals("sub (1)", PhoneFiles.free(box, "sub").name)
    }

    @Test fun shortcutsReadAndWrite() {
        assertEquals(listOf(listOf("win", "printscreen")), Keys.parse("Win + PrtScn"))
        assertEquals(listOf(listOf("ctrl", "k"), listOf("ctrl", "s")), Keys.parse("ctrl+k > ctrl+s"))
        assertEquals("ctrl+k > ctrl+s", Keys.write(Keys.parse(" CTRL+K>control + s ")))
        assertEquals(emptyList<List<String>>(), Keys.parse(" > "))
        assertEquals("Win + PrtScn", Keys.show(listOf("win", "printscreen")))
        Keys.GROUPS.flatMap { it.second }.forEach { k -> assertEquals(k, Keys.name(k)) }
    }

    /** Every key the phone offers must exist on the laptop. */
    @Test fun laptopKnowsEveryKey() {
        val all = (Keys.GROUPS.flatMap { it.second } + Keys.MODS).joinToString("\n")
        val p = ProcessBuilder(python.path, "-c", "import sys\nfrom syncpad.winput import key_name\nprint([k for k in sys.stdin.read().split('\\n') if key_name(k) != k])").directory(host).redirectErrorStream(true).start()
        p.outputStream.use { it.write(all.toByteArray()) }
        assertEquals("[]", p.inputStream.bufferedReader().readText().trim())
    }

    @Test fun unsteadyLinkIsNoticedButHiccupsAreNot() {
        val calm = dev.syncpad.app.net.Steadiness()
        repeat(300) { assertFalse(calm.add(if (it % 70 == 0) 250 else 5)) }       // one slow reply now and then
        // What the phone's hotspot really does: 200 ms every 2.05 s, pinged every 260 ms.
        val s = dev.syncpad.app.net.Steadiness()
        var at = -1
        for (i in 0 until 400) {
            val t = i * 260.0
            val into = t % 2050
            if (s.add(if (into < 200) (200 - into).toInt() + 4 else 4) && at < 0) at = i
        }
        assertTrue("noticed within 20 s, was $at", at in 1..77)
        assertTrue(s.unsteady); assertEquals(4, s.typical())
        repeat(80) { s.add(4) }
        assertFalse(s.unsteady)
    }

    @Test fun cableDetection() {
        assertTrue(Link.isCable("rndis0") && Link.isCable("ncm0") && Link.isCable("usb0"))
        assertFalse(Link.isCable("wlan0") || Link.isCable("rmnet_data0") || Link.isCable("swlan0"))
        fun b(vararg x: Int) = ByteArray(4) { x[it].toByte() }
        assertTrue(Link.sameNet(b(10, 31, 103, 5), b(10, 31, 103, 142), 24))
        assertFalse(Link.sameNet(b(10, 31, 103, 5), b(10, 31, 104, 5), 24))
        assertTrue(Link.sameNet(b(192, 168, 1, 1), b(192, 168, 1, 126), 25)); assertFalse(Link.sameNet(b(192, 168, 1, 1), b(192, 168, 1, 129), 25))
    }
}
