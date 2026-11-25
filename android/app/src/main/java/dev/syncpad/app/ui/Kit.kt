package dev.syncpad.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Three surface levels carry the hierarchy, so nothing needs an outline. Lime
// means one thing only: live, selected, or the main action.
val Bg = Color(0xFF0D0E10)
val S1 = Color(0xFF191B1E)
val S2 = Color(0xFF22252A)
val S3 = Color(0xFF30353B)
val Ink = Color(0xFFF1F3F2)
val Dim = Color(0xFF8D949A)
val Accent = Color(0xFFC8F250)
val OnAccent = Color(0xFF12150A)
val Warn = Color(0xFFFFB454)

/** Tints for deck keys. Muted, so they sort keys without competing with the accent. */
val Tints = listOf(Color(0xFF9DB8FF), Color(0xFF7FE0C3), Color(0xFFFF9E8A), Color(0xFFE3B7FF), Color(0xFFFFD27A), Color(0xFFB9C2C9))

val Keycap = Brush.verticalGradient(listOf(S2, Color(0xFF1C1F23)))
val R12 = RoundedCornerShape(12.dp)
val R18 = RoundedCornerShape(18.dp)
val R24 = RoundedCornerShape(24.dp)

val Numbers = TextStyle(fontFeatureSettings = "tnum")

private val Snap = spring<Float>(dampingRatio = .7f, stiffness = Spring.StiffnessHigh)

@Composable
fun Theme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, background = Bg, onBackground = Ink, surface = S1,
        onSurface = Ink, onSurfaceVariant = Dim, surfaceContainerHigh = S1, surfaceContainer = S1, outline = S3,
        surfaceContainerHighest = S2, secondaryContainer = S3, onSecondaryContainer = Ink),
    content = content,
)

/**
 * A key. Reports press and release so holding it holds it on the laptop, and
 * can repeat. The colour changes in the same frame as the touch: only the
 * spring back is animated.
 */
@Composable
fun Key(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, lit: Boolean = false, size: Int = 15, text: String = label,
    shape: Shape = R18, iconSize: Int = 24, repeat: Boolean = false, accent: Boolean = false, tint: Color = Ink, caption: String? = null,
    onUp: () -> Unit = {}, onDown: () -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val scale by animateFloatAsState(if (held) .955f else 1f, Snap, label = "key")
    if (repeat) LaunchedEffect(held) { if (held) { delay(360); while (true) { down(); delay(42) } } }
    DisposableEffect(Unit) { onDispose { if (held) up() } }
    val on = lit || accent
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .then(if (on) Modifier.background(if (held) Accent.copy(alpha = .82f) else Accent) else if (held) Modifier.background(S3) else Modifier.background(Keycap))
            .semantics { role = Role.Button; contentDescription = label; selected = lit; onClick { down(); up(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    held = true; down()
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            if (e.changes.none { it.pressed }) break
                        }
                    } finally { held = false; up() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (on) OnAccent else tint
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(iconSize.dp))
            else Text(text, color = ink, fontSize = size.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (caption != null) Text(caption, color = if (on) OnAccent.copy(alpha = .7f) else Dim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** For things inside scrolling lists, where a touch may be the start of a scroll. */
@Composable
fun Modifier.tap(shape: Shape = R18, enabled: Boolean = true, color: Color = S1, onTap: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, Snap, label = "tap")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape).background(if (pressed) S3 else color)
        .clickable(src, null, enabled, role = Role.Button, onClick = onTap)
}

@Composable
fun Action(label: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, onTap: () -> Unit) {
    val ink = if (!enabled) Dim.copy(alpha = .6f) else if (primary) OnAccent else Ink
    Row(
        modifier.tap(R18, enabled, if (primary && enabled) Accent else if (enabled) S2 else S1, onTap).heightIn(min = 54.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
)

@Composable
fun Section(title: String) = Text(title, color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 10.dp))

@Composable
fun Body(text: String, color: Color = Dim, modifier: Modifier = Modifier) = Text(text, color = color, fontSize = 14.sp, lineHeight = 20.sp, modifier = modifier)

/** Groups rows on one surface. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(R24).background(S1).padding(vertical = 6.dp), content = content)

@Composable
fun RowItem(title: String, note: String? = null, icon: ImageVector? = null, tint: Color = Ink, onTap: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) = Row(
    (if (onTap != null) Modifier.tap(R18, onTap = onTap) else Modifier).fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
) {
    if (icon != null) Box(Modifier.size(40.dp).clip(R12).background(S2), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, color = Dim, fontSize = 13.sp, lineHeight = 18.sp)
    }
    if (trailing != null) trailing() else if (onTap != null) Icon(Icons.Rounded.ChevronRight, null, tint = Dim, modifier = Modifier.size(22.dp))
}

@Composable
fun Toggle(label: String, note: String? = null, value: Boolean, onChange: (Boolean) -> Unit) = RowItem(label, note, trailing = {
    Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = OnAccent, checkedTrackColor = Accent,
        uncheckedThumbColor = Dim, uncheckedTrackColor = S2, uncheckedBorderColor = S3))
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dial(label: String, value: Float, range: ClosedFloatingPointRange<Float>, note: String? = null, onChange: (Float) -> Unit) = Column(
    Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text("%.1f".format(value), color = Dim, fontSize = 15.sp, style = Numbers)
    }
    if (note != null) Text(note, color = Dim, fontSize = 13.sp, lineHeight = 18.sp)
    val f = (value - range.start) / (range.endInclusive - range.start)
    Slider(value, onChange, Modifier.height(40.dp).semantics { contentDescription = label }, valueRange = range,
        thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(Ink)) },
        track = {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(S3)) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
            }
        })
}

/** Two or three choices with a sliding highlight. */
@Composable
fun Segments(options: List<String>, picked: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) = BoxWithConstraints(
    modifier.fillMaxWidth().height(48.dp).clip(R18).background(S1).padding(4.dp),
) {
    val w = maxWidth / options.size
    val x by animateDpAsState(w * picked, spring(.85f, Spring.StiffnessMediumLow), label = "seg")
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(S3))
    Row {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onPick(i) }
                    .semantics { selected = i == picked },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (i == picked) Ink else Dim, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(S3)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(Accent))
    }
}

@Composable
fun Gap(n: Int) = Spacer(Modifier.size(n.dp))
