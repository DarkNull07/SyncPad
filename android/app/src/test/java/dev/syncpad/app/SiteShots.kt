package dev.syncpad.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.net.Phase
import dev.syncpad.app.net.Status
import dev.syncpad.app.ui.Root
import dev.syncpad.app.ui.Shell
import dev.syncpad.app.ui.Tab
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor
import java.io.File

/** The app's real screens, for the website. Run with: gradlew :app:testDebugUnitTest --tests '*SiteShots' -Psite */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-480dpi")
class SiteShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val hub get() = ApplicationProvider.getApplicationContext<App>().hub

    @Before fun only() = assumeTrue(System.getProperty("syncpad.site") == "true")

    private fun idle(ms: Long = 60) { Thread.sleep(ms); shadowOf(Looper.getMainLooper()).idle(); rule.waitForIdle() }

    private fun shell() = object : Shell {
        override val hub = this@SiteShots.hub
        override fun haptic(ms: Int) {}
        override fun scan() {}
        override fun paste() {}
        override fun landscape(on: Boolean) {}
        override fun enableBluetooth() {}
        override fun discoverable() {}
        override fun allowFiles() {}
    }

    private fun save(name: String) {
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(Canvas(b)) }
        File(System.getProperty("syncpad.shots")!!, "site/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(name: String, tab: Tab, sub: String? = null, via: String = "USB", rtt: Int = 2, then: () -> Unit = {}) {
        hub.padHint = false
        hub.pair(Laptop("0011223344556677", "8899aabbccddeeff", "ab".repeat(32), "Studio laptop", 9, emptyList()))
        idle(); hub.link.stop(); idle()
        hub.link.onStatus(Status(Phase.CONNECTED, via, rtt, via == "Wi-Fi"))
        rule.setContent { Root(shell(), tab, sub) }
        idle(); then(); idle()
        save(name)
    }

    private fun touch(d: String, x: Float, y: Float) = rule.onNode(hasContentDescription(d)).performTouchInput { down(androidx.compose.ui.geometry.Offset(width * x, height * y)) }

    @Test fun pad() = screen("pad", Tab.PAD)
    @Test fun keys() = screen("keys", Tab.PAD) { rule.onNode(hasContentDescription("Extra keys")).performClick() }
    @Test fun air() {
        val sm = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        screen("air", Tab.AIR, via = "Wi-Fi", rtt = 5) { touch("Hold to point", .5f, .5f) }
    }
    @Test fun slides() = screen("slides", Tab.AIR, via = "Wi-Fi", rtt = 5) { rule.onNodeWithText("Slides").performClick() }
    @Test fun deck() = screen("deck", Tab.DECK)
    @Test fun shortcut() = screen("shortcut", Tab.DECK) {
        rule.onNodeWithText("Add key").performClick(); rule.waitForIdle()
        rule.runOnUiThread { hub.recorded = listOf("win", "shift", "s") }; idle()
        rule.runOnUiThread { hub.recorded = listOf("ctrl", "v") }; idle()
        rule.onNodeWithText("Pick keys").performClick()
    }
    @Test fun media() = screen("media", Tab.MORE, "remote")
    @Test fun modes() = screen("modes", Tab.PAD) { rule.onNodeWithText("2 ms").performClick() }
    @Test fun share() = screen("share", Tab.MORE, "files") {
        rule.runOnUiThread {
            hub.transfers += Transfer("trail-run-final.mp4", 812_000_000, true).apply { done = 506_000_000; rate = 24_600_000f }
            hub.transfers += Transfer("Reading list.pdf", 1_840_000, false).apply { state = "Saved"; uri = android.net.Uri.parse("content://x/1") }
            hub.transfers += Transfer("IMG_2041.jpg", 4_100_000, true).apply { state = "Sent" }
        }
    }
    @Test @Config(qualifiers = "w760dp-h360dp-land-480dpi") fun gamepad() = screen("gamepad", Tab.MORE, "gamepad")

    private fun picture(file: File, sky: Int, low: Int, hill: Int, sun: Int) {
        val b = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, 1200f, 800f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 640f, sky, low, Shader.TileMode.CLAMP) })
            drawCircle(800f, 330f, 92f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sun })
            for (k in 0..2) {
                val p = Path().apply {
                    moveTo(0f, 800f); lineTo(0f, 520f + 80 * k)
                    cubicTo(300f, 400f + 100 * k, 520f, 640f + 50 * k, 760f, 500f + 90 * k)
                    cubicTo(920f, 420f + 110 * k, 1060f, 560f + 60 * k, 1200f, 480f + 100 * k); lineTo(1200f, 800f); close()
                }
                drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hill; alpha = 110 + 60 * k })
            }
        }
        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** Served by the real laptop app, so this is the real file browser. */
    @Test fun files() {
        val host = File(System.getProperty("syncpad.host")!!)
        val cfg = java.nio.file.Files.createTempDirectory("cfg").toFile()
        val box = File(java.nio.file.Files.createTempDirectory("b").toFile(), "Field trip").apply { mkdirs() }
        listOf("Maps", "Recordings").forEach { File(box, it).mkdir() }
        picture(File(box, "ridge at dawn.jpg"), 0xFF2B3A5C.toInt(), 0xFFF0A36B.toInt(), 0xFF1B2433.toInt(), 0xFFFFE1B0.toInt())
        picture(File(box, "north valley.jpg"), 0xFF3F7C8C.toInt(), 0xFFCFE5D2.toInt(), 0xFF1F4A43.toInt(), 0xFFF6F3DC.toInt())
        File(box, "packing list.txt").writeText("Tent and poles\nTwo batteries\nWater filter\n")
        File(box, "route notes.pdf").writeBytes(ByteArray(2_400_000))
        File(box, "stream crossing.mp4").writeBytes(ByteArray(18_600_000))
        File(cfg, "config.json").writeText(org.json.JSONObject().put("host_id", "0011223344556677").put("settings", org.json.JSONObject().put("save_dir", box.path)).toString())
        val port = java.net.ServerSocket(0).use { it.localPort }
        val p = ProcessBuilder(File(host, ".venv/Scripts/python.exe").path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--port", "$port", "--config", cfg.path).directory(host).redirectErrorStream(true).start()
        fun has(t: String) = rule.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()
        fun await(ok: () -> Boolean) { val end = System.currentTimeMillis() + 12_000; while (!ok()) { check(System.currentTimeMillis() < end); idle(30) } }
        try {
            hub.pair(Laptop.parse(p.inputStream.bufferedReader().readLine())!!)
            rule.setContent { Root(shell(), Tab.MORE, "browse") }
            await { hub.status.phase == Phase.CONNECTED && has("Drives") }
            rule.onNodeWithText("SyncPad").performScrollTo().performClick()
            await { has("packing list.txt") }
            idle(1800)
            save("files")
            rule.onNodeWithText("ridge at dawn.jpg").performClick(); idle(1500)
            save("files_preview")
        } finally { hub.link.stop(); p.destroyForcibly(); cfg.deleteRecursively(); box.parentFile?.deleteRecursively() }
    }
}
