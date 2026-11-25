package dev.syncpad.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardAlt
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.syncpad.app.Hub
import dev.syncpad.app.input.Keys

/** Build a shortcut by pressing it on the laptop or tapping keys here. Steps run one after another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutEditor(hub: Hub, value: String, change: (String) -> Unit) {
    val steps = Keys.parse(value)
    val mods = remember { mutableStateListOf<String>() }
    var picking by remember { mutableStateOf(false) }
    var group by remember { mutableIntStateOf(1) }
    val now by rememberUpdatedState(value)
    val net = hub.online

    fun add(keys: List<String>) = change(Keys.write(Keys.parse(now) + listOf(keys)))

    LaunchedEffect(hub.recorded) { hub.recorded?.let { add(it.map(Keys::name)); hub.recorded = null } }
    DisposableEffect(Unit) { onDispose { if (hub.recording) hub.record(false) } }

    Column(Modifier.fillMaxWidth().clip(R18).background(S1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (steps.isEmpty()) Body(if (hub.recording) "Press the shortcut on your laptop's keyboard." else "No keys yet. Press them on your laptop, or pick them below.")
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, step ->
                Row(Modifier.clip(R12).background(S3).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Keys.show(step), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.size(44.dp).tap(R12, color = S3) { change(Keys.write(steps.filterIndexed { n, _ -> n != i })) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Remove ${Keys.show(step)}", tint = Dim, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        if (steps.size > 1) Body("Runs in this order.")
        if (hub.recording && steps.isNotEmpty()) Body("Press the next keys on your laptop's keyboard.", Accent)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(if (hub.recording) "Cancel" else "Press on laptop", Modifier.weight(1.3f).widthIn(min = 190.dp), enabled = net, icon = if (hub.recording) Icons.Rounded.Close else Icons.Rounded.Laptop) { hub.record(!hub.recording) }
            Action(if (picking) "Hide keys" else "Pick keys", Modifier.weight(1f).widthIn(min = 150.dp), icon = Icons.Rounded.KeyboardAlt) { picking = !picking }
        }
        if (picking) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Keys.MODS.forEach { m -> Key(Keys.label(m), Modifier.weight(1f).height(48.dp), lit = m in mods, shape = R12) { if (!mods.remove(m)) mods.add(m) } }
            }
            Segments(Keys.GROUPS.map { it.first }, group) { group = it }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Keys.GROUPS[group].second.forEach { k ->
                    Box(Modifier.tap(R12, color = S2) { add(Keys.MODS.filter { it in mods } + k); mods.clear() }.heightIn(min = 46.dp).widthIn(min = 46.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(Keys.label(k), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (mods.isNotEmpty()) Action("Add ${Keys.show(Keys.MODS.filter { it in mods })} alone", Modifier.fillMaxWidth()) { add(Keys.MODS.filter { it in mods }); mods.clear() }
        }
        if (steps.isNotEmpty()) Action("Try it", Modifier.fillMaxWidth(), enabled = hub.connected, icon = Icons.Rounded.PlayArrow) { hub.shortcut(value) }
    }
}
