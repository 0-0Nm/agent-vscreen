package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import kotlin.math.roundToInt

// [vscreen] Argument shapes every vscreen command shares, parsed in one place so the
// same spelling works everywhere and a bad value is refused instead of silently replaced.

/** "x1,y1,x2,y2" (also with Chinese commas, any corner order) → Rect. */
internal fun parseRect(v: String, what: String): Rect {
    val n = v.split(',', '，').mapNotNull { it.trim().toDoubleOrNull()?.roundToInt() }
    if (n.size != 4) throw ActionError("INVALID_ARGS", "$what 写成 x1,y1,x2,y2（收到 $v）")
    return Rect(minOf(n[0], n[2]), minOf(n[1], n[3]), maxOf(n[0], n[2]), maxOf(n[1], n[3]))
}

/** 500ms / 5s / 30m / 2h; a bare number is milliseconds; null → [def]. */
internal fun parseDuration(v: String?, def: Long): Long {
    val t = v?.trim() ?: return def
    return VScreenOptions.durationMs(t) ?: throw ActionError("INVALID_ARGS", "时长写成 500ms / 5s / 30m / 2h（不带单位就是毫秒）：$v")
}

/** A value the tool had to change (clamped) is said in the result instead of silently. */
private val adjustedNotes = ThreadLocal<MutableList<String>>()

internal fun adjusted(what: String) {
    (adjustedNotes.get() ?: ArrayList<String>().also { adjustedNotes.set(it) }) += what
}

/** Take the notes collected during one command (and clear them). */
internal fun takeAdjusted(): List<String> = adjustedNotes.get()?.toList().orEmpty().also { adjustedNotes.remove() }

internal fun bounded(name: String, v: Int, lo: Int, hi: Int): Int = bounded(name, v.toLong(), lo.toLong(), hi.toLong()).toInt()

/** [v] kept within [lo]..[hi]; a value outside is clamped AND reported in the result. */
internal fun bounded(name: String, v: Long, lo: Long, hi: Long): Long {
    if (v < lo || v > hi) adjusted("--$name $v 超出范围，按 ${v.coerceIn(lo, hi)} 执行（可用 $lo–$hi）")
    return v.coerceIn(lo, hi)
}
