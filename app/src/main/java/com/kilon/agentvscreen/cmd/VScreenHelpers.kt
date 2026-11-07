package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VPixels
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenGame
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPerception
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenSpots
import kotlin.math.roundToInt

// [vscreen] helpers: spots (colour blobs), zoom (a closer
// picture of an area), collect (scroll a list and gather its lines). They answer questions; they do not act on
// the app beyond collect's scrolling. Their numbers (#N, S1…) are screen points the act steps take.

private fun VScreenOffloadHandler.freshFrameOf(d: VScreenDisplay): Bitmap {
    val t0 = SystemClock.elapsedRealtime()
    d.requestFreshFrame()
    d.awaitFrameAfter(t0, 600)
    return d.latestFrame() ?: throw ActionError("NO_FRAME", "副屏还没有画面")
}

/** `spots [x1,y1,x2,y2] [--color #RRGGBB]`: colour blobs found on the device (no model), numbered S1…, with centres. */
internal fun VScreenOffloadHandler.spotsCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
    val region = args.positional.firstOrNull()?.let { parseRect(it, "区域") }
    val color = args.get("color")?.let { VScreenSpots.parseColor(it) ?: throw ActionError("INVALID_ARGS", "--color 写成 #RRGGBB") }
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = "找色块") { d ->
        val snap = snapshot(d)
        val frame = snap.frame ?: throw ActionError("NO_FRAME", "副屏还没有画面")
        // v0.5: blobs that are letters (inside an OCR word box) are not targets: left out, and counted.
        val words = snap.items.orEmpty().map { it.box }
        fun isText(b: Rect): Boolean = words.any { w -> val i = Rect(b); i.intersect(w) && i.width().toLong() * i.height() * 2 >= b.width().toLong() * b.height() }
        val found = VScreenSpots.find(frame, region = region, color = color, limit = 120)
        val list = found.filter { !isText(it.box) }.take(60)
        val textual = found.size - found.count { !isText(it.box) }
        if (list.isEmpty()) return@agentAction text("没找到色块" + (region?.let { "（区域 ${Report.box(it)}）" } ?: "") + if (textual > 0) "（有 $textual 个是文字，已略过）" else "")
        val sb = StringBuilder("色块（本地按颜色找的，不是识别；tap 用括号里的坐标）" + (if (textual > 0) "，另有 $textual 个落在文字上的已略过" else "") + "：\n")
        list.forEachIndexed { i, sp ->
            sb.append("S${i + 1} ${sp.shape}${if (sp.hollow) "(空心)" else ""} ${VScreenSpots.hex(sp.color)} (${sp.cx},${sp.cy}) ${sp.box.width()}x${sp.box.height()}\n")
        }
        runCatching { VScreenGame.annotate(frame, list.mapIndexed { i, sp -> VScreenGame.Label("S${i + 1}", sp.box, sp.cx to sp.cy) }) }.getOrNull()?.let { b ->
            savePic(s, b, "spots")?.let { sb.append("标注图 ").append(it).append('\n') }; b.recycle()
        }
        text(sb.toString())
    }
}

/** `zoom x1,y1,x2,y2 [--scale N]`: that area enlarged, with screen coordinates on its edges, plus the elements in it. */
internal fun VScreenOffloadHandler.zoomCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
    val r0 = args.positional.firstOrNull()?.let { parseRect(it, "区域") } ?: throw ActionError("INVALID_ARGS", "zoom x1,y1,x2,y2 [--scale 2]")
    return BackgroundOperation.agentAction(s, null, isRead = true, describe = "放大看") { d ->
        val frame = freshFrameOf(d)
        val r = Rect(r0.left.coerceIn(0, frame.width - 1), r0.top.coerceIn(0, frame.height - 1), r0.right.coerceIn(1, frame.width), r0.bottom.coerceIn(1, frame.height))
        if (r.width() < 4 || r.height() < 4) throw ActionError("INVALID_ARGS", "区域太小或在副屏外（副屏 ${d.width}x${d.height}）")
        val maxK = 1280.0 / maxOf(r.width(), r.height())
        val k = (args.getDouble("scale") ?: minOf(4.0, maxK)).coerceIn(0.5, maxOf(1.0, maxK))
        val w = (r.width() * k).roundToInt(); val h = (r.height() * k).roundToInt()
        val m = 34
        val out = Bitmap.createBitmap(w + m, h + m, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE)
        c.drawBitmap(frame, r, Rect(m, m, m + w, m + h), Paint(Paint.FILTER_BITMAP_FLAG))
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.rgb(30, 30, 160) }
        val step = listOf(10, 20, 50, 100, 200, 500).first { it * k >= 60 || it == 500 }
        var v = (r.left / step + 1) * step
        while (v < r.right) { val x = m + ((v - r.left) * k).toFloat(); c.drawLine(x, (m - 6).toFloat(), x, m.toFloat(), tick); c.drawText("$v", x + 2, (m - 8).toFloat(), tick); v += step }
        v = (r.top / step + 1) * step
        while (v < r.bottom) { val y = m + ((v - r.top) * k).toFloat(); c.drawLine((m - 6).toFloat(), y, m.toFloat(), y, tick); c.drawText("$v", 1f, y - 2, tick); v += step }
        val path = savePic(s, out, "zoom"); out.recycle()
        val inside = synchronized(VScreenOffloadHandler.lastElements) { VScreenOffloadHandler.lastElements[s] }
            ?.takeIf { it.generation == d.generation }?.list?.filter { Rect.intersects(it.box, r) }.orEmpty()
        val sb = StringBuilder("区域 ${Report.box(r)} 放大 ${"%.1f".format(k)} 倍" + (path?.let { "：$it" } ?: "（图没存下来）") + "（边上是副屏坐标）\n")
        if (inside.isNotEmpty()) { sb.append("上次列表里在这块的元素：\n"); inside.forEach { sb.append(it.line()).append('\n') } }
        text(sb.toString())
    }
}

/** Char bigrams of a normalised text (for "is this the same entry read twice"). */
private fun bigrams(t: String): Set<String> = if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()

/** Same entry: one text inside the other, or most of their character pairs shared (OCR splits / spaces differ). */
internal fun sameEntry(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.contains(b) || b.contains(a)) return true
    val x = bigrams(a); val y = bigrams(b)
    return x.intersect(y).size.toDouble() / x.union(y).size >= 0.6
}

/**
 * The entries of one screen: OCR rows grouped into blocks — rows that follow each other with a gap smaller than a
 * line, and overlap sideways, are one entry (a title wrapped over two lines is one entry, not two).
 */
internal fun entriesOf(items: List<VScreenOcr.Item>): List<String> {
    val rows = VScreenOcr.rows(items.filter { !VScreenOcr.isJunk(it) && !isJunkLabel(it.text.trim()) })
        .map { r -> Triple(r.text.trim(), Rect(r.items.first().box).apply { r.items.forEach { union(it.box) } }, r) }
        .filter { it.first.isNotEmpty() }
    if (rows.isEmpty()) return emptyList()
    val h = rows.map { it.second.height() }.sorted()[rows.size / 2].coerceAtLeast(8)
    val out = ArrayList<StringBuilder>()
    var last: Rect? = null
    var rowsIn = 0
    for ((t, box, _) in rows.sortedBy { it.second.top }) {
        val l = last
        // 1.1: a wrapped title is joined, a long paragraph is not swallowed whole: at most 3 rows / 90 characters per
        // entry, then a new entry starts (an answer's body becomes several readable entries, not one 3000-char block).
        val joins = l != null && box.top - l.bottom <= h * 0.6 && box.left < l.right && l.left < box.right &&
            rowsIn < 3 && out.last().length + t.length <= 90
        if (joins) { out.last().append(if (t.first().isLetterOrDigit() && out.last().last().isLetterOrDigit() && t.first().code < 128) " " else "").append(t); l!!.union(box); rowsIn++ }
        else { out += StringBuilder(t); last = Rect(box); rowsIn = 1 }
    }
    return out.map { it.toString() }
}

/** `collect [--pages N] [--until 文字]`: scroll down page by page and gather the entries (de-duplicated). */
internal fun VScreenOffloadHandler.collectCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
    val pages = bounded("pages", args.getInt("pages") ?: 8, 1, 40)
    val until = args.get("until")?.let { VScreenOcr.norm(it) }
    return BackgroundOperation.agentAction(s, null, isRead = false, describe = "滚动收集") { d ->
        VScreenPerception.ensure(d)
        val entries = ArrayList<String>()
        val keys = ArrayList<String>()
        var why = "翻完 $pages 页"
        var scanned = 0
        for (p in 0 until pages) {
            val items = snapshot(d).items ?: emptyList()
            scanned++
            var hit = false
            for (e in entriesOf(items)) {
                val n = VScreenOcr.norm(e)
                if (n.length < 2) continue
                val dup = keys.indexOfFirst { sameEntry(it, n) }
                if (dup >= 0) { if (n.length > keys[dup].length) { keys[dup] = n; entries[dup] = e }; continue }
                keys += n; entries += e
                if (until != null && n.contains(until)) hit = true
            }
            if (hit) { why = "看到了“${args.get("until")}”"; break }
            if (p == pages - 1) break
            val before = d.latestFrame()?.let { VScreenPerception.small(it) }
            val x = d.width / 2
            val o = VScreenShell.swipe(d.displayId, x, (d.height * 0.78).toInt(), x, (d.height * 0.28).toInt(), 450)
            if (!o.ok) throw ActionError(o.code, o.message)
            val still = Probe(this, d, listOf(Cond("stable", "still")))
            try { waitOn(d, still, 2_500L, null) } finally { still.close() }
            val after = d.latestFrame()?.let { VScreenPerception.small(it) }
            val moved = if (before != null && after != null && before.width == after.width && before.height == after.height)
                VPixels.regionDiff(before, after, Rect(0, 0, before.width, before.height)) else 1.0
            before?.recycle(); after?.recycle()
            if (moved in 0.0..0.003) { why = "再滑画面不动了（到底了，或这块不能滚）"; break }
        }
        val sb = StringBuilder("收集了 ${entries.size} 条，看了 $scanned 屏，停下原因：$why（OCR 读的，可能有错字；上下相连的行合成一条，同一条在几屏里重复出现只记一次，吸顶栏也只记一次）\n")
        entries.forEachIndexed { i, l -> sb.append(i + 1).append(". ").append(if (l.length > 160) l.take(150) + "…（这条共 ${l.length} 字）" else l).append('\n') }
        text(sb.toString())
    }
}
