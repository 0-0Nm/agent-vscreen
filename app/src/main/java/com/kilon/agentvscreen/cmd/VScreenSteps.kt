package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError

// [vscreen] v49: the step and condition language of `act`, parsed in one place (pure Kotlin, unit-tested).
//
// A segment is steps separated by ';' or new lines. A step is an action (tap / press / swipe / drag / scroll /
// type / key), a wait, or `use NAME` (a saved segment). Every action may end with `until <cond> [or <cond>…] [max]`.
// Conditions are facts the phone can check by itself: text appears / is gone, an area changed / stayed still,
// a point has a colour, the foreground app or activity. There is no "progress" and no meaning here.

/** Where a tap / press / scroll goes: a point, an element of the last list (#N), or words on the screen. */
internal sealed class StepTarget {
    data class At(val x: Int, val y: Int) : StepTarget()
    data class Ref(val n: Int) : StepTarget()
    data class Words(val text: String) : StepTarget()

    fun describe(): String = when (this) {
        is At -> "$x,$y"
        is Ref -> "#$n"
        is Words -> "“$text”"
    }
}

/** One fact to wait for or stop at. [area] null = the whole screen. */
internal data class Cond(val raw: String, val kind: String, val text: String? = null, val area: Rect? = null,
                         val x: Int = 0, val y: Int = 0, val color: Int = 0, val tol: Int = 40)

/** One step of a segment. */
internal data class Step(
    val raw: String,
    /** tap, press, swipe, drag, scroll, type, key, wait, sleep, use */
    val kind: String,
    val target: StepTarget? = null,
    val points: List<Pair<Int, Int>> = emptyList(),
    val text: String? = null,
    val ms: Long? = null,
    val append: Boolean = false,
    /** until … (or, for wait, what it waits for); null = the default wait. */
    val until: List<Cond>? = null,
    val untilMs: Long? = null,
) {
    val isAction: Boolean get() = kind !in setOf("wait", "sleep", "use")
}

internal object Steps {
    const val UNTIL_DEFAULT_MS = 5_000L
    const val WAIT_DEFAULT_MS = 10_000L
    const val MAX_WAIT_MS = 120_000L
    const val MAX_STEPS = 40

    private val KEYS = setOf("back", "enter", "home", "tab", "del", "delete", "space", "search", "menu", "escape", "esc", "up", "down",
        "left", "right", "paste", "copy", "cut", "select_all", "page_up", "page_down", "volume_up", "volume_down", "app_switch", "recent")

    /** Split a segment into step strings: ';' / '；' / new lines, not inside quotes. */
    fun split(body: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q: Char? = null
        for (c in body) {
            when {
                q != null -> { cur.append(c); if (c == q) q = null }
                c == '"' || c == '\'' || c == '“' -> { cur.append(c); q = if (c == '“') '”' else c }
                c == ';' || c == '；' || c == '\n' -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Tokens of one step: whitespace separated, quotes keep spaces (and are removed). */
    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var q: Char? = null
        var had = false
        for (c in s) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' || c == '“' -> { q = if (c == '“') '”' else c; had = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || had) out += cur.toString(); cur.setLength(0); had = false }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || had) out += cur.toString()
        return out
    }

    fun duration(t: String?): Long? = t?.let { VScreenOptions.durationMs(it.trim()) }

    /** "540,1200" / "540" "1200" / "540，1200" → numbers. */
    private fun numbers(toks: List<String>): List<Int>? {
        val out = ArrayList<Int>()
        for (t in toks) for (p in t.replace('，', ',').split(',').filter { it.isNotEmpty() }) out += p.toDoubleOrNull()?.toInt() ?: return null
        return out
    }

    fun parseAll(body: String): List<Step> {
        val parts = split(body)
        if (parts.size > MAX_STEPS) throw ActionError("INVALID_STEP", "一段最多 $MAX_STEPS 步（收到 ${parts.size} 步）：分成几段 act")
        return parts.map { parse(it) }
    }

    /** One step. Throws INVALID_STEP with what was wrong and how to write it. */
    fun parse(raw: String): Step {
        val toks0 = tokens(raw)
        if (toks0.isEmpty()) bad(raw, "空步骤")
        val verb = toks0[0].lowercase()
        // until … at the end (for type only when what follows really is a condition).
        val ui = toks0.indexOfLast { it.lowercase() == "until" }
        var until: List<Cond>? = null
        var untilMs: Long? = null
        var toks = toks0
        if (ui > 0 && verb != "wait") {
            val parsed = runCatching { condsWithMax(toks0.drop(ui + 1)) }
            if (parsed.isSuccess) { until = parsed.getOrThrow().first; untilMs = parsed.getOrThrow().second; toks = toks0.take(ui) }
            else if (verb != "type" && verb != "type+") throw parsed.exceptionOrNull()!!
        }
        val args = toks.drop(1)
        return when (verb) {
            "tap", "click", "点" -> Step(raw, "tap", target = target(raw, args, allowDuration = false).first, until = until, untilMs = untilMs)
            "press", "longpress", "hold", "长按" -> {
                val (t, ms) = target(raw, args, allowDuration = true)
                Step(raw, "press", target = t, ms = (ms ?: 800L).coerceIn(100L, 10_000L), until = until, untilMs = untilMs)
            }
            "swipe", "drag", "滑", "拖" -> {
                val isDrag = verb == "drag" || verb == "拖"
                val ms = args.lastOrNull()?.takeIf { it.any { c -> c.isLetter() } }?.let { duration(it) ?: bad(raw, "时长写成 300ms / 1s") }
                val nums = numbers(if (ms != null) args.dropLast(1) else args) ?: bad(raw, "坐标写成数字：$verb x1 y1 x2 y2${if (isDrag) " [x3 y3 …]" else ""} [时长]")
                if (nums.size < 4 || nums.size % 2 != 0) bad(raw, "$verb 要成对的坐标：x1 y1 x2 y2${if (isDrag) " [x3 y3 …]" else ""}（收到 ${nums.size} 个数）")
                if (!isDrag && nums.size != 4) bad(raw, "swipe 只有起点和终点；经过多个点用 drag")
                val pts = nums.chunked(2).map { it[0] to it[1] }
                Step(raw, if (isDrag) "drag" else "swipe", points = pts, ms = (ms ?: if (isDrag) 600L else 300L).coerceIn(50L, 10_000L), until = until, untilMs = untilMs)
            }
            "scroll", "滚" -> {
                val dir = args.firstOrNull()?.lowercase()?.let { d -> mapOf("上" to "up", "下" to "down", "左" to "left", "右" to "right")[d] ?: d }
                if (dir !in setOf("up", "down", "left", "right")) bad(raw, "scroll up|down|left|right [#N | x y]")
                val t = if (args.size > 1) target(raw, args.drop(1), allowDuration = false).first.also {
                    if (it is StepTarget.Words) bad(raw, "scroll 的位置写 #N 或 x y")
                } else null
                Step(raw, "scroll", target = t, text = dir, until = until, untilMs = untilMs)
            }
            "type", "type+", "输入" -> {
                val body = rawAfterVerb(raw, toks0[0]).let { r ->
                    if (until != null) r.substring(0, r.lastIndexOf("until").coerceAtLeast(0)).trimEnd() else r
                }.let { unquote(it) }
                if (body.isEmpty()) bad(raw, "type 后面写要输入的文字")
                Step(raw, "type", text = body, append = verb == "type+", until = until, untilMs = untilMs)
            }
            "key", "press-key", "按键" -> {
                val k = args.firstOrNull()?.lowercase() ?: bad(raw, "key 后面写键名：back / enter / home / tab / del / space / search")
                if (k !in KEYS && !k.matches(Regex("keycode_[a-z0-9_]+|[0-9]{1,3}"))) bad(raw, "不认识的键 $k（可用：${KEYS.joinToString(" ")}，或 keycode_XXX）")
                Step(raw, "key", text = k, until = until, untilMs = untilMs)
            }
            "back" -> Step(raw, "key", text = "back", until = until, untilMs = untilMs)
            "enter" -> Step(raw, "key", text = "enter", until = until, untilMs = untilMs)
            "wait", "等" -> {
                if (args.isEmpty()) bad(raw, "wait <条件> [最长时长] 或 wait 时长")
                val only = duration(args[0])
                if (args.size == 1 && only != null) Step(raw, "sleep", ms = only.coerceIn(0L, MAX_WAIT_MS))
                else {
                    val (c, max) = condsWithMax(args)
                    Step(raw, "wait", until = c, untilMs = (max ?: WAIT_DEFAULT_MS).coerceIn(100L, MAX_WAIT_MS))
                }
            }
            "use", "用" -> Step(raw, "use", text = args.joinToString(" ").trim().ifEmpty { bad(raw, "use 后面写保存过的段名") })
            else -> bad(raw, "不认识的步骤“$verb”")
        }
    }

    private fun rawAfterVerb(raw: String, verbTok: String): String = raw.trim().let { r ->
        val i = r.indexOf(verbTok)
        if (i < 0) r else r.substring(i + verbTok.length).trim()
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\'') || (t.first() == '“' && t.last() == '”'))) return t.substring(1, t.length - 1)
        return t
    }

    /** A target: x y | x,y | #N | words; optionally a duration last. */
    private fun target(raw: String, args: List<String>, allowDuration: Boolean): Pair<StepTarget, Long?> {
        if (args.isEmpty()) bad(raw, "要写点哪里：x y、#编号 或 画面上的字")
        var a = args
        var ms: Long? = null
        if (allowDuration && a.size >= 2) a.last().takeIf { t -> t.any { it.isLetter() } }?.let { t -> duration(t)?.let { ms = it; a = a.dropLast(1) } }
        // v0.5: a bare number after the target is the duration in ms too (press 600 2400 1200 / press #3 1200), as the help says.
        if (allowDuration && ms == null && a.size >= 2 && a.last().all { it.isDigit() } &&
            (a[0].startsWith("#") && a.size == 2 || numbers(a)?.size == 3)) { ms = a.last().toLong(); a = a.dropLast(1) }
        val first = a[0]
        if (first.startsWith("#") && a.size == 1) {
            val n = first.drop(1).toIntOrNull() ?: bad(raw, "#后面写元素编号（第三块列表里的 #N）")
            return StepTarget.Ref(n) to ms
        }
        val nums = numbers(a)
        if (nums != null) {
            // 1.1: one number alone is a word on the screen (a calculator's "1", a page number), not half a coordinate.
            if (nums.size == 1) return StepTarget.Words(a[0]) to ms
            if (nums.size != 2) bad(raw, "坐标是两个数：x y（只写一个数＝点画面上的这个数字；#N＝元素编号）")
            return StepTarget.At(nums[0], nums[1]) to ms
        }
        return StepTarget.Words(a.joinToString(" ").trim()) to ms
    }

    /** Conditions joined by or, then an optional duration last (the longest wait). */
    fun condsWithMax(toks: List<String>): Pair<List<Cond>, Long?> {
        if (toks.isEmpty()) throw ActionError("INVALID_STEP", "until / wait 后面要写条件：${CONDS_HELP}")
        var t = toks
        var max: Long? = null
        val last = t.last()
        if (t.size >= 2 && !last.contains(':') && last.any { it.isDigit() } && duration(last) != null) { max = duration(last); t = t.dropLast(1) }
        if (max != null && t.size >= 2 && t.last().lowercase() in setOf("max", "最长", "within")) t = t.dropLast(1)
        val conds = t.joinToString(" ").split(Regex("\\s+or\\s+|\\s*\\|\\s*|\\s+或\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.map { cond(it) }
        if (conds.isEmpty()) throw ActionError("INVALID_STEP", "until / wait 后面要写条件：$CONDS_HELP")
        return conds to max?.coerceIn(100L, MAX_WAIT_MS)
    }

    const val CONDS_HELP = "text:字 | gone:字 | change[:x1,y1,x2,y2] | still[:x1,y1,x2,y2] | stable | color:x,y=#RRGGBB[~容差] | app:名字，多个用 or 连"

    /** One condition. */
    fun cond(s0: String): Cond {
        val s = s0.trim()
        val kind = s.substringBefore(':').lowercase().trim()
        val v = if (':' in s) s.substringAfter(':').trim() else ""
        fun area(): Rect? = if (v.isEmpty()) null else parseRect(v, "区域")
        return when (kind) {
            "text", "gone" -> { if (v.isEmpty()) throw ActionError("INVALID_STEP", "$kind: 后面写字（$s）"); Cond(s, kind, text = v) }
            "change", "changed" -> Cond(s, "change", area = area())
            "still" -> Cond(s, "still", area = area())
            "stable" -> Cond(s, "still")
            "app", "activity" -> { if (v.isEmpty()) throw ActionError("INVALID_STEP", "app: 后面写包名或界面名（$s）"); Cond(s, "app", text = v) }
            "color", "colour" -> {
                val m = Regex("^(\\d+)\\s*[,，]\\s*(\\d+)\\s*=\\s*(#?[0-9a-fA-F]{6})\\s*(?:~\\s*(\\d+))?$").find(v)
                    ?: throw ActionError("INVALID_STEP", "颜色条件写成 color:x,y=#RRGGBB[~容差]（收到 $s）")
                val c = com.kilon.agentvscreen.core.VPixels.parseColor(m.groupValues[3]) ?: throw ActionError("INVALID_STEP", "颜色写成 #RRGGBB（收到 $s）")
                Cond(s, "color", x = m.groupValues[1].toInt(), y = m.groupValues[2].toInt(), color = c, tol = m.groupValues[4].toIntOrNull()?.coerceIn(0, 255) ?: 40)
            }
            else -> throw ActionError("INVALID_STEP", "不认识的条件“$s”。条件只有：$CONDS_HELP")
        }
    }

    /** Conditions of a --stop / keep value. */
    fun conds(spec: String): List<Cond> = condsWithMax(tokens(spec)).first

    private fun bad(raw: String, why: String): Nothing = throw ActionError("INVALID_STEP",
        "步骤“$raw”：$why。整段没有执行。步骤写法：tap x y | tap #N | tap 字 | press x y [时长] | swipe x1 y1 x2 y2 [时长] | " +
            "drag x1 y1 x2 y2 [… 时长] | scroll down [#N] | type 字 | key back | wait <条件> [最长] | wait 2s | use 段名；动作后可加 until <条件> [最长]")
}
