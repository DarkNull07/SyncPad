package dev.syncpad.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncpad.app.Hub
import kotlin.math.ln
import kotlin.math.max

/** The dial reads like fast.com's number: big, live, and settling on the result. */
@Composable
fun SpeedPage(hub: Hub) = Page {
    val s = hub.netSpeed
    val running = hub.testing
    val shown = when (s.phase) { "download", "upload" -> s.live; "done" -> s.down ?: 0.0; else -> 0.0 }
    // Logarithmic, so 5 and 500 Mbit/s are both readable on one dial.
    val target = (ln(1 + max(0.0, shown)) / ln(1001.0)).toFloat().coerceIn(0f, 1f)
    val sweep by animateFloatAsState(target, tween(450), label = "dial")
    val number by animateFloatAsState(shown.toFloat(), tween(450), label = "number")
    Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val w = 14.dp.toPx()
            val arc = Size(size.width - w, size.height - w)
            val o = Offset(w / 2, w / 2)
            drawArc(S2, 135f, 270f, false, o, arc, style = Stroke(w, cap = StrokeCap.Round))
            if (sweep > 0.002f) drawArc(if (s.phase == "upload") Tints[2] else Accent, 135f, 270f * sweep, false, o, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (running && s.phase == "ping") "\u2026" else "%.0f".format(number), color = Ink, fontSize = 60.sp, fontWeight = FontWeight.Light, style = Numbers)
            Text(when (s.phase) { "ping" -> "Measuring ping"; "download" -> "Download, Mbit/s"; "upload" -> "Upload, Mbit/s"; "done" -> "Download, Mbit/s"; "failed" -> s.error; else -> "Mbit/s" },
                color = Dim, fontSize = 14.sp)
        }
    }
    Card {
        Stat("Download", s.down?.let { "%.1f Mbit/s".format(it) })
        Stat("Upload", s.up?.let { "%.1f Mbit/s".format(it) })
        Stat("Ping", s.ping?.let { "$it ms" }, s.jitter?.let { "jitter $it ms" })
        Stat("Ping while busy", s.loaded?.let { "$it ms" }, "how much a full connection slows everything else")
        Stat("Data used", if (s.usedMb > 0) "%.0f MB".format(s.usedMb) else null)
    }
    if (running) Action("Stop", Modifier.fillMaxWidth()) { hub.stopSpeedTest() }
    else Action(if (s.phase == "done") "Test again" else "Start", Modifier.fillMaxWidth(), primary = true) { hub.startSpeedTest() }
    Body(listOfNotNull(
        "Measured against Cloudflare's nearest server" + (if (s.server.isNotEmpty()) " (${s.server})" else "") + ", with six connections at once, the way speed.cloudflare.com does.",
        if (hub.onMobileData) "You are on mobile data. A fast connection can use a few hundred MB." else null,
    ).joinToString(" "), modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Stat(label: String, value: String?, note: String? = null) = Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Ink, fontSize = 15.sp)
        if (note != null) Text(note, color = Dim, fontSize = 12.sp)
    }
    Text(value ?: "\u2013", color = if (value == null) Dim else Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, style = Numbers)
}
