package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.BackgroundOperation.ActionError

/**
 * v49: how every vscreen option is written, in one place. The parser ([args]), the option check
 * (VScreenOffloadHandler.checkOptions), duration units ([normalize]) and the help test all read this.
 *
 *  - SWITCH: `--x` alone; `--x=v` is refused.
 *  - VALUE:  `--x v` / `--x=v`; `--x` alone is refused.
 *
 * Durations take 500ms / 1.5s / 2m / 1h / 3秒; a bare number is milliseconds. They are turned into milliseconds
 * here, once, so commands read plain numbers.
 */
internal object VScreenOptions {
    enum class Kind { SWITCH, VALUE }

    /** Options that never take a value. */
    val SWITCHES: Set<String> = setOf("landscape", "portrait", "restart", "paused", "confirmed", "force", "open")

    /** Options whose value is a duration (milliseconds after [normalize]). */
    val DURATIONS: Set<String> = setOf("watch", "for", "wait")

    fun kindOf(@Suppress("UNUSED_PARAMETER") sub: String, key: String): Kind = when (key) {
        in SWITCHES, in OffloadOutput.OUTPUT_FLAGS, "help" -> Kind.SWITCH
        else -> Kind.VALUE
    }

    fun switchesFor(sub: String): Set<String> = SWITCHES.filterTo(HashSet()) { kindOf(sub, it) == Kind.SWITCH }

    fun isDurationOption(@Suppress("UNUSED_PARAMETER") sub: String, key: String): Boolean = key in DURATIONS

    /** The parser for [sub]'s arguments (already [normalize]d). */
    fun args(sub: String, rest: List<String>, strict: Boolean = true): OffloadArgs =
        OffloadArgs(rest, booleanFlags = switchesFor(sub), strict = strict)

    /** 500ms / 1.5s / 2m / 1h / 3秒 / 2分钟 → milliseconds; a bare number is milliseconds; null when not a duration. */
    fun durationMs(v: String): Long? {
        val m = DURATION.find(v.trim().lowercase()) ?: return null
        val k = when (m.groupValues[2]) {
            "", "ms", "毫秒" -> 1.0; "s", "sec", "秒" -> 1_000.0; "m", "min", "分", "分钟" -> 60_000.0; else -> 3_600_000.0
        }
        return (m.groupValues[1].toDouble() * k).toLong()
    }
    private val DURATION = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(ms|毫秒|s|sec|秒|m|min|分|分钟|h|hr|小时)?$")

    /** Duration option values in [rest] rewritten to milliseconds; a duration that does not parse is refused here. */
    fun normalize(sub: String, rest: List<String>): List<String> {
        val out = ArrayList<String>(rest.size)
        var i = 0
        while (i < rest.size) {
            val t = rest[i]
            val key = if (t.startsWith("--") && t.length > 2) t.substring(2).substringBefore('=') else null
            if (key == null || !isDurationOption(sub, key)) { out += t; i++; continue }
            if ('=' in t) { out += "--$key=" + ms(key, t.substringAfter('=')); i++; continue }
            out += t
            val next = rest.getOrNull(i + 1)
            if (next == null || next.startsWith("--")) i++ else { out += ms(key, next).toString(); i += 2 }
        }
        return out
    }

    private fun ms(key: String, v: String): Long = durationMs(v)
        ?: throw ActionError("INVALID_ARGS", "--$key 是时长，写成 500ms / 1.5s / 2m（不带单位就是毫秒）：收到 $v。命令没有执行")
}
