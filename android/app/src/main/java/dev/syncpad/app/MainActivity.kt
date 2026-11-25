package dev.syncpad.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.syncpad.app.net.Laptop
import dev.syncpad.app.ui.Root
import dev.syncpad.app.ui.Shell

class MainActivity : ComponentActivity(), Shell {
    override val hub: Hub by lazy { (application as App).hub }

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    private val scanner = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        val l = Laptop.parse(text)
        if (l == null) hub.notice = "That is not a SyncPad code" else hub.pair(l)
    }
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) enableBluetooth() else hub.notice = "Bluetooth needs the Nearby devices permission"
    }
    private val btOn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) hub.hidLink.start() }
    private val visible = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(0)
        enableEdgeToEdge(bars, bars)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Root(this) }
        if (savedInstanceState == null) incoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming(intent)
    }

    private fun incoming(i: Intent) {
        when (i.action) {
            // Any app can open this link, so it only ever asks.
            Intent.ACTION_VIEW -> i.dataString?.let { hub.pendingPair = Laptop.parse(it) }
            Intent.ACTION_SEND -> {
                val u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (u != null) share(listOf(u)) else i.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                    hub.link.json(org.json.JSONObject().put("t", "clip").put("s", t.take(12_000)))
                    hub.notice = if (hub.connected) "Copied to the laptop" else "Laptop not connected"
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> share(IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
        }
    }

    /** Only content a real app shared: never a path into this app's own private files. */
    private fun share(uris: List<Uri>) {
        val ok = uris.filter { it.scheme == "content" && it.authority?.startsWith(packageName) != true }
        if (ok.isNotEmpty()) { hub.upload(ok); hub.notice = "Sending to the laptop" }
    }

    override fun onStart() { super.onStart(); hub.foreground() }
    override fun onStop() { super.onStop(); hub.background() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hub.offerClip() else hub.releaseHeld()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = when (event.keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> "vol_up"; KeyEvent.KEYCODE_VOLUME_DOWN -> "vol_down"; else -> null }
        if (k == null || !hub.volumeKeys || !hub.connected) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) hub.combo(k)
        return true
    }

    // ---- Shell
    override fun haptic(ms: Int) {
        if (hub.haptics) runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    override fun scan() = scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
        .setOrientationLocked(false).setPrompt("Point at the code in the SyncPad window"))

    override fun paste() {
        val text = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val l = Laptop.parse(text)
        if (l == null) hub.notice = "Copy the link from the laptop first" else hub.pair(l)
    }

    override fun landscape(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private val storage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun allowFiles() {
        if (Build.VERSION.SDK_INT < 30) return storage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
    }

    private fun permitted(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val need = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (need.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) return true
        btPermission.launch(need)
        return false
    }

    override fun enableBluetooth() {
        hub.hidOn = true
        if (!permitted()) return
        val a = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (a != null && !a.isEnabled) btOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) else hub.hidLink.start()
    }

    @SuppressLint("MissingPermission")
    override fun discoverable() {
        if (permitted()) visible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
    }
}
