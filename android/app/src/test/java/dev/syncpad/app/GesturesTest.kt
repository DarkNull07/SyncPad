package dev.syncpad.app

import dev.syncpad.app.input.AirMouse
import dev.syncpad.app.input.Curve
import dev.syncpad.app.input.Gestures
import dev.syncpad.app.input.Sink
import dev.syncpad.app.net.HidLink
import dev.syncpad.app.net.Laptop
import org.junit.Assert.*
import org.junit.Test

class FakeSink : Sink {
    val log = ArrayList<String>()
    var mx = 0f; var my = 0f; var sx = 0f; var sy = 0f; var z = 0f
    override fun move(dx: Float, dy: Float) { mx += dx; my += dy }
    override fun scroll(dx: Float, dy: Float) { sx += dx; sy += dy }
    override fun zoom(units: Float) { z += units }
    override fun button(b: Int, down: Boolean) { log += "b$b${if (down) "+" else "-"}" }
    override fun key(k: String, down: Boolean) { log += "$k${if (down) "+" else "-"}" }
    override fun combo(vararg keys: String) { log += keys.joinToString("+") }
    override fun text(s: String) { log += s }
    override fun pad(b: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {}
}

class GesturesTest {
    private val s = FakeSink()
    private val g = Gestures(s, 16f)          // 16 px per mm, a typical 400 dpi phone
    private var t = 1000L

    private fun tap(n: Int = 1, hold: Long = 60) {
        repeat(n) { g.down(it, 100f + 200 * it, 100f, t) }
        t += hold
        repeat(n) { g.up(it, t) }
    }

    private fun drag(dx: Float, dy: Float, n: Int = 1, steps: Int = 10, lift: Boolean = true) {
        repeat(n) { g.down(it, 500f + 200 * it, 500f, t) }
        for (k in 1..steps) {
            t += 8
            repeat(n) { g.move(it, 500f + 200 * it + dx * k / steps, 500f + dy * k / steps, t) }
            g.frame(t)
        }
        if (lift) { t += 4; repeat(n) { g.up(it, t) } }
    }

    private fun settle() { repeat(400) { t += 8; g.tick(t) } }

    @Test fun tapPressesAtOnceAndReleasesLater() {
        tap()
        assertEquals(listOf("b0+"), s.log)
        settle()
        assertEquals(listOf("b0+", "b0-"), s.log)
        assertEquals(0f, s.mx, 0f)
    }

    @Test fun doubleTapIsTwoClicks() {
        tap(); t += 90; tap(); settle()
        assertEquals(listOf("b0+", "b0-", "b0+", "b0-"), s.log)
    }

    @Test fun tapThenDragHoldsTheButton() {
        tap(); t += 90
        drag(200f, 0f, lift = false)
        assertEquals(listOf("b0+"), s.log)
        assertTrue(s.mx > 50)
        t += 500; g.up(0, t); settle()
        assertEquals(listOf("b0+", "b0-"), s.log)
    }

    @Test fun twoAndThreeFingerTaps() {
        tap(2); t += 400; tap(3); settle()
        assertEquals(listOf("b1+", "b1-", "b2+", "b2-"), s.log)
        assertEquals(0f, s.sy, 0f)
    }

    @Test fun movingNeverClicks() {
        drag(300f, 0f); settle()
        assertTrue(s.log.isEmpty() && s.mx > 100 && s.my == 0f)
    }

    @Test fun slowIsPrecise() {
        assertEquals(9f, Curve.gain(5f, 1f, 1f), 0.01f)
        assertEquals(33f, Curve.gain(400f, 1f, 1f), 0.01f)
        assertEquals(9f, Curve.gain(400f, 1f, 0f), 0.01f)
    }

    @Test fun scrollFlingsThenStops() {
        drag(0f, -400f, n = 2)
        val atLift = s.sy
        assertTrue(atLift < -100 && s.sx == 0f && g.flinging)
        settle()
        assertTrue(s.sy < atLift - 50 && !g.flinging && s.log.isEmpty() && s.mx == 0f)
    }

    @Test fun touchStopsFlingWithoutClicking() {
        drag(0f, -400f, n = 2)
        t += 30; g.tick(t); tap(); settle()
        assertTrue(s.log.isEmpty())
    }

    @Test fun liftingOneFingerDoesNotTwitchCursor() {
        drag(0f, 300f, n = 2, lift = false)
        g.up(1, t)
        repeat(5) { t += 8; g.move(0, 500f + it * 40, 900f, t); g.frame(t) }
        g.up(0, t); settle()
        assertEquals(0f, s.mx, 0f)
        assertTrue(s.log.isEmpty())
    }

    @Test fun pinchZooms() {
        g.down(0, 400f, 500f, t); g.down(1, 600f, 500f, t)
        for (k in 1..10) { t += 8; g.move(0, 400f - 15 * k, 500f, t); g.move(1, 600f + 15 * k, 500f, t); g.frame(t) }
        t += 8; g.up(0, t); g.up(1, t); settle()
        assertTrue(s.z > 100 && s.sy == 0f && s.log.isEmpty())
    }

    @Test fun swipes() {
        drag(0f, -300f, n = 3); t += 500; drag(300f, 0f, n = 3); t += 500; drag(-300f, 0f, n = 4); settle()
        assertEquals(listOf("win+tab", "alt+tab", "ctrl+win+right"), s.log)
    }

    @Test fun cancelReleases() {
        tap(); t += 50; g.down(0, 1f, 1f, t); g.cancel()
        assertEquals(listOf("b0+", "b0-"), s.log)
    }

    @Test fun everyPressIsReleasedUnderRandomTouches() {
        val r = java.util.Random(7)
        val down = HashSet<Int>()
        repeat(60_000) {
            t += r.nextInt(60)
            val id = r.nextInt(4)
            when {
                id !in down && r.nextInt(3) == 0 -> { down += id; g.down(id, r.nextFloat() * 900, r.nextFloat() * 900, t) }
                id in down && r.nextInt(3) == 0 -> { down -= id; g.up(id, t) }
                id in down -> { g.move(id, r.nextFloat() * 900, r.nextFloat() * 900, t); g.frame(t) }
                else -> g.tick(t)
            }
        }
        down.toList().forEach { g.up(it, t) }
        settle()
        for (b in 0..2) {
            var held = false
            for (e in s.log) {
                if (e == "b$b+") { assertFalse(held); held = true }
                if (e == "b$b-") { assertTrue(held); held = false }
            }
            assertFalse(held)
        }
        assertTrue(s.mx.isFinite() && s.sy.isFinite())
    }

    @Test fun airMouseDirectionSurvivesRoll() {
        fun run(gx: Float, gy: Float, gz: Float, w: FloatArray): Pair<Float, Float> {
            val k = FakeSink(); val a = AirMouse(k)
            a.gravity(gx, gy, gz)
            for (i in 1..100) a.gyro(w[0], w[1], w[2], i * 5_000_000L)
            return k.mx to k.my
        }
        val flatRight = run(0f, 0f, 9.8f, floatArrayOf(0f, 0f, -1f))
        val rolledRight = run(9.8f, 0f, 0f, floatArrayOf(-1f, 0f, 0f))       // on its side
        val flatUp = run(0f, 0f, 9.8f, floatArrayOf(1f, 0f, 0f))
        assertTrue(flatRight.first > 100 && Math.abs(flatRight.second) < 1)
        assertEquals(flatRight.first, rolledRight.first, 1f)
        assertTrue(flatUp.second < -100 && Math.abs(flatUp.first) < 1)
        assertEquals(0f, run(0f, 0f, 9.8f, floatArrayOf(.004f, .003f, .004f)).first, 0.1f)   // hand tremor
    }

    @Test fun hidTables() {
        assertEquals(0x04 to true, HidLink.forChar('A'))
        assertEquals(0x1F to true, HidLink.forChar('@'))
        assertEquals(0x38 to true, HidLink.forChar('?'))
        assertEquals(0x33 to true, HidLink.forChar(':'))
        assertNull(HidLink.forChar('é'))
        assertEquals(0x45, HidLink.usage("f12"))
        // The descriptor must close every collection it opens.
        var i = 0; var depth = 0; val d = HidLink.DESCRIPTOR
        while (i < d.size) {
            val b = d[i].toInt() and 0xff
            if (b and 0xFC == 0xA0) depth++
            if (b and 0xFC == 0xC0) depth--
            i += 1 + intArrayOf(0, 1, 2, 4)[b and 3]
        }
        assertEquals(d.size, i); assertEquals(0, depth)
        // Report sizes must match what is sent: walk the items and add up input and feature bits per report.
        val bits = HashMap<String, Int>(); var size = 0; var count = 0; var id = 0
        i = 0
        while (i < d.size) {
            val b = d[i].toInt() and 0xff; val n = intArrayOf(0, 1, 2, 4)[b and 3]
            val v = if (n > 0) d[i + 1].toInt() and 0xff else 0
            when (b and 0xFC) { 0x74 -> size = v; 0x94 -> count = v; 0x84 -> id = v
                0x80 -> bits.merge("in$id", size * count, Int::plus); 0xB0 -> bits.merge("feature$id", size * count, Int::plus) }
            i += 1 + n
        }
        assertEquals(mapOf("in1" to 56, "in2" to 64, "in3" to 16, "in4" to 64, "feature1" to 8), bits.toMap())
    }

    @Test fun badLinksRejected() {
        val k = "ab".repeat(32)
        assertNotNull(Laptop.parse("syncpad://pair?v=1&h=0011223344556677&d=0011223344556677&k=$k&n=My%20PC&p=47800&a=10.0.0.2,evil.com"))
        assertEquals(listOf("10.0.0.2"), Laptop.parse("syncpad://pair?v=1&h=0011223344556677&d=0011223344556677&k=$k&n=x&p=47800&a=10.0.0.2,evil.com")!!.ips)
        listOf("", "https://x", "syncpad://pair", "syncpad://pair?v=2&h=0011223344556677&d=0011223344556677&k=$k&p=1",
            "syncpad://pair?v=1&h=00&d=0011223344556677&k=$k&p=1", "syncpad://pair?v=1&h=0011223344556677&d=0011223344556677&k=zz&p=1")
            .forEach { assertNull(it, Laptop.parse(it)) }
    }
}
