package dev.syncpad.app.input

/** Key names shared with the laptop, and shortcuts written as "ctrl+k > ctrl+s". */
object Keys {
    val MODS = listOf("ctrl", "alt", "shift", "win")

    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Letters" to (('a'..'z') + ('0'..'9')).map { it.toString() },
        "Special" to listOf("esc", "tab", "enter", "space", "backspace", "delete", "insert", "printscreen", "home", "end", "pageup", "pagedown",
            "up", "down", "left", "right", "capslock", "menu", "pause", "numlock", "scrolllock"),
        "F keys" to (1..12).map { "f$it" },
        "More" to listOf("play_pause", "next", "prev", "stop", "vol_up", "vol_down", "mute", "browser_back", "browser_forward",
            "-", "=", "[", "]", "\\", ";", "'", ",", ".", "/", "`"))

    private val LABELS = mapOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win", "esc" to "Esc", "tab" to "Tab", "enter" to "Enter",
        "space" to "Space", "backspace" to "Backspace", "delete" to "Del", "insert" to "Ins", "printscreen" to "PrtScn", "home" to "Home", "end" to "End",
        "pageup" to "PgUp", "pagedown" to "PgDn", "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right", "capslock" to "Caps",
        "menu" to "Menu", "pause" to "Pause", "numlock" to "Num", "scrolllock" to "Scroll", "play_pause" to "Play", "next" to "Next track", "prev" to "Last track",
        "stop" to "Stop", "vol_up" to "Vol +", "vol_down" to "Vol -", "mute" to "Mute", "browser_back" to "Web back", "browser_forward" to "Web forward")

    private val ALIAS = mapOf("prtsc" to "printscreen", "prtscn" to "printscreen", "prtscr" to "printscreen", "print" to "printscreen", "printscrn" to "printscreen",
        "windows" to "win", "cmd" to "win", "super" to "win", "control" to "ctrl", "return" to "enter", "escape" to "esc", "del" to "delete", "ins" to "insert",
        "pgup" to "pageup", "pgdn" to "pagedown", "pgdown" to "pagedown", "spacebar" to "space", "caps" to "capslock", "plus" to "=", "minus" to "-",
        "comma" to ",", "period" to ".", "dot" to ".", "slash" to "/")

    fun name(s: String): String = s.trim().lowercase().replace(" ", "").let { ALIAS[it] ?: it }

    fun label(k: String) = LABELS[k] ?: if (k.length <= 3) k.uppercase() else k.replaceFirstChar { it.uppercase() }

    fun parse(value: String): List<List<String>> = value.split('>').map { step -> step.split('+').map(::name).filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }

    fun write(steps: List<List<String>>) = steps.joinToString(" > ") { it.joinToString("+") }

    fun show(step: List<String>) = step.joinToString(" + ", transform = ::label)
}
