package dev.syncpad.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.syncpad.app.Hub
import dev.syncpad.app.input.Gestures
import dev.syncpad.app.net.Channel
import dev.syncpad.app.net.Wire
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

const val C_PANEL = 0xFF191B1E.toInt()
const val C_LINE = 0xFF2A2E32.toInt()
const val C_TEXT = 0xFFF1F3F2.toInt()
const val C_DIM = 0xFF8D949A.toInt()
const val C_ACCENT = 0xFFC8F250.toInt()

/** The trackpad. A plain View, to get unbuffered touch events with the least work per event. */
class PadView(context: Context, private val hub: Hub, haptic: (Int) -> Unit) : View(context) {
    private val dm = resources.displayMetrics
    private val mm = (if (dm.xdpi in dm.densityDpi * 0.6f..dm.densityDpi * 1.6f) dm.xdpi else dm.densityDpi.toFloat()) / 25.4f
    val gestures = Gestures(hub, mm, haptic)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_ACCENT }
    private val touches = HashMap<Int, FloatArray>()
    private var ticking = false
    var onTouch: () -> Unit = {}

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            gestures.tick(SystemClock.uptimeMillis())
            if (gestures.needsTick) Choreographer.getInstance().postFrameCallback(this) else ticking = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.config = hub.padConfig
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    requestUnbufferedDispatch(e)
                    parent?.requestDisallowInterceptTouchEvent(true)      // on a scrolling page, a swipe here moves the cursor
                    onTouch()
                }
                val i = e.actionIndex
                gestures.down(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
                touches[e.getPointerId(i)] = floatArrayOf(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (k in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount)
                        gestures.move(e.getPointerId(i), e.getHistoricalX(i, k), e.getHistoricalY(i, k), e.getHistoricalEventTime(k))
                    gestures.frame(e.getHistoricalEventTime(k))
                }
                for (i in 0 until e.pointerCount) {
                    gestures.move(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
                    touches[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                }
                gestures.frame(e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                gestures.up(id, e.eventTime)
                touches.remove(id)
                if (e.actionMasked == MotionEvent.ACTION_UP) touches.clear()
            }
            MotionEvent.ACTION_CANCEL -> { gestures.cancel(); touches.clear() }
        }
        if (gestures.needsTick && !ticking) { ticking = true; Choreographer.getInstance().postFrameCallback(tick) }
        postInvalidateOnAnimation()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        gestures.cancel()
        Choreographer.getInstance().removeFrameCallback(tick)
        ticking = false
    }

    private val step = 22 * dm.density
    private val dotR = 1.1f * dm.density
    private val reach = 92 * dm.density
    private var grid: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        grid = if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { b ->
            val g = Canvas(b)
            dot.color = C_LINE
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) { g.drawCircle(x, y, dotR, dot); x += step }
                y += step
            }
        }
    }

    /** The grid is drawn once; a frame only adds the few dots near each finger. */
    override fun onDraw(c: Canvas) {
        dot.color = C_LINE
        grid?.let { c.drawBitmap(it, 0f, 0f, dot) }
        dot.color = C_ACCENT
        for (t in touches.values) {
            val span = (reach / step).toInt() + 1
            val ci = (t[0] / step).toInt(); val cj = (t[1] / step).toInt()
            for (j in maxOf(0, cj - span)..cj + span) for (i in maxOf(0, ci - span)..ci + span) {
                val x = (i + .5f) * step; val y = (j + .5f) * step
                if (x > width || y > height) continue
                val near = 1 - hypot(x - t[0], y - t[1]) / reach
                if (near <= 0) continue
                dot.alpha = (60 + 195 * near).toInt()
                c.drawCircle(x, y, dotR * (1 + 1.6f * near), dot)
            }
        }
        glow.alpha = 46
        for (t in touches.values) c.drawCircle(t[0], t[1], 30 * dm.density, glow)
    }
}

/** Invisible text target: turns whatever the phone keyboard does into keystrokes. */
class KeyCatcher(context: Context, private val onText: (String) -> Unit, private val onKey: (String) -> Unit) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(a: EditorInfo): InputConnection {
        a.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        a.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return Conn()
    }

    private inner class Conn : BaseInputConnection(this, false) {
        private var composing = ""

        /** Swipe typing and autocorrect rewrite a word; send only what changed. */
        private fun replace(new: String) {
            val keep = composing.commonPrefixWith(new).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
            val drop = composing.substring(keep.length)
            repeat(drop.codePointCount(0, drop.length)) { onKey("backspace") }
            type(new.substring(keep.length))
        }

        private fun type(s: String) {
            var run = StringBuilder()
            for (ch in s) {
                if (ch == '\n') { if (run.isNotEmpty()) onText(run.toString()); run = StringBuilder(); onKey("enter") } else run.append(ch)
            }
            if (run.isNotEmpty()) onText(run.toString())
        }

        override fun setComposingText(t: CharSequence, p: Int): Boolean { replace(t.toString()); composing = t.toString(); return true }
        override fun commitText(t: CharSequence, p: Int): Boolean { replace(t.toString()); composing = ""; return true }
        override fun finishComposingText(): Boolean { composing = ""; return true }
        override fun setComposingRegion(s: Int, e: Int) = true

        override fun deleteSurroundingText(before: Int, after: Int): Boolean {
            repeat(before) { onKey("backspace") }
            repeat(after) { onKey("delete") }
            composing = composing.dropLast(before)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int) = deleteSurroundingText(before, after)
        override fun performEditorAction(a: Int): Boolean { onKey("enter"); return true }

        override fun sendKeyEvent(e: KeyEvent): Boolean {
            if (e.action == KeyEvent.ACTION_DOWN) handle(e)
            return true
        }
    }

    private fun handle(e: KeyEvent): Boolean {
        val k = NAMES[e.keyCode]
        if (k != null) onKey(k)
        else if (e.unicodeChar != 0) onText(String(Character.toChars(e.unicodeChar)))
        else return false
        return true
    }

    override fun onKeyDown(code: Int, e: KeyEvent) =
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) super.onKeyDown(code, e)
        else handle(e) || super.onKeyDown(code, e)

    fun show(on: Boolean) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) { requestFocus(); post { imm.showSoftInput(this, 0) } } else { imm.hideSoftInputFromWindow(windowToken, 0); clearFocus() }
    }

    companion object {
        private val NAMES = mapOf(KeyEvent.KEYCODE_DEL to "backspace", KeyEvent.KEYCODE_FORWARD_DEL to "delete",
            KeyEvent.KEYCODE_ENTER to "enter", KeyEvent.KEYCODE_NUMPAD_ENTER to "enter", KeyEvent.KEYCODE_TAB to "tab",
            KeyEvent.KEYCODE_ESCAPE to "esc", KeyEvent.KEYCODE_DPAD_UP to "up", KeyEvent.KEYCODE_DPAD_DOWN to "down",
            KeyEvent.KEYCODE_DPAD_LEFT to "left", KeyEvent.KEYCODE_DPAD_RIGHT to "right", KeyEvent.KEYCODE_MOVE_HOME to "home",
            KeyEvent.KEYCODE_MOVE_END to "end", KeyEvent.KEYCODE_PAGE_UP to "pageup", KeyEvent.KEYCODE_PAGE_DOWN to "pagedown")
    }
}

/** Live view of the laptop screen. Tap clicks there, hold then drag drags, pinch zooms the view. */
/** Width and JPEG quality, from sharpest to lightest. */
private val LEVELS = listOf(1920 to 62, 1600 to 56, 1280 to 50, 1024 to 45, 800 to 40, 640 to 34)

class ScreenView(context: Context, private val hub: Hub, private val haptic: (Int) -> Unit) : View(context) {
    var monitor = 0
    var quality = 60
    @Volatile var monitors = 1; private set
    var onMonitors: (Int) -> Unit = {}
    var onTouch: () -> Unit = {}

    @Volatile private var bmp: Bitmap? = null
    @Volatile private var cur = floatArrayOf(-1f, -1f)
    @Volatile private var alive = false
    @Volatile private var chan: Channel? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_DIM; textAlign = Paint.Align.CENTER; textSize = 15 * resources.displayMetrics.scaledDensity }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_ACCENT; style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
    private val fit = RectF()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val m = Matrix()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alive = true
        thread(name = "syncpad-screen") { loop() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        alive = false
        chan?.close()
        if (dragging) hub.button(0, false)
    }

    // ---- what the link can carry: size and quality follow how long the network takes per frame
    @Volatile var fps = 0f; private set
    @Volatile var netMs = -1; private set
    @Volatile private var lastNew = 0L
    private var level = 1
    private var calm = 0
    private val frames = ArrayDeque<Long>()

    private fun pick(): Pair<Int, Int> {
        val want = (maxOf(width, height) * zoom).toInt().coerceIn(640, 1920)
        if (quality >= 80) return want to 85                     // "Sharper picture": as asked, whatever it costs
        val (w, q) = LEVELS[level]
        return minOf(want, w) to q
    }

    /** Over the network's time for a frame (not the time spent waiting for the screen to change). */
    private fun adapt(ms: Int) {
        netMs = if (netMs < 0) ms else (netMs * 3 + ms) / 4
        if (quality >= 80) return
        if (netMs > 220 && level < LEVELS.size - 1) { level++; netMs = -1; calm = 0 }
        else if (netMs < 90) { if (++calm >= 12 && level > 0) { level--; calm = 0 } } else calm = 0
    }

    private fun loop() {
        // On the internet, start small and grow; nearby, start sharp.
        level = if (hub.status.via.startsWith("Internet")) 3 else 1
        while (alive) {
            val ch = hub.link.openChannel(Wire.SCREEN)
            if (ch == null) { Thread.sleep(700); continue }
            chan = ch
            val sent = ArrayDeque<Long>()
            fun ask() {
                val (w, q) = pick()
                ch.send(JSONObject().put("t", "next").put("v", 2).put("w", w).put("q", q).put("m", monitor).put("wait", 400).toString().toByteArray())
                sent.addLast(System.nanoTime())
            }
            try {
                // Two requests in flight, so the next frame is already on its way while this one draws.
                ask(); ask()
                while (alive) {
                    val d = ch.recv()
                    val took = ((System.nanoTime() - sent.removeFirst()) / 1_000_000).toInt()
                    if (d.size < 10) { ask(); continue }
                    val b = ByteBuffer.wrap(d, 1, d.size - 1)
                    val cx = b.short.toInt() and 0xffff; val cy = b.short.toInt() and 0xffff
                    val fw = b.short.toInt() and 0xffff; b.short
                    val n = b.get().toInt()
                    val waited = if (d.size >= 12) b.short.toInt() and 0xffff else 0
                    val at = if (d.size >= 12) 12 else 10
                    if (n != monitors) { monitors = n; post { onMonitors(n) } }
                    cur = floatArrayOf(cx / 65535f, cy / 65535f)
                    ask()
                    if (fw > 0 && d.size > at) {
                        adapt(maxOf(1, took - waited))
                        BitmapFactory.decodeByteArray(d, at, d.size - at)?.let { bmp = it }
                        val now = System.nanoTime()
                        lastNew = now
                        synchronized(frames) { frames.addLast(now); while (frames.size > 1 && now - frames.first() > 1_000_000_000L) frames.removeFirst(); fps = frames.size.toFloat() }
                    }
                    postInvalidateOnAnimation()
                }
            } catch (_: Exception) {
            } finally { ch.close() }
            if (alive) Thread.sleep(300)
        }
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) {
            c.drawText(if (!hub.online && hub.usingBt) "Screen view needs Wi-Fi or USB" else "Waiting for the laptop screen", width / 2f, height / 2f, text)
            return
        }
        layout(b)
        m.reset()
        m.postScale(fit.width() / b.width, fit.height() / b.height)
        m.postTranslate(fit.left, fit.top)
        c.drawBitmap(b, m, paint)
        val (x, y) = cur
        if (x < 1f || y < 1f) c.drawCircle(fit.left + x * fit.width(), fit.top + y * fit.height(), 7 * resources.displayMetrics.density, ring)
        // Frames per second and the network's share of each frame, top left.
        val still = System.nanoTime() - lastNew > 1_200_000_000L
        val label = (if (still) "Still" else "${fps.toInt()} fps") + if (netMs >= 0) "  ${netMs} ms" else ""
        val d = resources.displayMetrics.density
        val w = badgeText.measureText(label) + 20 * d
        badgeBox.set(10 * d, 10 * d, 10 * d + w, 38 * d)
        c.drawRoundRect(badgeBox, 14 * d, 14 * d, badge)
        c.drawText(label, badgeBox.left + 10 * d, badgeBox.centerY() + badgeText.textSize * .35f, badgeText)
        if (!still) postInvalidateDelayed(500)
    }

    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB30D0E10.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F2.toInt(); textSize = 12.5f * resources.displayMetrics.scaledDensity; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); fontFeatureSettings = "tnum" }
    private val badgeBox = RectF()

    private fun layout(b: Bitmap) {
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        val w = b.width * s; val h = b.height * s
        val mx = maxOf(0f, (w - width) / 2); val my = maxOf(0f, (h - height) / 2)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
        val l = (width - w) / 2 + panX; val t = (height - h) / 2 + panY
        fit.set(l, t, l + w, t + h)
    }

    private var x0 = 0f; private var y0 = 0f; private var lx = 0f; private var ly = 0f
    private var t0 = 0L
    private var moved = false
    private var multi = false
    private var dragging = false
    private var span = 0f
    private val slop = 9 * resources.displayMetrics.density

    private val hold = Runnable {
        dragging = true
        point(x0, y0)
        hub.button(0, true)
        haptic(16)
    }

    private fun point(x: Float, y: Float) = hub.absolute((x - fit.left) / fit.width(), (y - fit.top) / fit.height(), monitor)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouch()
                x0 = e.x; y0 = e.y; lx = e.x; ly = e.y; t0 = e.eventTime
                moved = false; multi = false; dragging = false
                postDelayed(hold, 380)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(hold)
                if (!dragging) { multi = true; mid(e); span = span(e); moved = false }
            }
            MotionEvent.ACTION_MOVE -> when {
                dragging -> point(e.getX(0), e.getY(0))
                multi && e.pointerCount >= 2 -> {
                    val px = lx; val py = ly
                    mid(e)
                    val s = span(e)
                    if (abs(s - span) > slop || hypot(lx - px, ly - py) > 1) moved = true
                    if (span > 1) {
                        val z = (zoom * s / span).coerceIn(1f, 5f)
                        val k = z / zoom
                        panX = (panX + width / 2f - lx) * k + lx - width / 2f
                        panY = (panY + height / 2f - ly) * k + ly - height / 2f
                        zoom = z
                    }
                    span = s
                    if (zoom > 1.02f) { panX += lx - px; panY += ly - py }
                    else hub.scroll(-(lx - px) * 1.4f, (ly - py) * 1.4f * if (hub.natural) 1 else -1)
                    invalidate()
                }
                !multi -> {
                    if (!moved && hypot(e.x - x0, e.y - y0) > slop) { moved = true; removeCallbacks(hold); lx = e.x; ly = e.y }
                    if (moved) {
                        if (zoom > 1.02f) { panX += e.x - lx; panY += e.y - ly; invalidate() } else point(e.x, e.y)
                        lx = e.x; ly = e.y
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                when {
                    dragging -> { hub.button(0, false); dragging = false }
                    multi -> if (!moved && e.eventTime - t0 < 300) { point(lx, ly); hub.click(1); haptic(12) }
                    !moved -> { point(e.x, e.y); hub.click(0); haptic(8) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (dragging) { hub.button(0, false); dragging = false }
            }
        }
        return true
    }

    private fun mid(e: MotionEvent) { lx = (e.getX(0) + e.getX(1)) / 2; ly = (e.getY(0) + e.getY(1)) / 2 }
    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
}

/** Landscape game controller. */
class GamepadView(context: Context, private val hub: Hub, private val haptic: (Int) -> Unit) : View(context) {
    private class Btn(val bit: Int, val label: String, val x: Float, val y: Float, val r: Float)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private var btns = emptyList<Btn>()
    private val stick = Array(2) { FloatArray(2) }     // centres
    private val value = Array(2) { FloatArray(2) }
    private var stickR = 0f
    private val owner = HashMap<Int, Any>()            // pointer to stick index or Btn
    private var sent = ""
    private var ticking = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Laid out on a 2:1 box, so no screen shape can make controls overlap.
        val u = min(w / 20f, h / 10f)
        val ox = w - 20 * u; val cy = h - 10 * u
        stickR = 1.75f * u
        stick[0][0] = 3.2f * u; stick[0][1] = cy + 4.6f * u
        stick[1][0] = ox + 13.2f * u; stick[1][1] = cy + 7.6f * u
        fun b(bit: Int, l: String, x: Float, y: Float, r: Float, right: Boolean) = Btn(bit, l, x * u + if (right) ox else 0f, cy + y * u, r * u)
        btns = listOf(
            b(0, "A", 17.2f, 6.1f, .85f, true), b(1, "B", 18.8f, 4.5f, .85f, true),
            b(2, "X", 15.6f, 4.5f, .85f, true), b(3, "Y", 17.2f, 2.9f, .85f, true),
            b(10, "▲", 6.6f, 6.4f, .68f, false), b(11, "▼", 6.6f, 8.8f, .68f, false),
            b(12, "◀", 5.4f, 7.6f, .68f, false), b(13, "▶", 7.8f, 7.6f, .68f, false),
            b(16, "LT", 1.4f, 1f, .8f, false), b(4, "LB", 3.6f, 1f, .8f, false),
            b(5, "RB", 16.4f, 1f, .8f, true), b(17, "RT", 18.6f, 1f, .8f, true),
            Btn(6, "BACK", w / 2f - 1.5f * u, cy + 1.3f * u, .75f * u), Btn(7, "START", w / 2f + 1.5f * u, cy + 1.3f * u, .75f * u),
            b(8, "L3", 1f, 8.8f, .6f, false), b(9, "R3", 19f, 8.8f, .6f, true))
    }

    private fun bits() = owner.values.filterIsInstance<Btn>().fold(0) { a, b -> a or (1 shl b.bit) }

    private fun push(force: Boolean = false) {
        val b = bits()
        val key = "$b ${value[0].joinToString()} ${value[1].joinToString()}"
        if (key == sent && !force) return
        sent = key
        hub.pad(b and 0xFFFF, value[0][0], value[0][1], value[1][0], value[1][1],
            if (b shr 16 and 1 == 1) 1f else 0f, if (b shr 17 and 1 == 1) 1f else 0f)
        // The keyboard fallback turns the right stick into mouse motion, which needs a steady beat.
        if (!ticking && (value[1][0] != 0f || value[1][1] != 0f)) { ticking = true; Choreographer.getInstance().postFrameCallback(beat) }
    }

    private val beat = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            if (value[1][0] == 0f && value[1][1] == 0f) { ticking = false; return }
            push(true)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
                val i = e.actionIndex
                val hit = btns.minByOrNull { hypot(e.getX(i) - it.x, e.getY(i) - it.y) - it.r }
                    ?.takeIf { hypot(e.getX(i) - it.x, e.getY(i) - it.y) < it.r * 1.25f }
                if (hit != null) { owner[e.getPointerId(i)] = hit; haptic(8) }
                else for (s in 0..1) if (hypot(e.getX(i) - stick[s][0], e.getY(i) - stick[s][1]) < stickR * 1.6f && s !in owner.values) {
                    owner[e.getPointerId(i)] = s
                    aim(s, e.getX(i), e.getY(i))
                    break
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) (owner[e.getPointerId(i)] as? Int)?.let { aim(it, e.getX(i), e.getY(i)) }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                (owner.remove(e.getPointerId(e.actionIndex)) as? Int)?.let { value[it].fill(0f) }
            MotionEvent.ACTION_CANCEL -> { owner.clear(); value.forEach { it.fill(0f) } }
        }
        push()
        invalidate()
        return true
    }

    private fun aim(s: Int, x: Float, y: Float) {
        val dx = (x - stick[s][0]) / stickR; val dy = (y - stick[s][1]) / stickR
        val n = maxOf(1f, hypot(dx, dy))
        value[s][0] = dx / n; value[s][1] = dy / n
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        owner.clear(); value.forEach { it.fill(0f) }
        push(true)
        Choreographer.getInstance().removeFrameCallback(beat)
    }

    override fun onDraw(c: Canvas) {
        for (s in 0..1) {
            fill.color = C_PANEL
            c.drawCircle(stick[s][0], stick[s][1], stickR, fill)
            fill.color = if (s in owner.values) C_ACCENT else C_LINE
            c.drawCircle(stick[s][0] + value[s][0] * stickR * .6f, stick[s][1] + value[s][1] * stickR * .6f, stickR * .48f, fill)
        }
        val on = owner.values
        for (b in btns) {
            val held = b in on
            fill.color = if (held) C_ACCENT else C_PANEL
            c.drawCircle(b.x, b.y, b.r, fill)
            text.color = if (held) 0xFF0D0E10.toInt() else C_TEXT
            text.textSize = if (b.label.length > 2) b.r * .42f else b.r * .72f
            c.drawText(b.label, b.x, b.y + text.textSize * .36f, text)
        }
    }
}
