package dev.syncpad.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.core.content.ContextCompat
import dev.syncpad.app.input.Sink
import kotlin.math.abs
import kotlin.math.roundToInt

enum class HidPhase { UNAVAILABLE, OFF, NO_PERMISSION, READY, CONNECTED }

data class HidStatus(val phase: HidPhase, val name: String = "")

/** Makes the phone a standard Bluetooth mouse, keyboard, media remote and gamepad. */
@SuppressLint("MissingPermission")      // every entry point checks allowed() first
class HidLink(private val ctx: Context, private val onStatus: (HidStatus) -> Unit) : Sink {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val thread = HandlerThread("syncpad-hid", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val h = Handler(thread.looper)
    private var proxy: BluetoothHidDevice? = null
    private var wanted = false
    @Volatile private var host: BluetoothDevice? = null

    /** Address to reconnect to by itself, and where to remember a new one. */
    var lastHost: String? = null
    var onHost: (String) -> Unit = {}

    val connected get() = host != null
    val supported get() = adapter != null

    private fun allowed() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        val a = adapter ?: return onStatus(HidStatus(HidPhase.UNAVAILABLE))
        if (!allowed()) return onStatus(HidStatus(HidPhase.NO_PERMISSION))
        if (!a.isEnabled) return onStatus(HidStatus(HidPhase.OFF))
        if (wanted) return
        wanted = true
        val ok = a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                proxy = p as BluetoothHidDevice
                if (wanted && !register()) onStatus(HidStatus(HidPhase.UNAVAILABLE))
            }

            override fun onServiceDisconnected(profile: Int) {
                proxy = null; host = null; wanted = false
                onStatus(HidStatus(HidPhase.OFF))
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { wanted = false; onStatus(HidStatus(HidPhase.UNAVAILABLE)) }
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        h.post {
            releaseAll()
            proxy?.let { p ->
                runCatching { host?.let { p.disconnect(it) } }
                runCatching { p.unregisterApp() }
                runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p) }
            }
            proxy = null; host = null
            onStatus(HidStatus(HidPhase.OFF))
        }
    }

    private fun register(): Boolean = runCatching {
        val sdp = BluetoothHidDeviceAppSdpSettings("SyncPad", "Phone as mouse and keyboard", "SyncPad",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR)
        // Ask for a channel that keeps up with one report per slot pair, instead of whatever is left over.
        val qos = BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED, 1200, 9, 0, 11_250, BluetoothHidDeviceAppQosSettings.MAX)
        proxy!!.registerApp(sdp, null, qos, { h.post(it) }, callback) || proxy!!.registerApp(sdp, null, null, { h.post(it) }, callback)
    }.getOrDefault(false)

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            onStatus(HidStatus(if (registered) HidPhase.READY else HidPhase.OFF))
            if (!registered) host = null
            else if (host == null) bonded().firstOrNull { it.address == lastHost }?.let { connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                clear()
                if (lastHost != device.address) { lastHost = device.address; onHost(device.address) }
                onStatus(HidStatus(HidPhase.CONNECTED, runCatching { device.name }.getOrNull() ?: "Laptop"))
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && host == device) {
                host = null
                fineScroll = false; fineAcross = false
                onStatus(HidStatus(HidPhase.READY))
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (id.toInt() == 1 && type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
                proxy?.replyReport(device, type, id, byteArrayOf(((if (fineScroll) 1 else 0) or (if (fineAcross) 4 else 0)).toByte()))
                return
            }
            val size = when (id.toInt()) { 1 -> 7; 2 -> 8; 3 -> 2; 4 -> 8; else -> 0 }
            if (size > 0) proxy?.replyReport(device, type, id, ByteArray(size))
            else proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Windows switching on fine scrolling. Until it does, whole notches are sent.
            if (id.toInt() == 1 && type == BluetoothHidDevice.REPORT_TYPE_FEATURE && data.isNotEmpty()) {
                val v = data.last().toInt()
                fineScroll = v and 3 != 0
                fineAcross = v shr 2 and 3 != 0
            }
            proxy?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    /** Computers paired with this phone. */
    fun bonded(): List<BluetoothDevice> =
        if (!allowed()) emptyList() else runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()

    fun name(d: BluetoothDevice) = runCatching { d.name }.getOrNull() ?: d.address

    fun connect(d: BluetoothDevice) = runCatching { proxy?.connect(d) ?: false }.getOrDefault(false)

    // ---- reports. Everything below runs on the HID thread.
    @Volatile var speed = 1f
    private var fineScroll = false
    private var fineAcross = false
    private var buttons = 0
    private var ax = 0f
    private var ay = 0f
    private var ws = 0f
    private var hs = 0f
    private var scheduled = false
    private var nextAt = 0L
    private var mods = 0
    private val down = ArrayList<Int>()

    private fun clear() { buttons = 0; ax = 0f; ay = 0f; ws = 0f; hs = 0f; mods = 0; down.clear() }

    private fun send(id: Int, data: ByteArray) { host?.let { d -> runCatching { proxy?.sendReport(d, id, data) } } }

    private fun releaseAll() {
        clear()
        send(1, ByteArray(7)); send(2, ByteArray(8)); send(3, ByteArray(2))
    }

    private fun take(v: Float, lim: Int): Int = v.toInt().coerceIn(-lim, lim)

    /** Bluetooth queues reports, and a queue is lag, so motion is merged to one report per 8 ms. */
    private val flush = Runnable {
        scheduled = false
        nextAt = android.os.SystemClock.uptimeMillis() + 8
        sendMouse()
        if (abs(ax) >= 1 || abs(ay) >= 1 || wheelDue()) schedule()
    }

    private fun wheelDue() = abs(ws) >= (if (fineScroll) 1f else 120f) || abs(hs) >= (if (fineAcross) 1f else 120f)

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        h.postAtTime(flush, maxOf(nextAt, android.os.SystemClock.uptimeMillis()))
    }

    private fun sendMouse() {
        val x = take(ax, 32767); val y = take(ay, 32767)
        ax -= x; ay -= y
        val wu = if (fineScroll) 1f else 120f; val pu = if (fineAcross) 1f else 120f
        val w = take(ws / wu, 127); val p = take(hs / pu, 127)
        ws -= w * wu; hs -= p * pu
        send(1, byteArrayOf(buttons.toByte(), x.toByte(), (x shr 8).toByte(), y.toByte(), (y shr 8).toByte(), w.toByte(), p.toByte()))
    }

    override fun move(dx: Float, dy: Float) { h.post { ax += dx * speed; ay += dy * speed; schedule() } }

    override fun scroll(dx: Float, dy: Float) {
        h.post { hs += dx; ws += dy; if (wheelDue()) schedule() }
    }

    private var zoomAcc = 0f
    override fun zoom(units: Float) {
        h.post {
            zoomAcc += units
            val n = (zoomAcc / 120f).toInt()
            if (n == 0) return@post
            zoomAcc -= n * 120f
            keyNow("ctrl", true)
            ws += n * 120f
            // All of it while Ctrl is down; a fine wheel holds one notch per report.
            do sendMouse() while (wheelDue())
            keyNow("ctrl", false)
        }
    }

    override fun button(b: Int, down: Boolean) {
        h.post {
            buttons = if (down) buttons or (1 shl b) else buttons and (1 shl b).inv()
            sendMouse()
        }
    }

    override fun key(k: String, down: Boolean) { h.post { keyNow(k, down) } }

    override fun combo(vararg keys: String) {
        h.post {
            keys.forEach { keyNow(it, true) }
            keys.reversed().forEach { keyNow(it, false) }
        }
    }

    override fun text(s: String) {
        h.post {
            for (c in s) {
                val (usage, shift) = forChar(c) ?: continue
                val keep = mods
                if (shift) mods = mods or 2
                down.add(usage); sendKeys()
                down.remove(usage); mods = keep; sendKeys()
            }
        }
    }

    private fun keyNow(k: String, on: Boolean) {
        val m = MODS[k]
        val media = MEDIA[k]
        when {
            m != null -> { mods = if (on) mods or m else mods and m.inv(); sendKeys() }
            media != null -> send(3, if (on) byteArrayOf(media.toByte(), (media shr 8).toByte()) else ByteArray(2))
            else -> {
                val u = usage(k) ?: return
                if (on) { if (u !in down && down.size < 6) down.add(u) } else down.remove(u)
                sendKeys()
            }
        }
    }

    private fun sendKeys() {
        val r = ByteArray(8)
        r[0] = mods.toByte()
        down.take(6).forEachIndexed { i, u -> r[2 + i] = u.toByte() }
        send(2, r)
    }

    override fun pad(b: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        fun a(v: Float) = (v * 127).roundToInt().coerceIn(-127, 127).toByte()
        fun t(v: Float) = (v * 255).roundToInt().coerceIn(0, 255).toByte()
        h.post { send(4, byteArrayOf(b.toByte(), (b shr 8).toByte(), a(lx), a(ly), a(rx), a(ry), t(lt), t(rt))) }
    }

    companion object {
        private val MODS = mapOf("ctrl" to 1, "shift" to 2, "alt" to 4, "win" to 8, "rctrl" to 16, "ralt" to 64)
        private val MEDIA = mapOf("play_pause" to 0xCD, "next" to 0xB5, "prev" to 0xB6, "stop" to 0xB7,
            "vol_up" to 0xE9, "vol_down" to 0xEA, "mute" to 0xE2, "browser_back" to 0x224, "browser_forward" to 0x225)
        private val NAMED = mapOf("enter" to 0x28, "esc" to 0x29, "backspace" to 0x2A, "tab" to 0x2B, "space" to 0x2C,
            "capslock" to 0x39, "printscreen" to 0x46, "pause" to 0x48, "scrolllock" to 0x47, "numlock" to 0x53, "insert" to 0x49, "home" to 0x4A, "pageup" to 0x4B,
            "delete" to 0x4C, "end" to 0x4D, "pagedown" to 0x4E, "right" to 0x4F, "left" to 0x50, "down" to 0x51,
            "up" to 0x52, "menu" to 0x65)
        private const val PLAIN = "-=[]\\;'`,./"
        private val PLAIN_USAGE = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
        private const val SHIFTED = "_+{}|:\"~<>?"
        private const val DIGIT_SHIFT = "!@#$%^&*()"

        fun usage(k: String): Int? = NAMED[k] ?: when {
            k.length in 2..3 && k[0] == 'f' -> k.drop(1).toIntOrNull()?.takeIf { it in 1..12 }?.let { 0x39 + it }
            k.length == 1 -> forChar(k[0])?.first
            else -> null
        }

        /** US layout usage and whether Shift is needed. Bluetooth keyboards cannot send arbitrary Unicode. */
        fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
            in 'a'..'z' -> 0x04 + (c - 'a') to false
            in 'A'..'Z' -> 0x04 + (c - 'A') to true
            in '1'..'9' -> 0x1E + (c - '1') to false
            '0' -> 0x27 to false
            ' ' -> 0x2C to false
            '\n' -> 0x28 to false
            '\t' -> 0x2B to false
            else -> {
                val p = PLAIN.indexOf(c); val s = SHIFTED.indexOf(c); val d = DIGIT_SHIFT.indexOf(c)
                when {
                    p >= 0 -> PLAIN_USAGE[p] to false
                    s >= 0 -> PLAIN_USAGE[s] to true
                    d >= 0 -> 0x1E + d to true
                    else -> null
                }
            }
        }

        private fun bytes(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

        val DESCRIPTOR = bytes("""
            05 01 09 02 A1 01 85 01 09 01 A1 00 05 09 19 01 29 05 15 00 25 01 95 05 75 01 81 02 95 01 75 03 81 03
            05 01 09 30 09 31 16 01 80 26 FF 7F 75 10 95 02 81 06
            A1 02 09 48 15 00 25 01 35 01 45 78 75 02 95 01 B1 02 35 00 45 00 09 38 15 81 25 7F 75 08 95 01 81 06 C0
            A1 02 09 48 15 00 25 01 35 01 45 78 75 02 95 01 B1 02 35 00 45 00 75 04 B1 03
            05 0C 0A 38 02 15 81 25 7F 75 08 95 01 81 06 C0 C0 C0
            05 01 09 06 A1 01 85 02 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 95 01 75 08 81 01
            95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0
            05 0C 09 01 A1 01 85 03 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0
            05 01 09 05 A1 01 85 04 05 09 19 01 29 10 15 00 25 01 75 01 95 10 81 02
            05 01 09 30 09 31 09 32 09 35 15 81 25 7F 75 08 95 04 81 02
            05 02 09 C5 09 C4 15 00 26 FF 00 75 08 95 02 81 02 C0""")
    }
}
