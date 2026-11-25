package dev.syncpad.app.input

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/** Everything the UI can do to the laptop, whatever the transport. */
interface Sink {
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)
    fun zoom(units: Float)
    fun button(b: Int, down: Boolean)
    fun key(k: String, down: Boolean)
    fun combo(vararg keys: String)
    fun text(s: String)
    fun pad(b: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float)

    fun click(b: Int = 0) { button(b, true); button(b, false) }
}

data class PadConfig(
    val speed: Float = 1f,
    val accel: Float = 1f,
    val scroll: Float = 1f,
    val natural: Boolean = true,
    val tap: Boolean = true,
)

/** Pointer speed curve: slow fingers get precision, fast ones cross the screen. */
object Curve {
    /** Pixels per millimetre of finger travel at [mmPerSec]. */
    fun gain(mmPerSec: Float, speed: Float, accel: Float): Float {
        val t = ((mmPerSec - 15f) / 260f).coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        return speed * (9f + 24f * accel * s)
    }
}

/**
 * Touch points in, laptop actions out. Coordinates are pixels, times are ms.
 * Has no Android types, so every gesture is unit tested.
 */
class Gestures(private val sink: Sink, private val pxPerMm: Float, private val haptic: (Int) -> Unit = {}) {
    var config = PadConfig()

    private class F(var x: Float, var y: Float, val x0: Float, val y0: Float)

    private enum class Mode { IDLE, PENDING, MOVE, TWO, SCROLL, PINCH, MULTI, DRAIN }

    private val fingers = LinkedHashMap<Int, F>()
    private var mode = Mode.IDLE
    private var maxFingers = 0
    private var startT = 0L
    private var travel = 0f
    private var lastT = 0L
    private var speed = 0f

    // Tap: press goes out at once; the release waits, so a returning finger drags.
    private var tapDown = false
    private var releaseAt = 0L
    private var dragging = false

    private var cx = 0f
    private var cy = 0f
    private var spread = 0f
    private var cx0 = 0f
    private var cy0 = 0f
    private var spread0 = 0f
    private var axis = 0
    private var vel = ArrayDeque<Triple<Long, Float, Float>>()
    private var flingX = 0f
    private var flingY = 0f
    private var flingT = 0L
    private var stoppedFling = false

    val flinging get() = flingX != 0f || flingY != 0f
    /** True while tick() must keep being called. */
    val needsTick get() = flinging || (tapDown && !dragging && fingers.isEmpty())

    private fun mm(px: Float) = px / pxPerMm

    private fun centroid() {
        cx = fingers.values.map { it.x }.average().toFloat()
        cy = fingers.values.map { it.y }.average().toFloat()
        spread = fingers.values.map { hypot(it.x - cx, it.y - cy) }.average().toFloat()
    }

    private fun anchor() { centroid(); cx0 = cx; cy0 = cy; spread0 = spread; axis = 0; vel.clear() }

    fun down(id: Int, x: Float, y: Float, t: Long) {
        tick(t)
        stoppedFling = (fingers.isEmpty() && flinging) || (stoppedFling && fingers.isNotEmpty())
        flingX = 0f; flingY = 0f
        fingers[id] = F(x, y, x, y)
        when (fingers.size) {
            1 -> {
                mode = Mode.PENDING; maxFingers = 1; startT = t; clean = true; travel = 0f; speed = 0f; lastT = t
                if (tapDown) dragging = true
            }
            else -> {
                if (mode == Mode.DRAIN) { maxFingers = maxOf(maxFingers, fingers.size); return }
                if (dragging) return                   // a second finger while dragging is ignored
                maxFingers = maxOf(maxFingers, fingers.size)
                mode = if (fingers.size == 2) Mode.TWO else Mode.MULTI
                anchor()
            }
        }
    }

    fun move(id: Int, x: Float, y: Float, t: Long) {
        val f = fingers[id] ?: return
        val dx = x - f.x
        val dy = y - f.y
        f.x = x; f.y = y
        if (dx == 0f && dy == 0f) return
        if (fingers.size == 1 || dragging) {
            if (dragging && f !== fingers.values.first()) return
            single(dx, dy, t)
        }
    }

    /** Call once after all pointers of one event were passed to move(). */
    fun frame(t: Long) {
        if (fingers.size < 2 || dragging || mode == Mode.DRAIN) return
        val px = cx; val py = cy; val ps = spread
        centroid()
        val dx = cx - px; val dy = cy - py
        when (mode) {
            Mode.TWO -> {
                val moved = mm(hypot(cx - cx0, cy - cy0))
                val pinched = mm(abs(spread - spread0))
                if (pinched > 1.6f && pinched > moved * 1.2f) { mode = Mode.PINCH; clean = false; haptic(6) }
                else if (moved > 0.9f) {
                    mode = Mode.SCROLL
                    clean = false
                    val ax = abs(cx - cx0); val ay = abs(cy - cy0)
                    axis = if (ay > ax * 1.6f) 1 else if (ax > ay * 1.6f) 2 else 0
                }
            }
            Mode.SCROLL -> {
                val sx = if (axis == 1) 0f else dx
                val sy = if (axis == 2) 0f else dy
                emitScroll(mm(sx), mm(sy))
                vel.addLast(Triple(t, mm(sx), mm(sy)))
                while (vel.isNotEmpty() && t - vel.first().first > 80) vel.removeFirst()
            }
            Mode.PINCH -> if (ps > 1f && spread > 1f) sink.zoom(ln(spread / ps) * 900f)
            Mode.MULTI -> {
                val ax = mm(cx - cx0); val ay = mm(cy - cy0)
                if (hypot(ax, ay) > 2f) clean = false
                if (hypot(ax, ay) > 9f) { swipe(fingers.size, ax, ay); mode = Mode.DRAIN }
            }
            else -> {}
        }
    }

    fun up(id: Int, t: Long) {
        val first = fingers.entries.firstOrNull()?.key == id
        fingers.remove(id) ?: return
        if (dragging) {
            if (!first && fingers.isNotEmpty()) return
            if (mode == Mode.PENDING && t - startT < TAP_MS) {     // tap, tap: a double click
                sink.button(0, false)
                sink.button(0, true)
                releaseAt = t + TAP_HOLD
                haptic(8)
            } else {
                sink.button(0, false)
                tapDown = false
            }
            dragging = false
            mode = if (fingers.isEmpty()) Mode.IDLE else Mode.DRAIN
            return
        }
        if (fingers.isEmpty()) {
            val quick = t - startT < TAP_MS && !stoppedFling && config.tap
            if (quick) when {
                mode == Mode.PENDING && maxFingers == 1 -> press(t)
                maxFingers == 2 && (mode == Mode.TWO || mode == Mode.DRAIN) && clean -> { sink.click(1); haptic(12) }
                maxFingers >= 3 && (mode == Mode.MULTI || mode == Mode.DRAIN) && clean -> { sink.click(2); haptic(12) }
            }
            mode = Mode.IDLE
            clean = true
        } else {
            if (mode == Mode.SCROLL) startFling(t)
            mode = Mode.DRAIN                          // a lifting hand must not twitch the cursor
        }
    }

    /** False once the fingers did anything else, so a fast swipe is never also a tap. */
    private var clean = true

    fun cancel() {
        fingers.clear()
        if (tapDown) { sink.button(0, false); tapDown = false }
        dragging = false
        flingX = 0f; flingY = 0f
        mode = Mode.IDLE
        clean = true
    }

    /** Advances momentum scrolling and the delayed tap release. */
    fun tick(t: Long) {
        if (tapDown && !dragging && fingers.isEmpty() && t >= releaseAt) { sink.button(0, false); tapDown = false }
        if (flinging) {
            val dt = (t - flingT).coerceIn(0, 100)
            if (dt == 0L) return
            flingT = t
            emitScroll(flingX * dt, flingY * dt)
            val k = exp(-dt / 325f)
            flingX *= k; flingY *= k
            if (hypot(flingX, flingY) < 0.006f) { flingX = 0f; flingY = 0f }
        }
    }

    private fun press(t: Long) {
        sink.button(0, true)
        tapDown = true
        releaseAt = t + TAP_HOLD
        haptic(8)
    }

    private fun single(dx: Float, dy: Float, t: Long) {
        if (mode == Mode.DRAIN || mode == Mode.IDLE) return
        val d = mm(hypot(dx, dy))
        travel += d
        val dt = (t - lastT).coerceIn(1, 50)
        lastT = t
        val v = d / dt * 1000f
        speed = if (speed == 0f) v else speed * 0.6f + v * 0.4f
        if (mode == Mode.PENDING) {
            val f = fingers.values.first()
            if (mm(hypot(f.x - f.x0, f.y - f.y0)) < SLOP_MM) return
            mode = Mode.MOVE
            clean = false
        }
        val g = Curve.gain(speed, config.speed, config.accel)
        sink.move(mm(dx) * g, mm(dy) * g)
    }

    private fun emitScroll(mx: Float, my: Float) {
        val k = 20f * config.scroll
        val s = if (config.natural) 1f else -1f
        if (mx != 0f || my != 0f) sink.scroll(-mx * k * s, my * k * s)
    }

    private fun startFling(t: Long) {
        val fresh = vel.filter { t - it.first <= 80 }
        if (fresh.size < 2) return
        val span = (t - fresh.first().first).coerceAtLeast(8)
        val vx = fresh.drop(1).sumOf { it.second.toDouble() }.toFloat() / span
        val vy = fresh.drop(1).sumOf { it.third.toDouble() }.toFloat() / span
        if (hypot(vx, vy) > 0.035f) { flingX = vx; flingY = vy; flingT = t }
    }

    private fun swipe(n: Int, x: Float, y: Float) {
        haptic(18)
        if (n >= 4) {
            when {
                abs(x) > abs(y) -> sink.combo("ctrl", "win", if (x < 0) "right" else "left")
                y < 0 -> sink.combo("win", "tab")
                else -> sink.combo("win", "d")
            }
        } else when {
            abs(x) > abs(y) -> if (x > 0) sink.combo("alt", "tab") else sink.combo("alt", "shift", "tab")
            y < 0 -> sink.combo("win", "tab")
            else -> sink.combo("win", "d")
        }
    }

    companion object {
        const val TAP_MS = 220L
        const val TAP_HOLD = 170L
        const val SLOP_MM = 0.45f
    }
}

/** Gyroscope to cursor. Uses gravity, so how the phone is rolled in the hand does not matter. */
class AirMouse(private val sink: Sink) {
    var speed = 1f
    private val g = floatArrayOf(0f, 0f, 1f)
    private var last = 0L

    fun gravity(x: Float, y: Float, z: Float) {
        val n = hypot(hypot(x, y), z)
        if (n < 1f) return
        g[0] = x / n; g[1] = y / n; g[2] = z / n
    }

    fun reset() { last = 0L }

    /** Angular speed in rad/s, device axes; t in nanoseconds. */
    fun gyro(wx: Float, wy: Float, wz: Float, t: Long) {
        val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceIn(0f, 0.05f)
        last = t
        if (dt == 0f) return
        val yaw = wx * g[0] + wy * g[1] + wz * g[2]
        // Right = forward x up, with forward the phone's long axis.
        var rx = g[2]; var rz = -g[0]
        val n = hypot(rx, rz)
        if (n < 0.2f) return                  // pointing straight up or down: no defined right
        rx /= n; rz /= n
        val pitch = wx * rx + wz * rz
        val rate = hypot(yaw, pitch)
        // Below tremor speed the response fades to nothing instead of cutting off.
        val fade = (rate / 0.06f).coerceIn(0f, 1f)
        val k = 1150f * speed * fade * fade * (0.8f + 0.6f * (rate / 2.5f).coerceIn(0f, 1f))
        sink.move(-yaw * dt * k, -pitch * dt * k)
    }
}
