package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.kilon.agentvscreen.host.AppLogger
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * [vscreen] On-device text layer for the background screen.
 *
 * ML Kit's bundled Chinese recognizer runs inside the app, offline, on the
 * full-resolution frame, so every box is already in virtual-screen pixels.
 *
 * Segmentation: ML Kit's own line grouping is not stable (the same tab bar
 * came back as one line, then as two). We therefore rebuild "items" from the
 * character boxes (Text.Symbol): characters of a line are split into
 * separate items wherever the visual gap is wider than 0.6 x the character
 * height. A row of tabs becomes one item per tab, every time, and each item
 * keeps its character boxes so a substring can be tapped at its own centre.
 *
 * Results are cached per display and frame version (or an unchanged frame).
 */
internal object VScreenOcr {
    private const val TAG = "VScreenOcr"
    const val ENGINE = "mlkit-zh (on-device)"

    data class Glyph(val ch: String, val box: Rect)

    data class Item(val text: String, val box: Rect, val glyphs: List<Glyph>) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
    }

    /** A match of a query: the item and the box of the matched characters only. */
    data class Match(val item: Item, val box: Rect, val exact: Boolean, val fuzzy: Boolean = false) {
        val cx: Int get() = box.centerX()
        val cy: Int get() = box.centerY()
    }

    /** Items whose vertical centres line up: one visual row of the screen. */
    data class Row(val items: List<Item>) {
        val top: Int = items.minOf { it.box.top }
        val bottom: Int = items.maxOf { it.box.bottom }
        val cy: Int get() = (top + bottom) / 2
        val height: Int get() = bottom - top
        val text: String get() = items.joinToString(" ") { it.text }
    }

    data class Result(
        val items: List<Item>,
        val displayId: Int,
        val frameVersion: Long,
        val ms: Long,
        val error: String?,
        val sig: ScreenSig? = null,
        val engine: String = ENGINE,
        /** Set when the accurate engine was asked for but failed and ML Kit answered instead. */
        val fallback: String? = null,
    )

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    @Volatile private var cache: Result? = null
    @Volatile private var accurateCache: Result? = null

    private fun reusable(c: Result?, displayId: Int, frame: Bitmap, version: Long): Boolean {
        if (c == null || c.displayId != displayId || c.error != null) return false
        if (c.frameVersion == version) return true
        // A new frame number with the same picture (cursor blink, redraw) reuses the result.
        val sig = ScreenSig.of(frame) ?: return false
        val old = c.sig ?: return false
        val ch = sig.compare(old)
        return ch.kind == ScreenSig.Kind.NONE && ch.ratio < 0.004
    }

    /** Average time of an accurate reading on this phone (0 = none yet). */
    @Volatile private var accurateMs = 0L


    /** Whether [recognize] with accurate = true will try PP-OCRv6 now. */
    fun accurateEnabled(): Boolean {
        val ctx = BackgroundOperation.appContextOrNull() ?: return false
        return BackgroundOperation.state.value.accurateOcr && VScreenPaddleOcr.available(ctx)
    }

    /** Engines for status: what read / tap --text use, and what the fast paths use. */
    fun engineInfo(): String = if (accurateEnabled()) "${VScreenPaddleOcr.ENGINE}；滚动等快速路径用 $ENGINE；后台感知（state 的 now.words）也用精确引擎" +
            (if (accurateMs > 0L) "（约 ${accurateMs}ms/次，慢时降低频率）" else "")
        else ENGINE + (VScreenPaddleOcr.loadError()?.let { "（PP-OCRv6 加载失败：$it）" } ?: "")

    /**
     * OCR of [frame] (version [version]) on display [displayId]. Never throws. Not on the main thread.
     * [accurate]: read / tap --text / vision marks ask for PP-OCRv6 (slower, fewer misreads) when it
     * is enabled; scroll measurement, waits and other loops keep ML Kit (fast). PP-OCRv6 failures
     * fall back to ML Kit and say so in [Result.fallback].
     */
    fun recognize(displayId: Int, frame: Bitmap, version: Long, accurate: Boolean = false): Result {
        var fallback: String? = null
        if (accurate && accurateEnabled()) {
            val ac = accurateCache
            if (reusable(ac, displayId, frame, version)) return ac!!
            val t0 = SystemClock.elapsedRealtime()
            try {
                val items = VScreenPaddleOcr.recognize(BackgroundOperation.appContextOrNull()!!, frame)
                val took = SystemClock.elapsedRealtime() - t0
                accurateMs = if (accurateMs == 0L) took else (accurateMs * 3 + took) / 4
                return Result(items, displayId, version, took, null, ScreenSig.of(frame),
                    engine = VScreenPaddleOcr.ENGINE).also { accurateCache = it }
            } catch (t: Throwable) {
                fallback = "PP-OCRv6 失败（${t.javaClass.simpleName}: ${t.message}），已改用 ML Kit"
                AppLogger.warning(TAG, fallback)
            }
        }
        val r = recognizeFast(displayId, frame, version)
        return if (fallback != null) r.copy(fallback = fallback) else r
    }

    /**
     * The words inside [r]: [full] (the screen reading, see VScreenText) whose centre is in [r], plus a padded,
     * enlarged reading of the crop of [frame] for what the whole reading missed (small HUD text).
     */
    fun readRegion(full: List<Item>, frame: Bitmap, r: Rect): List<Item> {
        val inside = full.filter { r.contains(it.cx, it.cy) }
        // v48: the crop is grown to the whole lines it cuts (a cut line reads as fragments: "1.吸平", "廷"), and what
        // it reads counts only where the full reading has nothing.
        val touched = full.filter { Rect.intersects(it.box, r) }
        val grown = Rect(r).also { g -> touched.forEach { g.union(it.box) } }
        val extra = runCatching { readCrop(frame, grown) }.onFailure { AppLogger.warning(TAG, "readCrop: ${it.message}") }.getOrDefault(emptyList())
            .filter { e -> r.contains(e.cx, e.cy) && touched.none { Rect.intersects(it.box, e.box) } }
        return (inside + extra).sortedWith(compareBy<Item>({ it.box.top / 8 }, { it.box.left }))
    }

    /** [readRegion] that also remembers where its short words are ([displayId] < 0: do not). */
    fun readRegion(displayId: Int, full: List<Item>, frame: Bitmap, r: Rect): List<Item> =
        readRegion(full, frame, r).also { if (displayId >= 0) remember(displayId, it) }

    /** OCR of [r0] alone: enlarged (small regions up to 3×) and set on a plain margin of its own edge colour. */
    fun readCrop(frame: Bitmap, r0: Rect): List<Item> {
        val r = Rect(r0)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() < 4 || r.height() < 4) return emptyList()
        val zoom = (160.0 / minOf(r.width(), r.height())).coerceIn(1.0, 3.0)
        val zw = (r.width() * zoom).toInt().coerceIn(4, 3000); val zh = (r.height() * zoom).toInt().coerceIn(4, 3000)
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val big = Bitmap.createScaledBitmap(crop, zw, zh, true)
        if (crop !== big && crop !== frame) crop.recycle()
        val pad = maxOf(24, zh / 3)
        val padded = Bitmap.createBitmap(zw + 2 * pad, zh + 2 * pad, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).apply { drawColor(edgeColor(big)); drawBitmap(big, pad.toFloat(), pad.toFloat(), null) }
        if (big !== frame) big.recycle()
        try {
            val ctx = BackgroundOperation.appContextOrNull()
            val items = if (ctx != null && accurateEnabled()) VScreenPaddleOcr.recognize(ctx, padded)
                else recognizeFast(-4, padded, -System.nanoTime()).items
            fun back(b: Rect) = Rect(r.left + ((b.left - pad) / zoom).toInt(), r.top + ((b.top - pad) / zoom).toInt(),
                r.left + ((b.right - pad) / zoom).toInt(), r.top + ((b.bottom - pad) / zoom).toInt())
            return items.map { Item(it.text, back(it.box), it.glyphs.map { g -> Glyph(g.ch, back(g.box)) }) }
                .filter { Rect.intersects(it.box, r) }
        } finally { padded.recycle() }
    }

    /** The most common colour along [b]'s border (the margin a crop is set on). */
    private fun edgeColor(b: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        fun add(x: Int, y: Int) { val c = b.getPixel(x, y) and 0xF0F0F0; counts[c] = (counts[c] ?: 0) + 1 }
        val stepX = maxOf(1, b.width / 40); val stepY = maxOf(1, b.height / 40)
        for (x in 0 until b.width step stepX) { add(x, 0); add(x, b.height - 1) }
        for (y in 0 until b.height step stepY) { add(0, y); add(b.width - 1, y) }
        return (counts.maxByOrNull { it.value }?.key ?: 0xFFFFFF) or 0xFF000000.toInt()
    }

    /** The first number in [items] read in order ("1,234", "-5", "3.5"), or null. */
    fun firstNumber(items: List<Item>): Double? {
        val txt = items.sortedWith(compareBy<Item>({ it.box.top / 8 }, { it.box.left })).joinToString(" ") { it.text }
        val m = NUMBER.find(txt) ?: return null
        // v48: a time (mm:ss, h:mm:ss, with hundredths) is one value, in seconds — not its leading "00".
        if (m.groupValues[2].isNotEmpty()) {
            val parts = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).filter { it.isNotEmpty() }.map { it.toDouble() }
            val whole = parts.fold(0.0) { acc, p -> acc * 60 + p }
            return whole + (m.groupValues[4].takeIf { it.isNotEmpty() }?.let { "0.$it".toDouble() } ?: 0.0)
        }
        return m.value.replace(",", "").replace("，", "").toDoubleOrNull()
    }
    private val NUMBER = Regex("(\\d{1,3})[:：](\\d{2})(?:[:：](\\d{2}))?(?:[.](\\d{1,3}))?|[-+]?\\d[\\d,，]*(?:\\.\\d+)?")

    private fun recognizeFast(displayId: Int, frame: Bitmap, version: Long): Result {
        val c = cache
        if (reusable(c, displayId, frame, version)) return c!!
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)), 10, TimeUnit.SECONDS)
            val items = ArrayList<Item>()
            for (block in text.textBlocks) for (line in block.lines) items += segment(line)
            items.sortWith(compareBy<Item>({ it.box.top / 8 }, { it.box.left }))
            Result(items, displayId, version, SystemClock.elapsedRealtime() - t0, null, ScreenSig.of(frame)).also { cache = it }
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            AppLogger.warning(TAG, "OCR failed: ${cause.javaClass.simpleName}: ${cause.message}")
            Result(emptyList(), displayId, version, SystemClock.elapsedRealtime() - t0,
                "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /** Current OCR of [d]'s latest frame, or null when there is no frame yet. */
    fun current(d: VScreenDisplay): Result? {
        val (frame, version) = d.latestWithVersion() ?: return null
        return recognize(d.displayId, frame, version)
    }

    // ── segmentation ──────────────────────────────────────────────────────

    private class G(val ch: String, val box: Rect, val element: Int)

    /** Split one ML Kit line into items at wide visual gaps, using character boxes. */
    private fun segment(line: Text.Line): List<Item> {
        val glyphs = ArrayList<G>()
        line.elements.forEachIndexed { ei, el ->
            val eb = el.boundingBox ?: return@forEachIndexed
            val syms = runCatching { el.symbols }.getOrNull().orEmpty().filter { it.boundingBox != null && it.text.isNotBlank() }
            if (syms.isNotEmpty()) {
                syms.forEach { glyphs += G(it.text, Rect(it.boundingBox!!), ei) }
            } else {
                // No symbol boxes: share the element box evenly between its characters.
                val chars = el.text.filterNot { it.isWhitespace() }.map { it.toString() }
                if (chars.isEmpty()) return@forEachIndexed
                val w = eb.width().toFloat() / chars.size
                chars.forEachIndexed { i, ch ->
                    glyphs += G(ch, Rect((eb.left + w * i).toInt(), eb.top, (eb.left + w * (i + 1)).toInt(), eb.bottom), ei)
                }
            }
        }
        if (glyphs.isEmpty()) {
            val b = line.boundingBox ?: return emptyList()
            return listOf(Item(line.text.trim(), Rect(b), emptyList()))
        }
        glyphs.sortBy { it.box.left }
        val h = glyphs.map { it.box.height() }.sorted()[glyphs.size / 2].coerceAtLeast(1)
        val out = ArrayList<Item>()
        var cur = ArrayList<G>()
        fun flush() {
            if (cur.isEmpty()) return
            val sb = StringBuilder()
            for (i in cur.indices) {
                val g = cur[i]
                if (i > 0) {
                    val p = cur[i - 1]
                    // Separate Latin words that ML Kit reported as different elements.
                    if (g.element != p.element && p.ch.last().isLetterOrDigit() && p.ch.last().code < 128 &&
                        g.ch.first().isLetterOrDigit() && g.ch.first().code < 128) sb.append(' ')
                }
                sb.append(g.ch)
            }
            val box = Rect(cur.first().box)
            cur.forEach { box.union(it.box) }
            out += Item(sb.toString(), box, cur.map { Glyph(it.ch, it.box) })
            cur = ArrayList()
        }
        for (g in glyphs) {
            val p = cur.lastOrNull()
            if (p != null) {
                val gap = g.box.left - p.box.right
                val misaligned = abs(g.box.centerY() - p.box.centerY()) > h
                if (gap > h * 0.6f || misaligned) flush()
            }
            cur += g
        }
        flush()
        return out
    }

    // ── queries ───────────────────────────────────────────────────────────

    fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    /**
     * v45: where each short text was last read on a display (any reading: the screen's, a region's). A short HUD
     * word ("II", "周二") is sometimes lost by the whole-screen pass; knowing where it was lets a check re-read just
     * that spot enlarged instead of waiting for the next whole-screen reading.
     */
    private val seenBoxes = HashMap<Int, LinkedHashMap<String, Rect>>()

    fun remember(displayId: Int, items: List<Item>) = synchronized(seenBoxes) {
        val m = seenBoxes.getOrPut(displayId) {
            object : LinkedHashMap<String, Rect>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Rect>?) = size > 300 }
        }
        for (it in items) { val k = norm(it.text); if (k.isNotEmpty() && k.length <= 8) m[k] = Rect(it.box) }
    }

    fun lastBox(displayId: Int, q: String): Rect? = synchronized(seenBoxes) {
        val k = norm(q); val m = seenBoxes[displayId] ?: return null
        m[k] ?: m.entries.firstOrNull { k.isNotEmpty() && it.key.contains(k) }?.value
    }

    fun forget(displayId: Int) = synchronized(seenBoxes) { seenBoxes.remove(displayId) }

    /** Box of the characters of [item] that spell [query] (normalised), or null. */
    private fun subBox(item: Item, query: String): Rect? {
        if (item.glyphs.isEmpty()) return null
        val chars = StringBuilder()
        val owner = ArrayList<Int>()
        item.glyphs.forEachIndexed { gi, g ->
            for (c in g.ch) if (!c.isWhitespace()) { chars.append(c.lowercaseChar()); owner += gi }
        }
        val at = chars.indexOf(query)
        if (at < 0) return null
        val first = owner[at]
        val last = owner[at + query.length - 1]
        val box = Rect(item.glyphs[first].box)
        for (i in first..last) box.union(item.glyphs[i].box)
        return box
    }

    /**
     * Places where [query] appears: whole items that equal it first; then
     * items containing it, each with the box of just the matched characters.
     */
    fun find(items: List<Item>, query: String, exactOnly: Boolean = false, fuzzy: Boolean = false): List<Match> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val exact = items.filter { norm(it.text) == q }.map { Match(it, it.box, true) }
        if (exact.isNotEmpty() || exactOnly) return exact
        val contains = items.filter { norm(it.text).contains(q) }.map { Match(it, subBox(it, q) ?: it.box, false) }
        if (contains.isNotEmpty()) return contains
        // v42: a line OCR cut into two or three boxes ("完成赛季作业第二章的3个" + "课题(1/3)").
        val joined = joinedFind(items, q)
        if (joined.isNotEmpty() || !fuzzy || q.length < 2) return joined
        return fuzzyFind(items, q)
    }

    /**
     * v42: neighbours on one row joined (up to 3 boxes, gaps under two text heights) and searched as one line.
     * The match is the joined item (its full text) with the union box.
     */
    fun joinedFind(items: List<Item>, q: String): List<Match> {
        if (q.length < 3) return emptyList()
        val sorted = items.filter { !isJunk(it) }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val out = ArrayList<Match>()
        for (a in sorted) {
            var cur = a
            var box = Rect(a.box)
            var text = a.text
            val glyphs = ArrayList(a.glyphs)
            for (step in 0 until 2) {
                val h = maxOf(8, box.height())
                val next = sorted.filter { it !== cur && it.box.left >= box.right - h / 2 && it.box.left - box.right <= h * 2 &&
                    kotlin.math.abs(it.cy - box.centerY()) <= h / 2 }.minByOrNull { it.box.left } ?: break
                text += next.text; box.union(next.box); glyphs.addAll(next.glyphs); cur = next
                val t = norm(text)
                if (t.contains(q)) {
                    if (out.none { Rect.intersects(it.box, box) }) out += Match(Item(text, Rect(box), glyphs), Rect(box), t == q)
                    break
                }
            }
        }
        return out
    }

    /**
     * Tolerate OCR misreads (地图 → 地, 模式 → 摸式). Short labels (≤3 chars) only match a
     * whole item one character off; longer ones match a substring within 1 (≤6 chars) or 2 edits.
     */
    private fun fuzzyFind(items: List<Item>, q: String): List<Match> {
        val out = ArrayList<Match>()
        for (it in items) {
            if (isJunk(it)) continue
            val t = norm(it.text)
            if (t.isEmpty()) continue
            if (q.length <= 3) {
                if (abs(t.length - q.length) <= 1 && editDistance(t, q) <= 1) out += Match(it, it.box, false, fuzzy = true)
            } else {
                val k = if (q.length <= 6) 1 else 2
                if (t.length < q.length - k) continue
                val (dist, end) = substringDistance(q, t)
                if (dist <= k) {
                    val part = t.substring((end - q.length).coerceAtLeast(0), end)
                    out += Match(it, subBox(it, part) ?: it.box, false, fuzzy = true)
                }
            }
        }
        return out
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** Smallest edit distance of [q] to any substring of [t], and where that substring ends. */
    private fun substringDistance(q: String, t: String): Pair<Int, Int> {
        var prev = IntArray(t.length + 1) // empty query matches anywhere at cost 0
        for (i in 1..q.length) {
            val cur = IntArray(t.length + 1)
            cur[0] = i
            for (j in 1..t.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (q[i - 1] == t[j - 1]) 0 else 1)
            }
            prev = cur
        }
        var best = Int.MAX_VALUE; var end = 0
        for (j in 0..t.length) if (prev[j] < best) { best = prev[j]; end = j }
        return best to end
    }

    /** OCR noise: mostly symbols (video thumbnails, textures), not readable text. */
    fun isJunk(item: Item): Boolean {
        val t = norm(item.text)
        if (t.isEmpty()) return true
        val good = t.count { it.isLetterOrDigit() }
        if (good * 2 < t.length) return true
        // A lone tiny Latin letter / symbol is usually texture noise; lone CJK chars and digits are kept.
        return t.length == 1 && !t[0].isDigit() && t[0].code < 0x2E80 && item.box.height() < 18
    }

    /** The item under (x, y): containing box (12 px slack) first, else the nearest centre within 80 px. */
    fun hitTest(items: List<Item>, x: Int, y: Int): Pair<Item, Int>? {
        val slack = 12
        items.filter { x >= it.box.left - slack && x <= it.box.right + slack && y >= it.box.top - slack && y <= it.box.bottom + slack }
            .minByOrNull { it.box.width() * it.box.height() }
            ?.let { return it to 0 }
        return items.map { it to hypot((it.cx - x).toDouble(), (it.cy - y).toDouble()).toInt() }
            .filter { it.second <= 80 }
            .minByOrNull { it.second }
    }

    /** Group items into visual rows (vertical centres within half a row height). */
    fun rows(items: List<Item>): List<Row> {
        val sorted = items.sortedBy { it.cy }
        val rows = ArrayList<MutableList<Item>>()
        for (it in sorted) {
            val r = rows.lastOrNull()
            if (r != null) {
                val top = r.minOf { x -> x.box.top }; val bottom = r.maxOf { x -> x.box.bottom }
                val rc = (top + bottom) / 2
                val h = maxOf(bottom - top, it.box.height())
                if (abs(it.cy - rc) <= h / 2) { r += it; continue }
            }
            rows += mutableListOf(it)
        }
        return rows.map { r -> Row(r.sortedBy { it.box.left }) }
    }

    /**
     * Title guess: in the top 18% of the screen, the text with the largest
     * characters (ties: closest to the horizontal centre). A search box hint
     * is usually smaller than a real title, but this stays a guess.
     */
    fun titleGuess(items: List<Item>, width: Int, height: Int, exclude: (Item) -> Boolean = { false }): Pair<String?, List<String>> {
        val top = items.filter { it.box.bottom <= height * 0.18 && it.box.width() <= width / 2 && !exclude(it) && titleLike(it.text) }
        val best = top.maxWithOrNull(compareBy<Item>({ (it.glyphs.map { g -> g.box.height() }.maxOrNull() ?: it.box.height()) / 4 },
            { -abs(it.cx - width / 2) }))
        return best?.text to top.take(6).map { it.text }
    }

    /**
     * v47: could [t] be a page title? Short (2–16 characters), not a value (a clock, a count) and not a piece of a
     * sentence (body text at the top of a reader page, a wallpaper preview's time were taken as screen names).
     */
    fun titleLike(t: String): Boolean {
        val n = norm(t)
        return n.length in 2..16 && !VScreenGame.isValue(n) && t.count { it in "，。,.;；:：!！?？|()（）、" } <= 1
    }

    // ── movement between two OCR results ─────────────────────────────────

    data class Movement(
        /** Content movement along the axis (px, signed), null when it cannot be measured. */
        val delta: Int?,
        val movingMatches: Int,
        /** Items of the newer result that did not move while the content did (sticky bars, bottom nav). */
        val fixed: List<Item>,
    )

    /**
     * How far the content moved between [before] and [after], from texts
     * that appear exactly once in both. Items that stay put while others
     * move are reported as fixed.
     */
    fun movement(before: List<Item>, after: List<Item>, vertical: Boolean): Movement {
        fun uniq(list: List<Item>) = list.filter { norm(it.text).length >= 2 }.groupBy { norm(it.text) }
            .filterValues { it.size == 1 }.mapValues { it.value[0] }
        val a = uniq(before); val b = uniq(after)
        val moving = ArrayList<Int>()
        val still = ArrayList<Item>()
        for ((k, ia) in a) {
            val ib = b[k] ?: continue
            val cross = if (vertical) ib.cx - ia.cx else ib.cy - ia.cy
            if (abs(cross) > 24) continue
            val d = if (vertical) ib.cy - ia.cy else ib.cx - ia.cx
            if (abs(d) <= 4) still += ib else moving += d
        }
        if (moving.size >= 2) {
            moving.sort()
            val med = moving[moving.size / 2]
            // Only call items fixed when the content clearly moved.
            return Movement(med, moving.size, if (abs(med) > 30) still else emptyList())
        }
        if (moving.isEmpty() && still.size >= 2) return Movement(0, 0, emptyList())
        if (moving.size == 1 && still.isEmpty()) return Movement(moving[0], 1, emptyList())
        return Movement(null, moving.size, emptyList())
    }
}
