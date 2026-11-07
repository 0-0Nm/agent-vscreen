package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [vscreen] Helpers for apps that are mostly pictures (games): no accessibility tree, a moving
 * background, buttons that are drawn rather than laid out.
 *
 *  - UI layer vs background: the set of OCR words is the "UI layer". A background animation
 *    changes pixels everywhere but leaves the words alone; a real page change, a popup or a
 *    button that went away changes the words.
 *  - Target region: a crop around the thing we are about to tap, compared on its own, so a
 *    moving background never makes a still button "stale".
 *  - Patch tracking: a small grayscale template of a confirmed button, searched near its old
 *    place, so a button the vision model found once can be tapped again without asking again.
 *  - Annotation: the look / diagnostics pictures (numbers, boxes, tap points).
 *  - Commit words (spend / irreversible) and money kinds for the confirmation guard.
 */
internal object VScreenGame {

    // ── UI layer ────────────────────────────────────────────────────────────

    private val NUMERIC = Regex("^[\\d\\s:：.,/%+\\-×xX]+$")

    /**
     * v46: a value, not a word: a number with at most a short unit or label around it (12:30, 99+, ★060, .1145ms,
     * Lv10, 3天, 45%). Values change by themselves in any app — clocks, counters, latency, scores, download
     * progress — so a change of values alone is reported, but is not "progress" unless the caller asks for it.
     */
    fun isValue(w: String): Boolean = w.any { it.isDigit() } && w.count { it.isLetter() } <= 3

    /**
     * v48: a change that says nothing about the page: a value, or a mark of one or two letters/symbols (a play/pause
     * glyph read as "ⅱ", an arrow) — never a CJK word.
     */
    fun isMinor(w: String): Boolean = isValue(w) || (w.length <= 2 && w.none { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF })

    /** v48: the pure numbers on the screen (00:21.57, 120, 3/5) with where they are — left out of [uiItems]. */
    fun valueItems(items: List<VScreenOcr.Item>): List<Pair<String, android.graphics.Rect>> = items
        .filter { !VScreenOcr.isJunk(it) }
        .map { VScreenOcr.norm(it.text) to it.box }
        .filter { (w, _) -> w.isNotEmpty() && w.any { it.isDigit() } && NUMERIC.matches(w) }

    /** Normalised words that make up the UI layer (counters and timers left out: they tick on their own). */
    fun uiWords(items: List<VScreenOcr.Item>): Set<String> = uiItems(items).map { it.first }.toSet()

    /** The UI words with where they are (v41: perception tells a new layout from the same boxes read differently). */
    fun uiItems(items: List<VScreenOcr.Item>): List<Pair<String, android.graphics.Rect>> = items
        .filter { !VScreenOcr.isJunk(it) }
        .map { VScreenOcr.norm(it.text) to it.box }
        .filter { (w, _) -> w.length >= 2 && !NUMERIC.matches(w) }

    data class UiDiff(val before: Int, val after: Int, val added: List<String>, val removed: List<String>) {
        /** Share of the words that came or went (0 = same UI layer, 1 = nothing in common). */
        val ratio: Double get() {
            val union = before + added.size
            return if (union == 0) 0.0 else (added.size + removed.size).toDouble() / union
        }
        val same: Boolean get() = added.isEmpty() && removed.isEmpty()
        /** Enough words on both sides to judge the page by them. */
        val meaningful: Boolean get() = maxOf(before, after) >= 3
    }

    fun uiDiff(before: List<VScreenOcr.Item>, after: List<VScreenOcr.Item>): UiDiff {
        val b = uiWords(before); val a = uiWords(after)
        return UiDiff(b.size, a.size, (a - b).toList(), (b - a).toList())
    }

    // ── regions ─────────────────────────────────────────────────────────────

    /** [box] grown by [pad] px (at least 24) on each side, clamped to [w]x[h]. */
    fun grow(box: Rect, pad: Int, w: Int, h: Int): Rect {
        val p = maxOf(pad, 24)
        return Rect((box.left - p).coerceAtLeast(0), (box.top - p).coerceAtLeast(0),
            (box.right + p).coerceAtMost(w), (box.bottom + p).coerceAtMost(h))
    }

    /** Grayscale of [r] in [bmp] scaled by [s]: (pixels, width, height). */
    private fun gray(bmp: Bitmap, r: Rect, s: Float): Triple<IntArray, Int, Int>? {
        if (bmp.isRecycled) return null
        val c = Rect(r.left.coerceAtLeast(0), r.top.coerceAtLeast(0), r.right.coerceAtMost(bmp.width), r.bottom.coerceAtMost(bmp.height))
        if (c.width() < 4 || c.height() < 4) return null
        return runCatching {
            val w = (c.width() * s).roundToInt().coerceAtLeast(2); val h = (c.height() * s).roundToInt().coerceAtLeast(2)
            val sub = Bitmap.createBitmap(bmp, c.left, c.top, c.width(), c.height())
            val sc = Bitmap.createScaledBitmap(sub, w, h, true)
            if (sub !== bmp && sub !== sc) sub.recycle()
            val px = IntArray(w * h); sc.getPixels(px, 0, w, 0, 0, w, h)
            if (sc !== bmp) sc.recycle()
            Triple(IntArray(w * h) { i -> val p = px[i]; (((p shr 16) and 0xff) * 3 + ((p shr 8) and 0xff) * 6 + (p and 0xff)) / 10 }, w, h)
        }.getOrNull()
    }

    /** Share of pixels of region [r] that changed between [a] and [b] (same size frames). -1 = cannot tell. */
    fun regionDiff(a: Bitmap?, b: Bitmap?, r: Rect): Double {
        if (a == null || b == null || a.width != b.width || a.height != b.height) return -1.0
        val s = (64f / maxOf(r.width(), r.height(), 1)).coerceIn(0.05f, 1f)
        val ga = gray(a, r, s) ?: return -1.0
        val gb = gray(b, r, s) ?: return -1.0
        if (ga.second != gb.second || ga.third != gb.third) return -1.0
        var n = 0
        for (i in ga.first.indices) if (abs(ga.first[i] - gb.first[i]) > 28) n++
        return n.toDouble() / ga.first.size
    }

    // ── patch tracking ──────────────────────────────────────────────────────

    /** A remembered look of one target: grayscale template at [scale] of [box]. */
    class Patch(val box: Rect, val px: IntArray, val w: Int, val h: Int, val scale: Float)

    fun patchOf(frame: Bitmap, box: Rect): Patch? {
        if (box.width() < 8 || box.height() < 8) return null
        val s = (40f / maxOf(box.width(), box.height())).coerceIn(0.08f, 1f)
        val g = gray(frame, box, s) ?: return null
        return Patch(Rect(box), g.first, g.second, g.third, s)
    }

    /**
     * Where [p] is now in [frame], searched within [radius] px of its old place.
     * Returns (box, score 0..1, 1 = identical) or null when nothing looks like it (score < 0.8).
     */
    fun track(p: Patch, frame: Bitmap, radius: Int = 160): Pair<Rect, Double>? {
        val area = Rect(p.box.left - radius, p.box.top - radius, p.box.right + radius, p.box.bottom + radius)
        area.intersect(0, 0, frame.width, frame.height)
        val g = gray(frame, area, p.scale) ?: return null
        val (px, aw, ah) = g
        if (aw < p.w || ah < p.h) return null
        var best = Double.MAX_VALUE; var bx = 0; var by = 0
        // Mean of the template, so a uniform brightness shift (day/night, a flash) does not break the match.
        val tm = p.px.average()
        val step = if (aw * ah > 40_000) 2 else 1
        var y = 0
        while (y <= ah - p.h) {
            var x = 0
            while (x <= aw - p.w) {
                var sum = 0L
                for (j in 0 until p.h) { val row = (y + j) * aw + x; for (i in 0 until p.w) sum += px[row + i] }
                val wm = sum.toDouble() / (p.w * p.h)
                var sad = 0.0
                for (j in 0 until p.h) {
                    val row = (y + j) * aw + x; val trow = j * p.w
                    for (i in 0 until p.w) sad += abs((px[row + i] - wm) - (p.px[trow + i] - tm))
                    if (sad >= best) break
                }
                if (sad < best) { best = sad; bx = x; by = y }
                x += step
            }
            y += step
        }
        val score = 1.0 - best / (p.w * p.h * 64.0)
        if (score < 0.8) return null
        val left = area.left + (bx / p.scale).roundToInt(); val top = area.top + (by / p.scale).roundToInt()
        return Rect(left, top, left + p.box.width(), top + p.box.height()) to score.coerceIn(0.0, 1.0)
    }

    /**
     * [p] anywhere in [frame] (or inside [area]): a coarse search at half the template's scale,
     * then a fine search around the best coarse hit. Returns (box, score) or null.
     */
    fun findAnywhere(p: Patch, frame: Bitmap, area: Rect? = null): Pair<Rect, Double>? {
        val full = area ?: Rect(0, 0, frame.width, frame.height)
        // Coarse level: at most ~320 px across the search area, and never finer than half the template.
        val r = minOf(0.5f, 320f / maxOf(full.width(), full.height(), 1) / p.scale).coerceAtLeast(0.05f)
        val s2 = p.scale * r
        val tpl = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(p.px.size) { val g = p.px[it].coerceIn(0, 255); (0xff shl 24) or (g shl 16) or (g shl 8) or g }, 0, p.w, 0, 0, p.w, p.h)
        }
        val tw = (p.w * r).roundToInt().coerceAtLeast(3); val th = (p.h * r).roundToInt().coerceAtLeast(3)
        val small = Bitmap.createScaledBitmap(tpl, tw, th, true)
        tpl.recycle()
        val tp = IntArray(tw * th); small.getPixels(tp, 0, tw, 0, 0, tw, th); small.recycle()
        val t = IntArray(tw * th) { tp[it] and 0xff }
        val g = gray(frame, full, s2) ?: return null
        val (px, aw, ah) = g
        if (aw < tw || ah < th) return null
        val tm = t.average()
        var best = Double.MAX_VALUE; var bx = 0; var by = 0
        for (y in 0..(ah - th)) for (x in 0..(aw - tw)) {
            var sum = 0L
            for (j in 0 until th) { val row = (y + j) * aw + x; for (i in 0 until tw) sum += px[row + i] }
            val wm = sum.toDouble() / (tw * th)
            var sad = 0.0
            for (j in 0 until th) {
                val row = (y + j) * aw + x; val trow = j * tw
                for (i in 0 until tw) sad += abs((px[row + i] - wm) - (t[trow + i] - tm))
                if (sad >= best) break
            }
            if (sad < best) { best = sad; bx = x; by = y }
        }
        if (1.0 - best / (tw * th * 64.0) < 0.7) return null
        val left = full.left + (bx / s2).roundToInt(); val top = full.top + (by / s2).roundToInt()
        val guess = Patch(Rect(left, top, left + p.box.width(), top + p.box.height()), p.px, p.w, p.h, p.scale)
        return track(guess, frame, maxOf(24, (4 / s2).roundToInt()))
    }

    // ── orientation ─────────────────────────────────────────────────────────

    fun turned(frame: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return frame
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, false)
    }

    /** How readable [frame] is the way it stands: characters in OCR words of 2+ letters. */
    fun uprightScore(frame: Bitmap): Int {
        val r = VScreenOcr.recognize(-7, frame, -System.nanoTime(), accurate = false)
        if (r.error != null) return 0
        return r.items.filter { !VScreenOcr.isJunk(it) }.sumOf { VScreenOcr.norm(it.text).length.takeIf { n -> n >= 2 } ?: 0 }
    }

    // ── pictures ────────────────────────────────────────────────────────────

    data class Label(val tag: String, val box: Rect?, val point: Pair<Int, Int>?, val dim: Boolean = false)

    /** Copy of [frame] (scaled to at most [maxSide]) with numbered boxes and tap points. */
    fun annotate(frame: Bitmap, labels: List<Label>, maxSide: Int = (maxOf(frame.width, frame.height) * 0.5).roundToInt()): Bitmap {
        val s = minOf(1f, maxSide.toFloat() / maxOf(frame.width, frame.height))
        val bw = (frame.width * s).roundToInt().coerceAtLeast(1); val bh = (frame.height * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createScaledBitmap(frame, bw, bh, true).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        val c = Canvas(bmp)
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
        val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val fg = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true; isFakeBoldText = true }
        for (l in labels) {
            val col = if (l.dim) Color.rgb(150, 150, 150) else Color.rgb(0, 200, 120)
            stroke.color = col; fill.color = Color.argb(225, Color.red(col), Color.green(col), Color.blue(col))
            l.box?.let { b -> c.drawRect(RectF(b.left * s, b.top * s, b.right * s, b.bottom * s), stroke) }
            val p = l.point ?: l.box?.let { it.centerX() to it.centerY() } ?: continue
            val x = p.first * s; val y = p.second * s
            c.drawCircle(x, y, 7f, fill)
            stroke.color = Color.WHITE; c.drawCircle(x, y, 9f, stroke)
            val tw = fg.measureText(l.tag) + 10f
            val tx = (x + 10f).coerceAtMost(bw - tw); val ty = (y - 30f).coerceAtLeast(0f)
            c.drawRect(tx, ty, tx + tw, ty + 28f, fill)
            c.drawText(l.tag, tx + 5f, ty + 22f, fg)
        }
        return bmp
    }

    /** Input diagnostics: the planned point (frame space) as a cross, with the input-space numbers written beside it. */
    fun diagnostics(frame: Bitmap, fx: Int, fy: Int, caption: List<String>): Bitmap {
        val s = minOf(1f, 1280f / maxOf(frame.width, frame.height))
        val bw = (frame.width * s).roundToInt().coerceAtLeast(1); val bh = (frame.height * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createScaledBitmap(frame, bw, bh, true).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        val c = Canvas(bmp)
        val red = Paint().apply { color = Color.rgb(255, 30, 60); strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
        val x = fx * s; val y = fy * s
        c.drawLine(x - 30f, y, x + 30f, y, red); c.drawLine(x, y - 30f, x, y + 30f, red); c.drawCircle(x, y, 16f, red)
        val bg = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        val fg = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
        val lh = 28f
        c.drawRect(0f, 0f, bw.toFloat(), lh * caption.size + 10f, bg)
        caption.forEachIndexed { i, t -> c.drawText(t, 8f, lh * (i + 1), fg) }
        return bmp
    }

    // ── commit actions: anything that spends, submits or cannot be undone ────────

    /** Button words that spend money or credit (any shop, app, site or game). */
    val SPEND = Regex("购买|买入|购入|兑换|支付|付款|充值|下单|提交订单|去结算|结算|抢购|购\\s*买|^买$|订阅|续费|开通会员|立即开通|购票|打赏|赞赏|捐赠|" +
        "buy|purchase|pay\\b|checkout|subscribe|place order|order now|donate", RegexOption.IGNORE_CASE)
    /** Button words for steps that cannot be taken back. */
    val IRREVERSIBLE = Regex("永久删除|彻底删除|删除|清空|注销|解绑|转账|卸载|恢复出厂|取消订单|申请退款|撤销授权|" +
        "delete|erase|wipe|transfer|unsubscribe|factory reset|remove account", RegexOption.IGNORE_CASE)
    /** "spend" / "irreversible" for a button label, null for an ordinary one. */
    fun commitKind(label: String): String? = when {
        SPEND.containsMatchIn(label) -> "spend"
        IRREVERSIBLE.containsMatchIn(label) -> "irreversible"
        else -> null
    }
    /** Words that mean a committed step went through. */
    val COMMIT_DONE = Regex("成功|已完成|已提交|已购买|已下单|已支付|已兑换|已领取|已拥有|已获得|获得|已删除|已清空|已开通|已订阅|已转账|" +
        "success|completed|done|purchased|obtained|submitted|deleted|subscribed", RegexOption.IGNORE_CASE)
    /** Real money on screen (a currency sign or unit next to a number). */
    val REAL_MONEY = Regex("[¥￥€£]\\s*\\d|\\$\\s*\\d|\\d+(\\.\\d+)?\\s*(元|块钱)(?!宝)|RMB|人民币|CNY|USD|实付|应付|充值", RegexOption.IGNORE_CASE)
    /** In-app credit: points, coins, balances, vouchers, tokens, gems. */
    val CREDIT = Regex("积分|点数|点券|金币|代币|余额|券|豆|币|钻|credits?|points?|coins?|tokens?|gems?|diamonds?", RegexOption.IGNORE_CASE)

    /** "real_money" / "credit" / "unknown" for the price text around a commit. */
    fun moneyKind(text: String): String = when {
        REAL_MONEY.containsMatchIn(text) -> "real_money"
        CREDIT.containsMatchIn(text) -> "credit"
        else -> "unknown"
    }
}
