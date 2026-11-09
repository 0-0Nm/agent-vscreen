package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenActivity
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.core.VScreenDisplayHealth
import com.kilon.agentvscreen.core.VScreenShell
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

// [vscreen] v49: the fixed four-block return of `act` and `launch`.
//
//   【做了什么】   the steps that ran, each wait met or timed out, which stop fired — facts, no verdicts;
//   【这段时间】   since the previous return (the model's thinking time included): words that appeared / went / came
//                 and went, where the picture moved and for how long, and a contact sheet from the recording track
//                 (only when something changed);
//   【现在】       the foreground app, numbered elements (accessibility + OCR, with their points) and a screenshot
//                 with screen coordinates on its edges;
//   【注意】       safety (main screen, pay guard, takeover, app rules) and failure facts only.
// The screenshot and the sheet are one picture handed to the model with the result.

/** One element of the 【现在】 list. [n] is what `tap #N` uses. */
internal data class El(val n: Int, val label: String, val box: Rect, val flags: String, val fromA11y: Boolean, val editable: Boolean = false)

/** The elements last shown to a chat, for `#N` (bound to the screen generation they were read on). */
internal class ElSet(val generation: Long, val atMs: Long, val list: List<El>)

/** What 【这段时间】 says, plus the frames for its sheet. */
internal class Since(val lines: List<String>, val changed: Boolean, val from: Long, val to: Long)

internal object Report {
    /** Lines of the 【现在】 list printed at most (all are kept for #N and zoom). */
    const val MAX_ELEMENTS = 160
    const val NOW_SIDE = 1280
    const val CELL_SIDE = 300
    const val MAX_CELLS = 8

    /** "-3.2s" relative to [now]. */
    fun ago(t: Long, now: Long): String = "-${((now - t).coerceAtLeast(0) / 100) / 10.0}s"

    fun box(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"
}

// ── elements ─────────────────────────────────────────────────────────

/** An accessibility node a person could see or act on. */
internal class RawEl(val label: String, val box: Rect, val flags: String, val a11y: Boolean, val editable: Boolean)

/** The accessibility side of the elements (no OCR): what nodes say, where, and what can be done with them. */
internal fun a11yRaw(d: VScreenDisplay): List<RawEl> {
    val raw = ArrayList<RawEl>()
    val screenArea = d.width.toLong() * d.height
    var seen = 0
    // 1.1: a clickable container with no words of its own (a list row, a card, a search box drawn by the app) takes the
    // words of the plain text nodes inside it, and those nodes are not listed again: one line per thing a person
    // would tap, with the words they would read on it. A wordless node is listed only when it is small (an icon
    // button); big wordless wrappers are layout, not targets. Its view id (stable across rotating placeholder
    // words) is shown when the app gives one.
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
        if (n == null || depth > 50 || seen++ > 3_000 || raw.size > 400) return
        val visible = n.isVisibleToUser
        val text = if (visible) (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() && !isJunkLabel(it) } else null
        val actionable = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable
        val b = Rect().also { n.getBoundsInScreen(it) }
        val inside = visible && b.width() > 0 && b.height() > 0 && b.centerX() in 0 until d.width && b.centerY() in 0 until d.height
        val area = b.width().toLong() * b.height()
        val big = area > screenArea / 4
        val start = raw.size
        for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        if (!inside || !(text != null || (actionable && !big) || n.isEditable)) return
        val flags = buildList {
            if (n.isEditable) add("可输入") else if (n.isClickable) add("可点")
            if (n.isScrollable) add("可滚动")
            if (n.isCheckable) add(if (n.isChecked) "已选" else "未选")
            if (n.isFocused && n.isEditable) add("有焦点")
            if (n.isPassword) add("密码")
        }.joinToString(" ")
        var label = text?.let { tidy(it) }
        if (label == null && !n.isEditable && (n.isClickable || n.isCheckable)) {
            // Plain words drawn inside this tappable node (not inside a tappable child of it).
            val inner = (start until raw.size).filter { i -> raw[i].let { it.flags.isEmpty() && !it.editable && !it.label.startsWith("（") && b.contains(it.box.centerX(), it.box.centerY()) } }
            if (inner.isNotEmpty()) {
                label = inner.map { raw[it].label }.distinct().joinToString(" · ").let { if (it.length > 60) it.take(58) + "…" else it }
                inner.asReversed().forEach { raw.removeAt(it) }
            }
        }
        val id = n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.length in 2..32 }
        if (label == null) {
            if (!n.isEditable && area > screenArea / 40) return
            label = if (n.isEditable) "（空输入框${id?.let { " $it" } ?: ""}）"
                else "（无文字${(id ?: n.className?.toString()?.substringAfterLast('.')?.takeIf { it.length in 3..24 })?.let { " $it" } ?: ""}）"
        }
        raw.add(start.coerceAtMost(raw.size), RawEl(label, b, flags, true, n.isEditable))
    }
    BackgroundOperation.nodeRoots(d.displayId).forEach { walk(it, 0) }
    // Same label at (nearly) the same place: once.
    val out = ArrayList<RawEl>()
    for (r in raw) if (out.none { it.label == r.label && Math.abs(it.box.centerX() - r.box.centerX()) <= 20 && Math.abs(it.box.centerY() - r.box.centerY()) <= 20 }) out += r
    return out
}

/** Internal names that are not words on the screen: hex hashes, code identifiers (virtualNode). */
private val HEXISH = Regex("^[0-9a-fA-F]{5,}$")
private val CODE_ID = Regex("^[a-z]{3,}[A-Z][a-z]+[A-Za-z0-9]*$")
internal fun isJunkLabel(t: String): Boolean = (HEXISH.matches(t) && t.any { it.isDigit() } && t.any { it.isLetter() }) || (t.length >= 8 && CODE_ID.matches(t))

private val DIGIT = Regex("[0-9]")

/**
 * The elements of one picture: accessibility nodes, then the OCR words no node already says, in reading order.
 * v0.5: when a node's text has digits and the picture shows different digits in its place (an accessibility tree
 * that lags behind the picture: a timer, a counter), the picture's text wins — the list says what is drawn.
 * Nothing is cut here; the printer decides how many lines to show.
 */
internal fun mergeElements(d: VScreenDisplay, a11y: List<RawEl>, ocr: List<VScreenOcr.Item>?): List<El> {
    val items = ocr.orEmpty().filter { !VScreenOcr.isJunk(it) && !isJunkLabel(it.text.trim()) && it.text.isNotBlank() }
    val out = ArrayList<RawEl>()
    val usedOcr = HashSet<VScreenOcr.Item>()
    for (a in a11y) {
        if (!a.label.startsWith("（") && DIGIT.containsMatchIn(a.label) && a.label.length <= 24) {
            val drawn = items.filter { Rect(a.box).apply { inset(-8, -8) }.contains(it.box.centerX(), it.box.centerY()) && DIGIT.containsMatchIn(it.text) }
            val drawnText = drawn.joinToString(" ") { it.text.trim() }
            if (drawn.isNotEmpty() && VScreenOcr.norm(drawnText) != VScreenOcr.norm(a.label) && !VScreenOcr.norm(drawnText).contains(VScreenOcr.norm(a.label))) {
                usedOcr += drawn
                out += RawEl(tidy(drawnText), a.box, a.flags, true, a.editable)
                continue
            }
        }
        out += a
    }
    for (it in items) {
        if (it in usedOcr) continue
        val t = it.text.replace('\n', ' ').trim()
        val nt = VScreenOcr.norm(t)
        val said = out.any { a -> a.a11y && Rect.intersects(a.box, it.box) && VScreenOcr.norm(a.label).let { l -> l.isNotEmpty() && (l.contains(nt) || nt.contains(l)) } }
        if (!said) out += RawEl(tidy(t), Rect(it.box), "", false, false)
    }
    val row = maxOf(16, d.height / 120)
    return out.sortedWith(compareBy({ it.box.centerY() / row }, { it.box.centerX() }))
        .mapIndexed { i, r -> El(i + 1, r.label, r.box, r.flags, r.a11y, r.editable) }
}

/** One look at the screen: a frame, the OCR words of exactly that frame, the elements, the foreground activity. */
internal class Snap(val frame: Bitmap?, val version: Long, val items: List<VScreenOcr.Item>?, val els: List<El>,
                    val activity: String?, val ocrReused: Boolean, val frameFresh: Boolean, val frameAgeMs: Long)

private val snapPool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "vscreen-snap").apply { isDaemon = true } }

/**
 * v0.5 — the one way the tool looks at the screen now (four blocks, words targets, look).
 *  - The words are read from the SAME frame the screenshot shows (the list and the picture cannot disagree).
 *  - When the perception thread already read this picture (same frame, or no sampled pixel differs), its reading is
 *    used as is: no second OCR of an unchanged screen. Otherwise OCR runs once, on this frame, and the result is handed
 *    to the perception thread (it will not read the same picture again).
 *  - The accessibility walk and the foreground lookup run in parallel with the OCR.
 *  - If the picture changed while it was being read (a page still fading in), it is read once more.
 */
internal fun VScreenOffloadHandler.snapshot(d: VScreenDisplay, freshMs: Long = 100L): Snap {
    VScreenPerception.ensure(d)
    var attempt = 0
    while (true) {
        val t0 = SystemClock.elapsedRealtime()
        d.requestFreshFrame()
        val sample = d.awaitFrameAfterInfo(t0, freshMs)
        val a11yF = snapPool.submit<List<RawEl>> { runCatching { a11yRaw(d) }.getOrDefault(emptyList()) }
        val actF = snapPool.submit<String?> { runCatching { VScreenShell.topActivity(d.displayId) }.getOrNull() }
        var reused = false
        val items: List<VScreenOcr.Item>? = if (sample == null) null else {
            VScreenPerception.readingFor(d.displayId, sample.bitmap, sample.version)?.let { reused = true; it.items }
                ?: runCatching { VScreenOcr.recognize(d.displayId, sample.bitmap, sample.version, accurate = VScreenOcr.accurateEnabled()) }
                    .getOrNull()?.takeIf { it.error == null }?.let { r ->
                        val a11yTexts = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList()).map { it.label }
                        VScreenPerception.offerReading(d.displayId, sample.bitmap, sample.version, r, a11yTexts)
                        r.items
                    }
        }
        val raw = runCatching { a11yF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        val act = runCatching { actF.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        val after = d.latestWithVersion()
        if (attempt == 0 && sample != null && after != null && after.second != sample.version && !samePicture(sample.bitmap, after.first)) { attempt++; continue }
        return Snap(sample?.bitmap, sample?.version ?: -1L, items, mergeElements(d, raw, items), act, reused, sample?.fresh == true, sample?.ageMs ?: -1L)
    }
}

private fun samePicture(a: Bitmap, b: Bitmap): Boolean {
    if (a === b) return true
    val x = VScreenPerception.small(a); val y = VScreenPerception.small(b)
    return try { x.width == y.width && x.height == y.height && com.kilon.agentvscreen.core.VPixels.regionDiff(x, y, Rect(0, 0, x.width, x.height)) in 0.0..0.002 }
    finally { x.recycle(); y.recycle() }
}

private val PCT = Regex("%[0-9A-Fa-f]{2}")
private val URLISH = Regex("^(https?://|[a-z][a-z0-9+.-]*://)|[?&][\\w%]+=")

/** Labels: URL-encoded tracking strings are summarised, long ones cut (and said so). */
internal fun tidy(raw: String): String {
    val t = raw.trim()
    if (PCT.findAll(t).count() >= 3 || (t.length > 40 && !t.contains(' ') && URLISH.containsMatchIn(t))) return "（链接/标识 ${t.length} 字）"
    return if (t.length > 40) t.take(40) + "…（共 ${t.length} 字）" else t
}

internal fun El.line(): String {
    val c = "(${box.centerX()},${box.centerY()})"
    return "#$n “$label” $c" + if (flags.isNotEmpty()) " $flags" else ""
}

// ── 这段时间 ──────────────────────────────────────────────────────────

/**
 * What happened on [d] between [from] and [to] (epoch ms), from the perception readings and the recording track:
 * words that appeared, went, or came and went; spans when the picture changed, where and how much.
 */
internal fun sinceHeader(from: Long, to: Long, actionMs: Long): String {
    val span = ((to - from) / 100) / 10.0
    return if (actionMs > 0 && to - from > actionMs + 300) "上次返回到现在 ${span}s（其中这段动作 ${(actionMs / 100) / 10.0}s，之前 ${((to - from - actionMs) / 100) / 10.0}s 是你在思考或做别的）"
        else "这段 ${span}s"
}

/** Words that appeared, went, or came and went between [from] and [to], from the perception readings. */
internal fun wordFacts(d: VScreenDisplay, from: Long, to: Long): List<String> {
    val lines = ArrayList<String>()
    val rs = VScreenPerception.readings(d.displayId, from, to)
    if (rs.size >= 2) {
        val then = rs.first().words; val now = rs.last().words
        val thenN = then.map { VScreenOcr.norm(it) }.toSet(); val nowN = now.map { VScreenOcr.norm(it) }.toSet()
        val appeared = now.filter { VScreenOcr.norm(it) !in thenN }
        val gone = then.filter { VScreenOcr.norm(it) !in nowN }
        val between = LinkedHashMap<String, String>()
        for (r in rs.drop(1).dropLast(1)) for (w in r.words) { val k = VScreenOcr.norm(w); if (k !in thenN && k !in nowN) between.putIfAbsent(k, w) }
        if (appeared.isNotEmpty()) lines += "出现了：" + list(appeared, 20)
        if (gone.isNotEmpty()) lines += "不见了：" + list(gone, 20)
        if (between.isNotEmpty()) lines += "中途出现过又消失：" + list(between.values.toList(), 12)
    }
    return lines
}

/** Where and how long the picture changed between [from] and [to], from the recording track. (lines, changed) */
internal fun pictureFacts(d: VScreenDisplay, from: Long, to: Long): Pair<List<String>, Boolean> {
    val lines = ArrayList<String>()
    val frames = VScreenRecorder.frames(d.displayId, from, to)
    if (frames.isEmpty()) return lines to false
    val base = VScreenRecorder.frameAt(d.displayId, from)
    val sample = (listOfNotNull(base?.takeIf { it.atMs < (frames.firstOrNull()?.atMs ?: 0L) }) + frames.let { fs ->
        if (fs.size <= 40) fs else (0 until 40).map { fs[it * (fs.size - 1) / 39] }.distinct()
    })
    val pics = sample.mapNotNull { f -> VScreenRecorder.decode(f, 160)?.let { f.atMs to it } }
    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    class Span(val start: Long, var end: Long, var area: Rect, var share: Double)
    val spans = ArrayList<Span>()
    try {
        for ((a, b) in pics.zipWithNext()) {
            val rows = VScreenActivity.rowsFor(a.second.width, a.second.height)
            val c = VScreenActivity.cells(a.second, b.second, VScreenActivity.COLS, rows) ?: continue
            val share = VScreenActivity.share(c)
            if (share <= 0.0) continue
            val bb = VScreenActivity.bbox(c, VScreenActivity.COLS, rows, fw, fh) ?: continue
            val last = spans.lastOrNull()
            if (last != null && a.first - last.end <= 600L) { last.end = b.first; last.area.union(bb); last.share = maxOf(last.share, share) }
            else spans += Span(a.first, b.first, Rect(bb), share)
        }
        if (spans.isEmpty()) return lines to false
        val firstLast = if (pics.size >= 2) {
            val a = pics.first().second; val b = pics.last().second
            VScreenActivity.cells(a, b, VScreenActivity.COLS, VScreenActivity.rowsFor(a.width, a.height))?.let { c ->
                VScreenActivity.share(c) to VScreenActivity.bbox(c, VScreenActivity.COLS, VScreenActivity.rowsFor(a.width, a.height), fw, fh)
            }
        } else null
        val shown = spans.sortedByDescending { (it.end - it.start) * (it.share + 0.01) }.take(6).sortedBy { it.start }
        lines += "画面变化（时间相对现在，区域是副屏坐标，百分比是变了的格子占全屏）：" + shown.joinToString("；") { sp ->
            val dur = ((sp.end - sp.start) / 100) / 10.0
            "${Report.ago(sp.start, to)}～${if (to - sp.end < 400) "现在" else Report.ago(sp.end, to)}（${dur}s）[${Report.box(sp.area)}] ${(sp.share * 100).roundToInt()}%"
        } + if (spans.size > shown.size) "；另有 ${spans.size - shown.size} 段较小的变化" else ""
        firstLast?.let { (share, bb) ->
            lines += if (share <= 0.0) "开头和现在的画面一样（中间动过又回去了）"
                else "开头和现在相比：${(share * 100).roundToInt()}% 的格子不同" + (bb?.let { " [${Report.box(it)}]" } ?: "")
        }
        return lines to true
    } finally { pics.forEach { it.second.recycle() } }
}

private fun list(ws: List<String>, max: Int): String =
    ws.take(max).joinToString("、") { "“${it.take(30)}”" } + if (ws.size > max) " 等 ${ws.size} 处" else ""

// ── the picture ──────────────────────────────────────────────────────

/**
 * One image for the model: the screen now (long side [Report.NOW_SIDE]) with screen coordinates on its top and left
 * edges, and — when [since] says something changed — a sheet of frames from the track next to it (each cell: how long
 * ago, the actions done before it drawn as a red dot / line). Saved in the chat's attachments and offered inline.
 */
internal fun VScreenOffloadHandler.picture(sid: String, d: VScreenDisplay, frame: Bitmap, since: Since?, tag: String,
                                           cells0: List<Pair<Long, Bitmap>>? = null): String? = runCatching {
    val k = minOf(1.0, Report.NOW_SIDE.toDouble() / maxOf(frame.width, frame.height))
    val nw = (frame.width * k).roundToInt().coerceAtLeast(1); val nh = (frame.height * k).roundToInt().coerceAtLeast(1)
    val margin = 30; val title = 26
    // The sheet.
    val cells: List<Pair<Long, Bitmap>> = cells0 ?: if (since?.changed == true) pickTrack(d.displayId, since.from, since.to, Report.MAX_CELLS) else emptyList()
    val marks = if (cells.isNotEmpty()) VScreenRecorder.marks(d.displayId, since!!.from - 1, since.to).filter { it.kind == "action" } else emptyList()
    val (fw, fh) = VScreenRecorder.frameSize(d.displayId) ?: (d.width to d.height)
    val landscape = frame.width > frame.height
    val cw = cells.maxOfOrNull { it.second.width } ?: 0; val ch = cells.maxOfOrNull { it.second.height } ?: 0
    val cap = 22; val pad = 6
    val cols = when { cells.isEmpty() -> 0; landscape -> minOf(4, cells.size); cells.size <= 4 -> 1; else -> 2 }
    val rows = if (cols == 0) 0 else (cells.size + cols - 1) / cols
    val sheetW = if (cols == 0) 0 else cols * (cw + pad) + pad
    val sheetH = if (cols == 0) 0 else rows * (ch + cap + pad) + pad + title
    val nowW = margin + nw + pad; val nowH = title + margin + nh + pad
    val totalW = if (landscape) maxOf(nowW, sheetW) else nowW + sheetW
    val totalH = if (landscape) nowH + sheetH else maxOf(nowH, sheetH)
    val out = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val c = Canvas(out); c.drawColor(Color.WHITE)
    val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = Color.BLACK; isFakeBoldText = true }
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.rgb(30, 30, 160) }
    val line = Paint().apply { color = Color.rgb(30, 30, 160); strokeWidth = 1f }
    val faint = Paint().apply { color = Color.argb(70, 30, 30, 160); strokeWidth = 1f }
    c.drawText("现在（边上是副屏坐标）", margin.toFloat(), 20f, head)
    val ox = margin; val oy = title + margin
    c.drawBitmap(frame, null, Rect(ox, oy, ox + nw, oy + nh), Paint(Paint.FILTER_BITMAP_FLAG))
    // Ticks every 100 screen px, numbers every 200 (every 500 on big screens), faint lines across every 200/500.
    val stepLabel = if (maxOf(d.width, d.height) > 2600) 500 else 200
    var v = 0
    while (v <= d.width) {
        val x = ox + (v * k).toFloat()
        c.drawLine(x, (oy - 6).toFloat(), x, oy.toFloat(), line)
        if (v % stepLabel == 0) { c.drawText(v.toString(), x + 2f, (oy - 8).toFloat(), tick); if (v in 1 until d.width) c.drawLine(x, oy.toFloat(), x, (oy + nh).toFloat(), faint) }
        v += 100
    }
    v = 0
    while (v <= d.height) {
        val y = oy + (v * k).toFloat()
        c.drawLine((ox - 6).toFloat(), y, ox.toFloat(), y, line)
        if (v % stepLabel == 0) {
            c.save(); c.rotate(-90f, (ox - 9).toFloat(), y - 2f); c.drawText(v.toString(), (ox - 9).toFloat(), y - 2f, tick); c.restore()
            if (v in 1 until d.height) c.drawLine(ox.toFloat(), y, (ox + nw).toFloat(), y, faint)
        }
        v += 100
    }
    if (cells.isNotEmpty()) {
        val sx = if (landscape) 0 else nowW; val sy = if (landscape) nowH else 0
        c.drawText("这段时间（${Report.ago(since!!.from, since.to)} 到现在，红点=点击 红线=拖动）", (sx + pad).toFloat(), (sy + 20).toFloat(), head)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = Color.BLACK }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 30, 60); style = Paint.Style.STROKE; strokeWidth = 3f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 30, 60) }
        var prevT = Long.MIN_VALUE
        cells.forEachIndexed { i, (t, b) ->
            val x = sx + pad + (i % cols) * (cw + pad); val y = sy + title + pad + (i / cols) * (ch + cap + pad)
            c.drawBitmap(b, null, Rect(x, y, x + b.width, y + b.height), null)
            val kx = b.width.toFloat() / maxOf(1, fw); val ky = b.height.toFloat() / maxOf(1, fh)
            val here = marks.filter { it.atMs > prevT && it.atMs <= t }
            for (m in here) {
                val pts = m.pts.map { (x + it[0] * kx) to (y + it[1] * ky) }
                if (pts.size == 1) { c.drawCircle(pts[0].first, pts[0].second, 7f, fill); c.drawCircle(pts[0].first, pts[0].second, 11f, dot) }
                else for ((a, bb) in pts.zipWithNext()) c.drawLine(a.first, a.second, bb.first, bb.second, dot)
            }
            c.drawText("${i + 1}  ${Report.ago(t, since.to)}" + if (here.isNotEmpty()) "  ${here.joinToString(" ") { it.label.take(14) }}".take(34) else "",
                (x + 2).toFloat(), (y + ch + 16).toFloat(), tp)
            prevT = t
        }
    }
    cells.forEach { it.second.recycle() }
    savePic(sid, out, tag).also { out.recycle() }
}.getOrNull()

/** Save [bmp] as a JPEG in the shot store and return its reference (the API layer turns it into a URL, a local file or an inline image). */
internal fun VScreenOffloadHandler.savePic(sid: String, bmp: Bitmap, tag: String): String? = ShotStore.save(context, bmp, tag)

/**
 * Frames from the track for a sheet: the one showing at [from], the first after every action, then evenly spread,
 * at most [max] (caller recycles).
 */
internal fun pickTrack(displayId: Int, from: Long, to: Long, max: Int, maxSide: Int = Report.CELL_SIDE): List<Pair<Long, Bitmap>> {
    val all = VScreenRecorder.frames(displayId, from - 120_000L, to)
    if (all.isEmpty()) return emptyList()
    val times = all.map { it.atMs }
    val acts = VScreenRecorder.marks(displayId, from, to).filter { it.kind == "action" }.map { it.atMs }
    val first = times.indices.lastOrNull { times[it] <= from } ?: 0
    val inside = times.indices.filter { times[it] in (from + 1)..to }
    val chosen = sortedSetOf(first)
    for (a in acts) inside.firstOrNull { times[it] >= a }?.let { if (chosen.size < max) chosen += it }
    val room = max - chosen.size
    if (room > 0 && inside.isNotEmpty()) {
        val rest = inside.filter { it !in chosen }
        val spread = if (rest.size <= room) rest else (0 until room).map { kk -> rest[(kk * (rest.size - 1)) / maxOf(1, room - 1)] }
        chosen.addAll(spread)
    }
    return chosen.toList().take(max).mapNotNull { i -> VScreenRecorder.decode(all[i], maxSide)?.let { all[i].atMs to it } }
}

// ── the four blocks ──────────────────────────────────────────────────

/** The text of a four-block return. [done] and [attention] are lines; the other two are built here. */
internal fun VScreenOffloadHandler.fourBlocks(sid: String, d: VScreenDisplay, done: List<String>, attention: MutableList<String>,
                                              segmentStart: Long, actionMs: Long, tag: String, fullList: Boolean = false): NativeOffloadResult {
    // Whoever is handed 【现在】 has seen the current page: that is the re-read a takeover or restart asks for.
    BackgroundOperation.acknowledgeReread(sid)
    val now = System.currentTimeMillis()
    val last = synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] }
    val from = if (last != null && now - last <= 600_000L && last <= segmentStart) last else segmentStart
    // The screen is gone (privileged connection lost): say so instead of describing an empty screen.
    connectionLost(d)?.let { lost ->
        val sb = StringBuilder("【做了什么】\n"); done.forEach { sb.append(it).append('\n') }
        sb.append("\n【这段时间】\n（副屏已不存在，没有录屏）\n\n【现在】\n（没有副屏）\n\n【注意】\n")
        (attention + lost).distinct().forEach { sb.append("- ").append(it).append('\n') }
        synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] = System.currentTimeMillis() }
        return NativeOffloadResult(0, sb.toString())
    }
    // v0.5: the recording-track work (decoding frames for 【这段时间】 and the sheet) runs while the screen is read.
    val picF = REPORT_POOL.submit<Pair<List<String>, Boolean>> { runCatching { pictureFacts(d, from, now) }.getOrElse { listOf("（读不到录屏：${it.message}）") to false } }
    val cellsF = REPORT_POOL.submit<List<Pair<Long, Bitmap>>> { runCatching { pickTrack(d.displayId, from, now, Report.MAX_CELLS) }.getOrDefault(emptyList()) }
    val snap = snapshot(d)
    VScreenDisplayHealth.cachedFrameNote(snap.frame != null, snap.frameFresh, snap.frameAgeMs)?.let { attention += "$it；先 read/重试确认" }
    val (picLines, changed) = runCatching { picF.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList<String>() to false)
    val cells = runCatching { cellsF.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
    val words = wordFacts(d, from, System.currentTimeMillis())
    val sinceLines = ArrayList<String>().apply { add(sinceHeader(from, now, actionMs)); addAll(words); addAll(picLines); if (size == 1) add("文字和画面都没有明显变化（光标、细小动画这类小变动不算）") }
    val since = Since(sinceLines, changed, from, now)
    val els = numbered(sid, d, snap.els)
    val prev = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }
    synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] = ElSet(d.generation, now, els) }
    val shownCells = if (changed) cells else { cells.forEach { it.second.recycle() }; emptyList() }
    val pic = snap.frame?.let { picture(sid, d, it, since, tag, shownCells) } ?: run { shownCells.forEach { it.second.recycle() }; null }
    if (snap.activity == null) attention += "副屏上没有应用（副屏没有桌面，home 和 back 回不到桌面）：launch <应用名> 打开一个"
    VScreenPerception.blankFor(d.displayId)?.let { ms -> attention += "画面已连续 ${(ms / 100) / 10.0} 秒是一整片纯色（黑屏/白屏）" }
    val sb = StringBuilder()
    sb.append("【做了什么】\n")
    done.forEach { sb.append(it).append('\n') }
    sb.append("\n【这段时间】\n")
    since.lines.forEach { sb.append(it).append('\n') }
    sb.append("\n【现在】 ").append(snap.activity ?: "（副屏上没有应用）").append("  副屏 ${d.width}x${d.height}")
    if (pic != null) sb.append("  图 ").append(pic).append(if (since.changed) "（左/上是现在，旁边是这段时间的拼图）" else "")
    sb.append('\n')
    appendElements(sb, els, prev?.takeIf { it.generation == d.generation }, fullList, snap.items == null)
    sb.append("\n【注意】\n")
    if (attention.isEmpty()) sb.append("无\n") else attention.distinct().forEach { sb.append("- ").append(it).append('\n') }
    synchronized(VScreenOffloadHandler.lastReturn) { VScreenOffloadHandler.lastReturn[sid] = System.currentTimeMillis() }
    return NativeOffloadResult(0, sb.toString())
}

private val REPORT_POOL = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "vscreen-report").apply { isDaemon = true } }

/** Same element as last time: same words, centre within 24 px. */
private fun sameEl(a: El, b: El) = a.label == b.label && Math.abs(a.box.centerX() - b.box.centerX()) <= 24 && Math.abs(a.box.centerY() - b.box.centerY()) <= 24

/**
 * v0.5: numbers stay put on the same page. When most of the elements were in the last list, they keep their numbers
 * and new ones get numbers after the highest; on a new page the list is numbered from 1 in reading order.
 */
internal fun numbered(sid: String, d: VScreenDisplay, els: List<El>): List<El> {
    val prev = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[sid] }?.takeIf { it.generation == d.generation }?.list
    if (prev.isNullOrEmpty() || els.isEmpty()) return els
    val match = els.map { e -> prev.firstOrNull { sameEl(it, e) } }
    if (match.count { it != null } < els.size * 0.6) return els
    var next = prev.maxOf { it.n }
    val used = HashSet<Int>()
    return els.mapIndexed { i, e ->
        val m = match[i]?.takeIf { used.add(it.n) }
        e.copy(n = m?.n ?: ++next)
    }
}

/**
 * The 【现在】 list. On the same page as last time only what is new or changed is printed (the rest keeps its numbers,
 * said in one line); [full] (a look without steps) prints everything. At most [Report.MAX_ELEMENTS] lines; all are kept
 * for #N and zoom.
 */
private fun appendElements(sb: StringBuilder, els: List<El>, prev: ElSet?, full: Boolean, noOcr: Boolean) {
    if (els.isEmpty()) { sb.append(if (noOcr) "（没读到文字：OCR 没有结果）\n" else "（画面上没有可读的文字或控件：看图）\n"); return }
    val same = if (full || prev == null) emptyList() else els.filter { e -> prev.list.any { it.n == e.n && sameEl(it, e) } }
    val show = if (same.size >= els.size * 0.6) els - same.toSet() else els
    val keptLine = if (show.size < els.size) "（另有 ${els.size - show.size} 个元素和上次【现在】一样，编号不变；act 不带步骤会列出全部）" else null
    val gone = if (prev != null && show.size < els.size) prev.list.count { p -> els.none { sameEl(it, p) } } else 0
    show.sortedWith(compareBy({ it.box.centerY() }, { it.box.centerX() })).take(Report.MAX_ELEMENTS).forEach { sb.append(it.line()).append('\n') }
    if (show.size > Report.MAX_ELEMENTS) sb.append("（还有 ${show.size - Report.MAX_ELEMENTS} 个没列出：zoom x1,y1,x2,y2 列出那块的全部元素）\n")
    keptLine?.let { sb.append(it).append(if (gone > 0) "；上次的 $gone 个不见了" else "").append('\n') }
}
