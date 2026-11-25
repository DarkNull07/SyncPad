package dev.syncpad.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.ui.Root
import dev.syncpad.app.ui.Shell
import dev.syncpad.app.ui.Tab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders every screen for real, which catches crashes and lets a person look at them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, tab: Tab, sub: String? = null, paired: Boolean = true, then: () -> Unit = {}) {
        val hub = (ApplicationProvider.getApplicationContext<App>()).hub
        if (paired) hub.pair(Laptop("0011223344556677", "8899aabbccddeeff", "ab".repeat(32), "MY-PC", 9, emptyList()))
        val shell = object : Shell {
            override val hub = hub
            override fun haptic(ms: Int) {}
            override fun scan() {}
            override fun paste() {}
            override fun landscape(on: Boolean) {}
            override fun enableBluetooth() {}
            override fun discoverable() {}
            override fun allowFiles() {}
        }
        rule.mainClock.autoAdvance = true
        rule.setContent { Root(shell, tab, sub) }
        rule.waitForIdle()
        then()
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(b)) }
        val f = File(System.getProperty("syncpad.shots")!!, "$name.png").apply { parentFile!!.mkdirs() }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        hub.link.stop()
    }

    private val hub get() = ApplicationProvider.getApplicationContext<App>().hub

    @Test fun welcome() = shot("welcome", Tab.PAD, paired = false)
    @Test fun pad() = shot("pad", Tab.PAD)
    @Test fun keyboard() = shot("keyboard", Tab.PAD) { rule.onNodeWithContentDescriptionClick("Keyboard") }
    @Test fun air() = shot("air", Tab.AIR)
    @Test fun slides() = shot("slides", Tab.AIR) { rule.onNodeWithText("Slides").performClick() }
    @Test fun screen() = shot("screen", Tab.SCREEN)
    @Test fun deck() = shot("deck", Tab.DECK)
    @Test fun extraKeys() = shot("extra_keys", Tab.PAD) { rule.onNodeWithContentDescriptionClick("Extra keys") }
    /** Exactly what was asked: the button is always there, and the keyboard brings the keys with it. */
    @Test fun extraKeysFollowTheKeyboard() = shot("extra_follow", Tab.PAD) {
        fun shown() = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Esc")).fetchSemanticsNodes().isNotEmpty()
        org.junit.Assert.assertFalse(shown())
        rule.onNodeWithContentDescriptionClick("Keyboard"); rule.waitForIdle(); org.junit.Assert.assertTrue(shown())
        rule.onNodeWithContentDescriptionClick("Keyboard"); rule.waitForIdle(); org.junit.Assert.assertFalse(shown())
        rule.onNodeWithContentDescriptionClick("Extra keys"); rule.waitForIdle(); org.junit.Assert.assertTrue(shown())
        rule.onNodeWithContentDescriptionClick("Keyboard"); rule.waitForIdle()
        rule.onNodeWithContentDescriptionClick("Keyboard"); rule.waitForIdle(); org.junit.Assert.assertTrue(shown())
        rule.onNodeWithContentDescriptionClick("Extra keys"); rule.waitForIdle(); org.junit.Assert.assertFalse(shown())
    }
    @Test fun shortcutPicker() = shot("shortcut", Tab.DECK) {
        rule.onNodeWithText("Add key").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Pick keys").performClick(); rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Win")).performClick()
        rule.onNodeWithText("PrtScn").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("Win + PrtScn").assertExists()
        rule.runOnUiThread { hub.recorded = listOf("ctrl", "shift", "esc") }; rule.waitForIdle()
        rule.onNodeWithText("Ctrl + Shift + Esc").assertExists()
    }
    /** The real screens against the real laptop app, with a folder made for the purpose. */
    @Test fun browseLive() {
        val host = File(System.getProperty("syncpad.host")!!)
        val python = File(host, ".venv/Scripts/python.exe")
        org.junit.Assume.assumeTrue(python.exists())
        val cfg = java.nio.file.Files.createTempDirectory("cfg").toFile()
        val box = java.nio.file.Files.createTempDirectory("box").toFile()
        File(box, "Semester 5").mkdir(); File(box, "Project photos").mkdir()
        File(box, "Lecture notes week 9.pdf").writeBytes(ByteArray(2_400_000))
        File(box, "todo.txt").writeText("Finish the rover report\nBook the lab\nCall the hostel office\n")
        File(box, "demo-recording.mp4").writeBytes(ByteArray(900_000))
        listOf("sunset.jpg" to 0xFFE08A4B.toInt(), "circuit.png" to 0xFF3C7A6B.toInt()).forEach { (n, c) ->
            val b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(b).apply { drawColor(c); drawCircle(450f, 300f, 170f, android.graphics.Paint().apply { color = 0xFFF5E6C8.toInt() }) }
            File(box, n).outputStream().use { o -> b.compress(if (n.endsWith("png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, o) }
        }
        File(cfg, "config.json").writeText(org.json.JSONObject().put("host_id", "0011223344556677").put("settings", org.json.JSONObject().put("save_dir", box.path)).toString())
        val port = java.net.ServerSocket(0).use { it.localPort }
        val p = ProcessBuilder(python.path, "-u", "syncpad_host.py", "--console", "--dry-run", "--local-only", "--port", "$port", "--config", cfg.path).directory(host).redirectErrorStream(true).start()
        val link = p.inputStream.bufferedReader().readLine()
        fun await(what: String, ok: () -> Boolean) {
            val end = System.currentTimeMillis() + 12_000
            while (!ok()) {
                check(System.currentTimeMillis() < end) { what }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(25)
            }
        }
        fun has(t: String) = rule.onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().isNotEmpty()
        try {
            shot("browse_folder", Tab.MORE, "browse", paired = false) {
                rule.runOnUiThread { hub.pair(Laptop.parse(link)!!) }
                await("connected") { hub.status.phase == dev.syncpad.app.net.Phase.CONNECTED }
                await("roots") { has("Drives") }
                rule.onNodeWithText("SyncPad").performScrollTo().performClick()
                await("folder") { has("todo.txt") }
                Thread.sleep(1500); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            hub.link.stop(); rule.waitForIdle()
            rule.runOnUiThread { hub.foreground() }
            await("again") { hub.status.phase == dev.syncpad.app.net.Phase.CONNECTED && has("sunset.jpg") }
            rule.onNodeWithText("sunset.jpg").performClick(); rule.waitForIdle()
            Thread.sleep(1200); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); rule.waitForIdle()
            rule.onNodeWithText("Open on laptop").assertExists()
            save("browse_sheet")
            rule.onNodeWithText("Delete").performClick(); rule.waitForIdle()
            rule.onNodeWithText("Delete sunset.jpg?").assertExists()
            rule.onNodeWithText("Cancel").performClick(); rule.waitForIdle()
            org.junit.Assert.assertTrue(File(box, "sunset.jpg").exists())
        } finally { hub.link.stop(); p.destroyForcibly(); cfg.deleteRecursively(); box.deleteRecursively() }
    }

    private fun save(name: String) {
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(android.graphics.Canvas(b)) }
        File(System.getProperty("syncpad.shots")!!, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun modeSheet() = shot("mode_sheet", Tab.PAD) {
        rule.onNodeWithText("Searching").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Connect with").assertExists()
        rule.onNodeWithText("USB").performClick(); rule.waitForIdle()
        org.junit.Assert.assertEquals(dev.syncpad.app.net.Mode.USB, hub.mode)
        rule.onNodeWithText("Finding USB").performClick(); rule.waitForIdle()
    }
    @Test fun modeIsRemembered() = shot("mode_more", Tab.MORE, "connect") {
        rule.onNodeWithText("Wi-Fi").performClick(); rule.waitForIdle()
        org.junit.Assert.assertEquals(dev.syncpad.app.net.Mode.WIFI, Hub(ApplicationProvider.getApplicationContext()).mode)
    }
    /** A common 1080 by 2400 phone, less its status and navigation bars: the whole pad must show without scrolling. */
    @Test @Config(qualifiers = "w360dp-h730dp-480dpi") fun deckFitsThisPhone() = shot("deck_360", Tab.DECK) {
        val root = rule.onNodeWithText("Deck").fetchSemanticsNode().boundsInRoot.top
        val left = rule.onNode(androidx.compose.ui.test.hasContentDescription("Left click")).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("buttons end at ${left.bottom}, dock starts near $root", left.bottom <= root && left.height > 100)
        val pad = rule.onNode(androidx.compose.ui.test.hasContentDescription("Mouse pad")).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("pad is ${pad.height / 3} dp tall", pad.height / 3 >= 148)
    }
    private fun manyKeys() = rule.runOnUiThread { hub.saveDeck(List(22) { DeckKey("Key ${it + 1}", "keys", "f5", it % 6) }) }
    /** With many keys the pad moves down; swiping the keys scrolls, swiping the pad does not. */
    @Test fun deckScrollsButThePadDoesNot() = shot("deck_many", Tab.DECK) {
        manyKeys(); rule.waitForIdle()
        fun top(t: String) = rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top
        val before = top("Key 1")
        rule.onNodeWithText("Key 8").performTouchInput { swipeUp(startY = centerY, endY = centerY - 500f) }; rule.waitForIdle()
        org.junit.Assert.assertTrue("keys scroll the page", top("Add key") != before)
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Mouse pad")).performScrollTo(); rule.waitForIdle()
        val at = top("Add key")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Mouse pad")).performTouchInput { swipeDown(startY = centerY - 100f, endY = centerY + 300f) }; rule.waitForIdle()
        org.junit.Assert.assertEquals("the pad must not scroll the page", at, top("Add key"), 1f)
    }
    @Test fun browseOffline() = shot("browse_offline", Tab.MORE, "browse")
    @Test fun deckEdit() = shot("deck_edit", Tab.DECK) { rule.onNodeWithText("Add key").performClick() }
    @Test fun more() = shot("more", Tab.MORE)
    @Test fun remote() = shot("remote", Tab.MORE, "remote")
    @Test fun files() = shot("files", Tab.MORE, "files")
    @Test fun filesBusy() = shot("files_busy", Tab.MORE, "files") {
        rule.runOnUiThread {
            hub.transfers += Transfer("holiday-in-goa-2026-final-cut.mp4", 812_000_000, true).apply { done = 342_000_000; rate = 24_600_000f }
            hub.transfers += Transfer("Assignment 4.pdf", 1_840_000, false).apply { state = "Saved"; uri = android.net.Uri.parse("content://x/1") }
            hub.transfers += Transfer("IMG_20261124_181203.jpg", 4_100_000, true).apply { state = "Sent" }
            hub.transfers += Transfer("backup.zip", 90_000_000, true).apply { state = "Failed: the connection dropped" }
        }
    }
    @Test fun connect() = shot("connect", Tab.MORE, "connect")
    @Test fun settings() = shot("settings", Tab.MORE, "settings")
    @Test @Config(qualifiers = "w891dp-h411dp-land-420dpi") fun gamepad() = shot("gamepad", Tab.MORE, "gamepad")
    /** A small phone with large text: nothing may be cut off. */
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallShortcut() = shot("small_shortcut", Tab.DECK) {
        rule.onNodeWithText("Add key").performScrollTo().performClick(); rule.waitForIdle(); rule.onNodeWithText("Pick keys").performScrollTo().performClick()
    }
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallMode() = shot("small_mode", Tab.PAD) { rule.onNodeWithText("Searching").performClick() }
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallDeck() = shot("small_deck", Tab.DECK)
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallPad() = shot("small_pad", Tab.PAD) { rule.onNodeWithContentDescriptionClick("Keyboard") }
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallMedia() = shot("small_media", Tab.MORE, "remote")
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallWelcome() = shot("small_welcome", Tab.PAD, paired = false)
    @Test @Config(qualifiers = "w340dp-h620dp-320dpi", fontScale = 1.3f) fun smallSlides() = shot("small_slides", Tab.AIR) { rule.onNodeWithText("Slides").performClick() }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescriptionClick(s: String) {
    onNode(androidx.compose.ui.test.hasContentDescription(s)).performClick()
}
