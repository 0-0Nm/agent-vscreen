package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VPixels
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenKnowledge
import com.kilon.agentvscreen.core.VScreenMap
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import kotlin.math.hypot
import kotlin.math.roundToInt

// [vscreen] v49: `act` — one segment: 0..N steps, each with a fact-only wait, stop conditions checked every 0.1 s,
// optional watching afterwards, and (for apps whose pause / resume steps were written down) resume → steps → watch →
// pause, so a realtime game becomes turn-based. It returns the four blocks (VScreenReport). With --keep the stop
// conditions go on being watched after the turn (VScreenRules) and post an event to the caller or notify the user.

/** Watches [conds] on [d]; the baseline (for change:) is the picture when it was made. */
internal class Probe(private val h: VScreenOffloadHandler, private val d: VScreenDisplay, val conds: List<Cond>) {
    private val since = SystemClock.elapsedRealtime()
    private var base: Bitmap? = d.latestFrame()?.let { VScreenPerception.small(it) }
    private var prev: Bitmap? = null
    private val stillMs = HashMap<Int, Long>()
    private var lastPoll = 0L
    private var appAt = 0L
    private var appNow: List<String> = emptyList()
    private val wantsText = conds.any { it.kind == "text" || it.kind == "gone" }

    fun close() { base?.recycle(); base = null; prev?.recycle(); prev = null }

    /** Re-take the baseline (a kept watch starts from the moment the turn ended). */
    fun rebase() { base?.recycle(); base = d.latestFrame()?.let { VScreenPerception.small(it) }; stillMs.clear() }

    private fun scaled(c: Cond, b: Bitmap): Rect? = c.area?.let { r ->
        val k = b.width.toDouble() / maxOf(1, d.width)
        Rect((r.left * k).toInt(), (r.top * k).toInt(), maxOf((r.left * k).toInt() + 1, (r.right * k).toInt()), maxOf((r.top * k).toInt() + 1, (r.bottom * k).toInt()))
    }

    private fun diff(a: Bitmap, b: Bitmap, c: Cond): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        return VPixels.regionDiff(a, b, scaled(c, a) ?: Rect(0, 0, a.width, a.height)).coerceAtLeast(0.0)
    }

    private fun where(c: Cond) = c.area?.let { "区域 [${Report.box(it)}]" } ?: "画面"

    /** The words of [q] seen now (accessibility first, then an OCR reading taken after this probe began). */
    private fun seen(q: String): String? {
        val n = VScreenOcr.norm(q)
        runCatching { BackgroundOperation.a11yTexts(d.displayId, 400) }.getOrNull()?.firstOrNull { VScreenOcr.norm(it).contains(n) }?.let { return it.take(40) }
        val r = VScreenPerception.reading(d.displayId, since, 0) ?: return null
        return r.items.firstOrNull { VScreenOcr.norm(it.text).contains(n) }?.text?.take(40)
    }

    /** True only when a reading taken after this probe began does not hold [q] (and the accessibility tree does not either). */
    private fun gone(q: String): Boolean {
        val n = VScreenOcr.norm(q)
        if (runCatching { BackgroundOperation.a11yTexts(d.displayId, 400) }.getOrNull()?.any { VScreenOcr.norm(it).contains(n) } == true) return false
        val r = VScreenPerception.reading(d.displayId, since, 0) ?: return false
        return r.items.none { VScreenOcr.norm(it.text).contains(n) }
    }

    /**
     * What the foreground of the screen is, every way it can be named: the top activity, the packages of the
     * visible tasks on this display, and their app names. `app:` matches any of them.
     */
    private fun appFacts(): List<String> {
        val out = ArrayList<String>()
        runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull()?.let { out += it; out += it.substringBefore('/') }
        runCatching { VScreenHandoff.tasksOn(d.displayId).filter { it.visible }.map { it.pkg } }.getOrNull()?.let { out += it }
        out.toList().map { it.substringBefore('/') }.distinct().forEach { p -> VScreenKnowledge.label(p)?.let { out += it } }
        return out.distinct()
    }

    /** For a wait that timed out: what each condition sees now (a fact the caller can compare with what it asked). */
    fun now(): String = conds.joinToString("；") { c ->
        when (c.kind) {
            "text" -> "画面上没有“${c.text}”"
            "gone" -> "“${c.text}”还在"
            "change" -> "${where(c)}没有明显变化"
            "still" -> "${where(c)}一直在变"
            "color" -> d.latestFrame()?.takeIf { c.x in 0 until it.width && c.y in 0 until it.height }
                ?.let { "(${c.x},${c.y}) 现在是 ${VPixels.hex(VPixels.at(it, c.x, c.y))}" } ?: "读不到 (${c.x},${c.y}) 的颜色"
            "app" -> "前台是 " + (appFacts().takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: "（读不到）")
            else -> c.raw
        }
    }

    /** null = nothing met yet; otherwise "<condition>：<what was seen>". */
    fun poll(): String? {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastPoll == 0L) 0L else now - lastPoll
        lastPoll = now
        if (wantsText) VScreenPerception.hurry(d.displayId)
        val full = d.latestFrame()
        val cur = full?.let { VScreenPerception.small(it) }
        try {
            for ((i, c) in conds.withIndex()) {
                val hit: String? = when (c.kind) {
                    "text" -> seen(c.text!!)?.let { "看到“$it”" }
                    "gone" -> if (gone(c.text!!)) "画面上没有“${c.text}”了" else null
                    "change" -> { val b = base; if (b != null && cur != null) diff(b, cur, c).takeIf { it >= 0.02 }?.let { "${where(c)}变了（${(it * 100).roundToInt()}% 的取样点不同）" } else null }
                    "still" -> {
                        val p = prev
                        if (p != null && cur != null && diff(p, cur, c) <= 0.004) stillMs[i] = (stillMs[i] ?: 0L) + dt else stillMs[i] = 0L
                        val s = stillMs[i] ?: 0L
                        if (s >= 500L && now - since >= 300L) "${where(c)}连续 ${s}ms 没变" else null
                    }
                    "color" -> full?.takeIf { c.x in 0 until it.width && c.y in 0 until it.height }?.let { f ->
                        val px = VPixels.at(f, c.x, c.y)
                        if (VPixels.dist(px, c.color) <= c.tol) "(${c.x},${c.y}) 是 ${VPixels.hex(px)}" else null
                    }
                    "app" -> {
                        if (now - appAt >= 500L) { appNow = appFacts(); appAt = now }
                        appNow.firstOrNull { it.contains(c.text!!, ignoreCase = true) }?.let { "前台是 ${appNow.first()}" }
                    }
                    else -> null
                }
                if (hit != null) return "${c.raw}：$hit"
            }
            return null
        } finally { prev?.recycle(); prev = cur }
    }
}

/** How one wait ended: [met] = the condition's fact, [stopped] = a stop condition's fact, else it timed out. */
internal class WaitOut(val met: String?, val stopped: String?, val ms: Long)

/** Wait on [probe] (null = just time) up to [maxMs], checking [stop] too, every 0.1 s. */
internal fun waitOn(d: VScreenDisplay, probe: Probe?, maxMs: Long, stop: Probe?): WaitOut {
    val t0 = SystemClock.elapsedRealtime()
    while (true) {
        d.requestFreshFrame()
        Thread.sleep(100)
        val el = SystemClock.elapsedRealtime() - t0
        if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", "用户接管了副屏，这段停在这里")
        if (BackgroundOperation.state.value.userStopped) throw ActionError("USER_STOPPED", BackgroundOperation.userStoppedMessage())
        stop?.poll()?.let { return WaitOut(null, it, el) }
        probe?.poll()?.let { return WaitOut(it, null, el) }
        if (el >= maxMs) return WaitOut(null, null, el)
    }
}

/** Options of one segment. */
internal class SegmentOpts(val stop: List<Cond>, val watchMs: Long?, val paused: Boolean, val confirmed: Boolean)

/** A step that was not done, and why (it ends the segment; the reason goes to 【注意】). */
internal class StepRefused(val code: String, message: String) : Exception(message)

/** What a segment did. */
internal class SegmentOut(val done: List<String>, val attention: MutableList<String>, val actionMs: Long, val start: Long)

private fun s1(ms: Long) = "${(ms / 100) / 10.0}s"

/** The app a chat works on: the one it launched (pages of other packages included), else the one on top. */
internal fun VScreenOffloadHandler.workApp(sid: String, d: VScreenDisplay): String? =
    VScreenKnowledge.journey(sid) ?: runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()?.takeIf { it != context.packageName }

/** Run a segment of [steps] on the chat's screen. Never throws for a step: a failed step ends the segment and is said. */
internal fun VScreenOffloadHandler.runSegment(sid: String, d: VScreenDisplay, steps: List<Step>, o: SegmentOpts): SegmentOut {
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val done = ArrayList<String>()
    val attention = ArrayList<String>()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    VScreenAwake.ensure(d)?.let { attention += it }
    VScreenAwake.kick("act")
    VScreenPerception.ensure(d)
    val app = workApp(sid, d)
    val prof = app?.let { VScreenMap.profile(it) }.orEmpty()
    val pauseParsed = prof["pause"]?.let { runCatching { Steps.parseAll(it) } }
    val resumeParsed = prof["resume"]?.let { runCatching { Steps.parseAll(it) } }
    val pauseSteps = pauseParsed?.getOrNull()
    val resumeSteps = resumeParsed?.getOrNull()
    val turnBased = pauseSteps != null && resumeSteps != null
    // v0.5: a half-written or unreadable turn setup is said, not silently ignored.
    pauseParsed?.exceptionOrNull()?.let { attention += "记的暂停步骤读不懂（${it.message?.take(80)}）：回合制没启用，note pause 重写" }
    resumeParsed?.exceptionOrNull()?.let { attention += "记的继续步骤读不懂（${it.message?.take(80)}）：回合制没启用，note resume 重写" }
    if (prof["pause"] != null && prof["resume"] == null) attention += "只记了暂停、没记继续：回合制没启用（note resume \"<继续的步骤>\"）"
    if (prof["resume"] != null && prof["pause"] == null) attention += "只记了继续、没记暂停：回合制没启用（note pause \"<暂停的步骤>\"）"
    val realtime = turnBased || prof["realtime"] == "true"
    val stop = if (o.stop.isNotEmpty()) Probe(this, d, o.stop) else null
    var resumed = false
    var stoppedBy: String? = null
    // 1.1: a step whose expected result did not happen (an until that timed out, typing that did not land or could not
    // be checked) ends the segment like a refused step: the later steps were written for a screen that is not there.
    var missed: String? = null
    var skipped = 0
    fun screenNow(): String = runCatching {
        val sn = snapshot(d)
        val words = sn.els.filter { !it.label.startsWith("（") }.map { it.label.take(16) }.distinct().take(10)
        "现在：${sn.activity?.substringAfterLast('/') ?: "前台读不到"}" + if (words.isEmpty()) "，画面上没有读到字" else "，能看到 ${words.joinToString("、") { "“$it”" }}"
    }.getOrDefault("")
    var n = 0
    // Where the agent last tapped in this segment: typing must go to a field there (BackgroundOperation.typeFact).
    var lastTap: Pair<Int, Int>? = null
    fun line(s: String) { done += s }
    fun runOne(step: Step, label: String, depth: Int, quick: Boolean) {
        if (stoppedBy != null) return
        if (missed != null) { skipped++; return }
        if (step.kind == "use") {
            val name = step.text!!
            val seg = app?.let { VScreenMap.segment(it, name) } ?: throw StepRefused("NOT_FOUND", "没有叫“$name”的段（${app ?: "当前应用"}）：note 能看到保存过的段")
            if (depth >= 3) throw StepRefused("INVALID_STEP", "段里 use 套用太深（最多 3 层）")
            val inner = runCatching { Steps.parseAll(seg.steps) }.getOrElse { throw StepRefused("INVALID_STEP", "段“$name”的步骤读不懂：${it.message}") }
            line("$label use $name（${inner.size} 步）")
            VScreenMap.markUsed(app, name)
            inner.forEachIndexed { i, st -> runOne(st, "$label.${i + 1}", depth + 1, quick) }
            return
        }
        if (step.kind == "sleep") {
            val w = waitOn(d, null, step.ms ?: 0L, stop)
            if (w.stopped != null) { stoppedBy = w.stopped; line("$label 等 ${s1(step.ms ?: 0)}：${s1(w.ms)} 时停止条件 ${w.stopped}") }
            else line("$label 等了 ${s1(w.ms)}")
            return
        }
        if (step.kind == "wait") {
            val p = Probe(this, d, step.until!!)
            try {
                val w = waitOn(d, p, step.untilMs ?: Steps.WAIT_DEFAULT_MS, stop)
                when {
                    w.stopped != null -> { stoppedBy = w.stopped; line("$label 等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 时停止条件 ${w.stopped}") }
                    w.met != null -> line("$label 等到了 ${w.met}（${s1(w.ms)}）")
                    else -> {
                        val now = screenNow()
                        line("$label 等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 到时间，没等到（${p.now()}）")
                        missed = "第 $label 步 ${step.raw} 没等到（${p.now()}）。$now"
                    }
                }
            } finally { p.close() }
            return
        }
        // An action.
        stop?.poll()?.let { stoppedBy = it; line("$label 没做（${step.raw}）：停止条件 $it 已经满足"); return }
        val conds = step.until ?: if (quick) null else listOf(Cond("stable", "still"))
        val probe = conds?.let { Probe(this, d, it) }
        try {
            val done1 = doAction(sid, d, step, o.confirmed, lastTap)
            val fact = done1.text
            lastTap = if (step.kind == "tap" || step.kind == "press") lastPoint(sid) else if (step.kind == "type") lastTap else null
            val maxMs = step.untilMs ?: if (step.until != null) Steps.UNTIL_DEFAULT_MS else 3_000L
            if (done1.miss != null) {
                line("$label ${step.raw} → $fact")
                missed = "第 $label 步 ${step.raw}：${done1.miss}"
                return
            }
            if (probe == null) { line("$label ${step.raw} → $fact"); Thread.sleep(80); return }
            val w = waitOn(d, probe, maxMs, stop)
            val waitTxt = when {
                w.stopped != null -> { stoppedBy = w.stopped; "${s1(w.ms)} 时停止条件 ${w.stopped}" }
                w.met != null -> "等到 ${w.met}（${s1(w.ms)}）"
                step.until == null -> "等画面稳定：${s1(w.ms)} 到时间，画面还在变"
                else -> {
                    missed = "第 $label 步 ${step.raw} 做了，但没等到 ${step.until.joinToString(" or ") { it.raw }}（${probe.now()}）。${screenNow()}"
                    "等 ${step.until.joinToString(" or ") { it.raw }}：${s1(w.ms)} 到时间，没等到（${probe.now()}）"
                }
            }
            line("$label ${step.raw} → $fact；$waitTxt")
        } finally { probe?.close() }
    }
    fun runList(list: List<Step>, prefix: String, quickAll: Boolean, lastWaits: Boolean) {
        list.forEachIndexed { i, st ->
            val last = i == list.lastIndex
            runOne(st, "$prefix${i + 1}", 0, quick = quickAll && !(last && lastWaits && !realtime))
        }
    }
    try {
        val watch = o.watchMs ?: if (realtime && (steps.isNotEmpty() || !turnBased)) 1_000L else 0L
        if (turnBased && !o.paused && (steps.isNotEmpty() || o.watchMs != null)) {
            val moving = Probe(this, d, listOf(Cond("change", "change")))
            try {
                runList(resumeSteps!!, "继续 ", quickAll = true, lastWaits = false)
                resumed = true
                // v0.5: checked against the picture: a resumed app moves.
                val w = waitOn(d, moving, 1_000L, null)
                if (w.met == null) attention += "继续步骤做了，但 1 秒内画面没动：可能没继续上（坐标不对、被弹窗挡住），这段的动作是在暂停状态下做的"
            } finally { moving.close() }
        }
        // Normal apps wait for a still picture after each action (at most 3 s); realtime apps go straight on.
        runList(steps, "", quickAll = realtime, lastWaits = true)
        n = steps.size
        if (steps.isEmpty() && !realtime && stoppedBy == null) {
            val p = Probe(this, d, listOf(Cond("stable", "still")))
            try {
                val w = waitOn(d, p, 3_000L, stop)
                line(when {
                    w.stopped != null -> { stoppedBy = w.stopped; "看了一眼：${s1(w.ms)} 时停止条件 ${w.stopped}" }
                    w.met != null -> "看了一眼（画面稳定，${s1(w.ms)}）"
                    else -> "看了一眼（3 秒内一直有像素在动，没等到完全静止；有没有明显变化看【这段时间】）"
                })
            } finally { p.close() }
        }
        if (watch > 0 && stoppedBy == null) {
            val w = waitOn(d, null, watch, stop)
            line(if (w.stopped != null) { stoppedBy = w.stopped; "观察 ${s1(watch)}：${s1(w.ms)} 时停止条件 ${w.stopped}" } else "观察了 ${s1(w.ms)}")
        }
    } catch (e: StepRefused) {
        attention += "${e.code}：${e.message}"
        line("（从这里起没做：${e.code}：${e.message?.take(60)}）")
    } catch (e: ActionError) {
        when (e.code) {
            "REREAD_REQUIRED", "HOST_RESTARTED" -> { BackgroundOperation.acknowledgeReread(sid); attention += "${e.code}：${e.message}" }
            else -> attention += "${e.code}：${e.message}"
        }
        connectionLost(d)?.let { attention += it }
        line("（停在这里：${e.code}）")
    } finally {
        stop?.close()
        if (resumed && connectionLost(d) == null) {
            // The pause runs even when the segment stopped early (a game must not keep running while the caller thinks).
            val m = missed; missed = null
            runCatching { runList(pauseSteps!!, "暂停 ", quickAll = true, lastWaits = false) }
                .also { if (missed != null) attention += "暂停步骤没达到预期：$missed" ; missed = m }
                .onFailure { attention += "暂停步骤没做完：${it.message}" }
            // v0.5: the pause is checked against the picture, not assumed: a paused app stops moving.
            val p = Probe(this, d, listOf(Cond("stable", "still")))
            try {
                val w = waitOn(d, p, 2_500L, null)
                if (w.met != null) line("暂停后画面静止（${s1(w.ms)} 内确认）")
                else {
                    line("暂停后 2.5s 画面一直在变")
                    attention += "暂停步骤做了，但画面 2.5 秒内一直在变：可能没暂停住（坐标不对、被弹窗挡住），也可能暂停页本身有动画。看图核对；没停住就 note pause 改步骤"
                }
            } catch (e: ActionError) { attention += "暂停核对没做完：${e.code}" } finally { p.close() }
        }
    }
    missed?.let { m ->
        attention += "没达到预期，这段停在这里：$m" + (if (skipped > 0) "。后面 $skipped 步没做" else "") + "。看截图决定下一步"
        if (skipped > 0) line("（后面 $skipped 步没做：上一步没达到预期）")
    }
    if (o.stop.isNotEmpty()) line(if (stoppedBy != null) "停止条件触发：$stoppedBy" else "停止条件 ${o.stop.joinToString(" or ") { it.raw }}：这段里没出现")
    if (turnBased && !resumed && steps.isEmpty()) line("（这个应用记了暂停/继续步骤；这次没带步骤也没 --watch，所以没有继续，画面停在原状态）")
    mainCheckLines(main0, tasks0)?.let { attention += it }
    val top = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (app != null && top != null && top != app && top != context.packageName) attention += "副屏前台现在是 $top，不是你打开的 $app"
    return SegmentOut(done, attention, SystemClock.elapsedRealtime() - t0, start)
}

/** The elements to resolve #N / words against: the list last shown (for #N) or the screen now (for words). */
private fun VScreenOffloadHandler.target(sid: String, d: VScreenDisplay, t: StepTarget): Triple<Int, Int, String?> = when (t) {
    is StepTarget.At -> {
        if (t.x !in 0 until d.width || t.y !in 0 until d.height)
            throw StepRefused("OUT_OF_BOUNDS", "(${t.x},${t.y}) 在副屏 ${d.width}x${d.height} 外面")
        val label = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.list
            ?.filter { it.box.contains(t.x, t.y) }?.minByOrNull { it.box.width().toLong() * it.box.height() }?.label
        Triple(t.x, t.y, label)
    }
    is StepTarget.Ref -> {
        val set = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
            ?: throw StepRefused("NOT_FOUND", "还没有元素列表：先 act 看一眼再用 #编号")
        if (set.generation != d.generation) throw StepRefused("NOT_FOUND", "#${t.n} 是上一块副屏的列表（副屏重建过）：先 act 看一眼")
        val e = set.list.firstOrNull { it.n == t.n } ?: throw StepRefused("NOT_FOUND", "上次的列表里没有 #${t.n}（共 ${set.list.size} 个）")
        Triple(e.box.centerX(), e.box.centerY(), e.label)
    }
    is StepTarget.Words -> {
        val els = snapshot(d).els
        val q = VScreenOcr.norm(t.text)
        val exact = els.filter { VScreenOcr.norm(it.label) == q }
        val hits = exact.ifEmpty { els.filter { VScreenOcr.norm(it.label).contains(q) } }
        val e = hits.firstOrNull() ?: throw StepRefused("NOT_FOUND", "画面上没找到“${t.text}”（控件文字和 OCR 都没有）" + nearMiss(els, t.text))
        Triple(e.box.centerX(), e.box.centerY(), e.label + if (hits.size > 1) "，共 ${hits.size} 处，点了第一处" else "")
    }
}

/**
 * 1.1: what IS on the screen when a word target is missing: the closest words (shared characters) and the input
 * fields, each with its point, so the next try can aim at a point instead of guessing another word. Rotating
 * placeholder words (a browser's hot-search box) are the usual cause.
 */
internal fun nearMiss(els: List<El>, want: String): String {
    val q = VScreenOcr.norm(want)
    if (q.isEmpty()) return ""
    // Shared characters, relative to the shorter text; at least two shared (one shared character is chance).
    fun score(l: String): Double {
        val n = VScreenOcr.norm(l); if (n.isEmpty()) return 0.0
        val common = q.toSet().intersect(n.toSet()).size
        if (common < minOf(2, q.toSet().size)) return 0.0
        return common.toDouble() / minOf(q.toSet().size, n.toSet().size)
    }
    val close = els.filter { !it.label.startsWith("（") }.map { it to score(it.label) }.filter { it.second >= 0.5 }
        .sortedByDescending { it.second }.take(3).map { "“${it.first.label.take(24)}”(${it.first.box.centerX()},${it.first.box.centerY()})" }
    val fields = els.filter { it.editable }.take(3).map { "${it.label.take(20)}(${it.box.centerX()},${it.box.centerY()})" }
    val parts = buildList {
        if (close.isNotEmpty()) add("相近的字：${close.joinToString("、")}")
        if (fields.isNotEmpty()) add("输入框：${fields.joinToString("、")}")
    }
    return if (parts.isEmpty()) "。画面上也没有相近的字：看截图，用坐标点" else "。${parts.joinToString("；")}。文字会变的地方（轮播的提示词）用坐标点"
}

/** Refuse a press on a money / irreversible button without --confirmed. */
private fun payGuard(label: String?, confirmed: Boolean) {
    val l = label ?: return
    val kind = VScreenGame.commitKind(l) ?: return
    if (!confirmed) throw StepRefused("CONFIRM_REQUIRED", "“${l.take(20)}”是${if (kind == "spend") "付费/购买" else "不可撤销"}按钮，没点。" +
        "先把要做的事和金额告诉用户，用户同意后在这一段加 --confirmed 重发")
}

/** The user's per-app rule for the app on the screen (read-only: no taps / typing / gestures; blocked: only key back). */
private fun appRule(d: VScreenDisplay, step: Step) {
    if (VScreenStore.rules.value.isEmpty()) return
    val pkg = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull() ?: return
    val rule = VScreenStore.ruleFor(pkg) ?: return
    val hint = "这是用户在 Agent-VScreen › 应用限制 里定的，不要绕过（也不要从别的入口进同一个应用）"
    val back = step.kind == "key" && step.text == "back"
    if (rule == VScreenStore.Rule.BLOCK && !back) throw StepRefused("APP_BLOCKED", "副屏上是 $pkg，用户禁止后台操作它：只能 key back 离开或 stop。$hint")
    if (rule == VScreenStore.Rule.READONLY && step.kind !in setOf("scroll", "key")) throw StepRefused("APP_READ_ONLY", "副屏上是 $pkg，用户设为只读：能看、滚动、返回，不能点击/输入/滑动。$hint")
}

/** Do one action and say what was done (facts: where, with which method). */
/** The point the last tap / press of this thread went to (for the typing check). */
private val actPoint = ThreadLocal<Pair<Int, Int>?>()
/** The focused field before the last tap (see BackgroundOperation.typeFact). */
private val focusBeforeTap = ThreadLocal<String?>()
private fun lastPoint(@Suppress("UNUSED_PARAMETER") sid: String): Pair<Int, Int>? = actPoint.get()

/** Null while the privileged connection and the screen are alive; otherwise what the agent must know and do. */
internal fun connectionLost(d: VScreenDisplay): String? {
    val st = BackgroundOperation.state.value
    if (!d.released && st.connection == BackgroundOperation.Connection.READY) return null
    return "后台连接断了（${st.connectionError ?: st.connection.name}），副屏和上面的应用已经没了，不是你的操作造成的。" +
        "告诉用户；连接恢复（Shizuku 在运行，vscreen fix not_connected）后重新 launch"
}

/** What one action did ([text]), and [miss] when its own effect could not be confirmed (it ends the segment). */
internal class Done(val text: String, val miss: String? = null)

private fun VScreenOffloadHandler.doAction(sid: String, d: VScreenDisplay, step: Step, confirmed: Boolean, tappedAt: Pair<Int, Int>? = null): Done {
    appRule(d, step)
    actPoint.set(null)
    var miss: String? = null
    val pts = ArrayList<IntArray>()
    val fact = BackgroundOperation.agentAction(sid, null, isRead = false, describe = step.raw.take(40)) { dd ->
        fun okOr(o: VScreenShell.Outcome) { if (!o.ok) throw ActionError(o.code, o.message) }
        when (step.kind) {
            "tap" -> {
                val (x, y, label) = target(sid, dd, step.target!!)
                payGuard(label, confirmed)
                focusBeforeTap.set(BackgroundOperation.focusedFieldKey(dd.displayId))
                okOr(VScreenShell.tap(dd.displayId, x, y)); pts += intArrayOf(x, y); actPoint.set(x to y)
                "点了 ($x,$y)" + (label?.let { "，那里是“${it.take(30)}”" } ?: "")
            }
            "press" -> {
                val (x, y, label) = target(sid, dd, step.target!!)
                payGuard(label, confirmed)
                okOr(VScreenShell.longPress(dd.displayId, x, y, (step.ms ?: 800L).toInt())); pts += intArrayOf(x, y); actPoint.set(x to y)
                "按住 ($x,$y) ${step.ms}ms" + (label?.let { "，那里是“${it.take(30)}”" } ?: "")
            }
            "swipe" -> {
                val (a, b) = step.points
                bounds(dd, step.points)
                okOr(VScreenShell.swipe(dd.displayId, a.first, a.second, b.first, b.second, (step.ms ?: 300L).toInt()))
                pts += intArrayOf(a.first, a.second); pts += intArrayOf(b.first, b.second)
                "从 (${a.first},${a.second}) 滑到 (${b.first},${b.second})，${step.ms}ms"
            }
            "drag" -> {
                bounds(dd, step.points)
                val how = drag(dd, step.points, (step.ms ?: 600L).toInt())
                step.points.forEach { pts += intArrayOf(it.first, it.second) }
                "按住拖过 ${step.points.joinToString(" → ") { "(${it.first},${it.second})" }}，${step.ms}ms（$how）"
            }
            "scroll" -> {
                val c = step.target?.let { val (x, y, _) = target(sid, dd, it); x to y } ?: (dd.width / 2 to dd.height / 2)
                val dir = step.text!!
                val vertical = dir == "up" || dir == "down"
                val span = ((if (vertical) dd.height else dd.width) * 0.3).toInt()
                // "down" = see what is below: the finger moves up.
                val (x1, y1, x2, y2) = when (dir) {
                    "down" -> listOf(c.first, (c.second + span).coerceAtMost(dd.height - 2), c.first, (c.second - span).coerceAtLeast(1))
                    "up" -> listOf(c.first, (c.second - span).coerceAtLeast(1), c.first, (c.second + span).coerceAtMost(dd.height - 2))
                    "right" -> listOf((c.first + span).coerceAtMost(dd.width - 2), c.second, (c.first - span).coerceAtLeast(1), c.second)
                    else -> listOf((c.first - span).coerceAtLeast(1), c.second, (c.first + span).coerceAtMost(dd.width - 2), c.second)
                }
                okOr(VScreenShell.swipe(dd.displayId, x1, y1, x2, y2, 350))
                pts += intArrayOf(x1, y1); pts += intArrayOf(x2, y2)
                "手指从 ($x1,$y1) 滑到 ($x2,$y2)"
            }
            "type" -> {
                val f = BackgroundOperation.typeFact(dd.displayId, step.text!!, step.append, tappedAt, if (tappedAt != null) focusBeforeTap.get() else null)
                val where = f.bounds?.let { b -> " [${b.left},${b.top},${b.right},${b.bottom}]" + (f.fieldId?.let { "（$it）" } ?: "") } ?: ""
                when {
                    f.field == null -> {
                        miss = if (f.checked) "没有获得焦点的输入框，字很可能没进去。先点输入框（【现在】里标“可输入”的），等它标“有焦点”再 type"
                            else "无障碍没开，确认不了字落在哪。看截图核对，字进去了再发后面的步骤"
                        "按 ${f.method} 发了 ${step.text.length} 个字；" + if (f.checked) "没有获得焦点的输入框" else "读不到输入框"
                    }
                    f.field.startsWith("（密码框") -> "输入 ${step.text.length} 个字（${f.method}）；密码框$where ${f.field}"
                    !f.field.contains(step.text) -> {
                        miss = "输入框$where 现在是“${f.field.take(60)}”，不含你输入的字（这个框可能不收这种输入，或焦点在别处）"
                        "按 ${f.method} 发了 ${step.text.length} 个字；输入框$where 现在是“${f.field.take(60)}”，不含你输入的字"
                    }
                    else -> "输入 ${step.text.length} 个字（${f.method}）；输入框$where 现在是“${f.field.take(60)}”"
                }
            }
            "key" -> {
                val k = VScreenShell.keyWithMethod(dd.displayId, step.text!!)
                okOr(k.outcome)
                "按了 ${step.text}（${k.method}）"
            }
            else -> throw StepRefused("INVALID_STEP", "不是动作：${step.raw}")
        }
    }
    if (pts.isNotEmpty()) BackgroundOperation.actionLog(sid, 1).lastOrNull()?.trackN?.let { VScreenRecorder.setPoints(d.displayId, it, pts) }
    return Done(fact, miss)
}

private fun bounds(d: VScreenDisplay, pts: List<Pair<Int, Int>>) {
    pts.firstOrNull { it.first !in 0 until d.width || it.second !in 0 until d.height }?.let {
        throw StepRefused("OUT_OF_BOUNDS", "(${it.first},${it.second}) 在副屏 ${d.width}x${d.height} 外面")
    }
}

/**
 * One finger down at the first point, still 150 ms (games pick a piece up only after a hold), glide through the
 * points over [ms], still 120 ms, lift. Without the display service (app-owned screen), two points use a plain drag.
 */
private fun drag(d: VScreenDisplay, pts: List<Pair<Int, Int>>, ms: Int): String {
    val lens = pts.zipWithNext { a, b -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }
    val total = lens.sum().coerceAtLeast(1.0)
    d.boost(ms.toLong() + 3_000)
    val down = VScreenShell.finger(d.displayId, "down", 0, pts[0].first, pts[0].second).first
    if (!down.ok) {
        if (pts.size != 2) throw ActionError(down.code, "多点拖动需要副屏服务：${down.message}")
        val o = VScreenShell.drag(d.displayId, pts[0].first, pts[0].second, pts[1].first, pts[1].second, ms, 120)
        if (!o.ok) throw ActionError(o.code, o.message)
        return "整段注入"
    }
    try {
        Thread.sleep(150)
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", "用户接管了副屏，已抬起手指")
            val f = ((SystemClock.elapsedRealtime() - t0).toDouble() / ms).coerceIn(0.0, 1.0)
            var dist = f * total; var i = 0
            while (i < lens.size - 1 && dist > lens[i]) { dist -= lens[i]; i++ }
            val a = pts[i]; val b = pts[i + 1]; val k = if (lens[i] <= 0) 1.0 else (dist / lens[i]).coerceIn(0.0, 1.0)
            VScreenShell.finger(d.displayId, "move", 0, (a.first + (b.first - a.first) * k).roundToInt(), (a.second + (b.second - a.second) * k).roundToInt())
            if (f >= 1.0) break
            Thread.sleep(16)
        }
        Thread.sleep(120)
        VScreenShell.finger(d.displayId, "up", 0, pts.last().first, pts.last().second)
        return "手指注入"
    } finally { runCatching { VScreenShell.finger(d.displayId, "cancel", 0, -1, -1) } }
}

/** Main-screen facts for 【注意】: its top activity or its task list changed during the segment. */
internal fun VScreenOffloadHandler.mainCheckLines(before: String?, tasksBefore: Set<Int>?): String? {
    val after = mainTop()
    val lost = mainTasksLost(tasksBefore)
    if (before == after && lost.isEmpty()) return null
    return "主屏在这段前后变了（前台 $before → $after" + (if (lost.isNotEmpty()) "；主屏任务 ${lost.joinToString(",")} 不见了" else "") +
        "）。可能是用户自己切的，也可能被打扰了：停下，先告诉用户"
}

// ── the command ──────────────────────────────────────────────────────

/** `act ["<steps>"] [--stop C] [--watch T] [--paused] [--keep notify|wake] [--for T] [--save NAME] [--confirmed]` */
internal fun VScreenOffloadHandler.act(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
    val body = args.positional.joinToString(" ").trim()
    val steps = if (body.isEmpty()) emptyList() else Steps.parseAll(body)
    val stop = args.get("stop")?.let { Steps.conds(it) }.orEmpty()
    val keep = args.get("keep")?.lowercase()?.also {
        if (it !in setOf("notify", "wake")) throw ActionError("INVALID_ARGS", "--keep notify（通知用户）或 --keep wake（发事件给调用方，用 events 取）")
        if (stop.isEmpty()) throw ActionError("INVALID_ARGS", "--keep 盯的是 --stop 的条件：一起写，如 --stop \"text:到货\" --keep notify")
    }
    val watch = args.getLong("watch")?.let { bounded("watch", it, 0L, 120_000L) }
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val out = runSegment(s, d, steps, SegmentOpts(stop, watch, args.hasFlag("paused"), args.hasFlag("confirmed")))
    val done = ArrayList(out.done)
    if (steps.isNotEmpty() && body.isNotEmpty()) args.get("save")?.let { name ->
        val app = workApp(s, d) ?: throw ActionError("NO_APP", "副屏上没有应用，段存不到哪里")
        VScreenMap.saveSegment(app, name.trim(), body)
        done += "已存为 $app 的段“${name.trim()}”（以后 use ${name.trim()}）"
    }
    keep?.let { k -> startKeep(s, d, args.get("stop")!!, stop, k, args.getLong("for") ?: 3_600_000L)?.let { out.attention += it } }
    takeAdjusted().forEach { out.attention += "参数被调整：$it" }
    // The user's "blocked" apps are never read or shown, whichever way the segment got there.
    val pkgNow = runCatching { VScreenShell.topPackage(d.displayId) }.getOrNull()
    if (pkgNow != null && VScreenStore.ruleFor(pkgNow) == VScreenStore.Rule.BLOCK) return blockedBlocks(done, out.attention, pkgNow)
    return fourBlocks(s, d, done, out.attention, out.start, out.actionMs, "act", fullList = steps.isEmpty())
}

/** 【现在】 of an app the user blocked: nothing of it is read or shown. */
internal fun blockedBlocks(done: List<String>, attention: List<String>, pkg: String): NativeOffloadResult {
    val sb = StringBuilder("【做了什么】\n")
    done.forEach { sb.append(it).append('\n') }
    sb.append("\n【这段时间】\n（不读取：用户禁止后台操作 $pkg）\n\n【现在】 $pkg\n（不读取：用户禁止后台操作这个应用。key back 离开或 stop）\n\n【注意】\n")
    (attention + "APP_BLOCKED：副屏上是 $pkg，用户禁止后台操作它").distinct().forEach { sb.append("- ").append(it).append('\n') }
    return NativeOffloadResult(0, sb.toString())
}

private var keepSeq = 0

/** Start watching [conds] after the turn. Returns a line for 【注意】. */
private fun VScreenOffloadHandler.startKeep(sid: String, d0: VScreenDisplay, spec: String, conds: List<Cond>, how: String, forMs0: Long): String? {
    val forMs = bounded("for", forMs0, 10_000L, 12 * 3_600_000L)
    val app = runCatching { VScreenShell.topPackage(d0.displayId) }.getOrNull()
    val name = "盯${synchronized(VScreenRules) { ++keepSeq }}"
    val host = object : VScreenRules.Host {
        private var probe: Probe? = null
        private var on: VScreenDisplay? = null
        override fun check(l: VScreenRules.Live, d: VScreenDisplay): String? {
            if (on !== d) { probe?.close(); probe = Probe(this@startKeep, d, conds); on = d }
            VScreenAwake.ensure(d)
            VScreenPerception.ensure(d)
            d.requestFreshFrame()
            val hit = probe!!.poll()
            if (hit != null) { probe?.close(); probe = null; on = null }
            return hit
        }
    }
    val spec2 = VScreenRules.Spec(name, sid, spec, app, 1_000L, forMs, wake = how == "wake", notify = how == "notify")
    val l = try { VScreenRules.start(spec2, host) } catch (e: IllegalStateException) { return "KEEP_LIMIT：${e.message}" }
    val warn = if (how == "notify") VScreenHandoff.notifyBlocked(context)?.let { "；但通知发不出去（$it）：请用户打开 Agent-VScreen 的通知，或改用 --keep wake" } else null
    return "本轮之后继续盯「${l.spec.name}」：$spec，最长 ${forMs / 60_000} 分钟，看到就${if (how == "wake") "发一条事件（vscreen events --wait 取）" else "通知用户"}，然后结束" +
        (warn ?: "") + "。副屏会保持在这一页；keep 查看，keep off ${l.spec.name} 取消。告诉用户你在盯什么、多久"
}

// ── launch ──────────────────────────────────────────────────────────

/** `launch <app> [--landscape|--portrait] [--restart]`: open it, look, return the four blocks. */
internal fun VScreenOffloadHandler.launchCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
    val q = args.positional.joinToString(" ").trim().ifEmpty { throw ActionError("INVALID_ARGS", "launch <应用名或包名>") }
    val pkg = VScreenKnowledge.resolveApp(q) ?: throw ActionError("NO_APP", "没找到“$q”：apps $q 看看已安装的应用和包名")
    if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK) throw ActionError("APP_BLOCKED", "用户禁止后台操作 $pkg（Agent-VScreen › 应用限制），不要绕过")
    val start = System.currentTimeMillis()
    val t0 = SystemClock.elapsedRealtime()
    val main0 = mainTop(); val tasks0 = mainTaskIds()
    val orient = when { args.hasFlag("landscape") -> true; args.hasFlag("portrait") -> false; else -> null }
    val facts = doLaunch(s, pkg, orient, args.hasFlag("restart"))
    val d = BackgroundOperation.acquire(s, create = false)
    VScreenPerception.ensure(d)
    val done = ArrayList(facts.done)
    // What the agent wrote down about this app before.
    val m = VScreenMap.load(pkg)
    if (m.notes.isNotEmpty()) done += "你以前记下的（note）：" + m.notes.takeLast(12).joinToString("；") { "${it.id}. ${it.text}" }
    if (m.segments.isNotEmpty()) done += "保存的段（use 名字）：" + m.segments.joinToString("；") { "${it.name} = ${it.steps.take(80)}" }
    val p = m.profile
    if (p["pause"] != null && p["resume"] != null) done += "回合制：每段 act 先“${p["resume"]}”继续，末尾“${p["pause"]}”暂停"
    else if (p["realtime"] == "true") done += "记为实时应用（不带步骤的 act 看 1 秒）"
    val attention = facts.attention
    mainCheckLines(main0, tasks0)?.let { attention += it }
    takeAdjusted().forEach { attention += "参数被调整：$it" }
    return fourBlocks(s, d, done, attention, start, SystemClock.elapsedRealtime() - t0, "launch", fullList = true)
}
