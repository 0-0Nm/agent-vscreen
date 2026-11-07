package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [vscreen] v27: graphic targets without a model. Finds small shapes that stand out from the
 * background (game stations, map pins, coloured dots, icons on a plain board) by colour, and
 * reports each one's centre, box, colour and rough shape. Pure pixel work (~50–150 ms), so an
 * agent can point at things OCR cannot read and accessibility does not list.
 */
object VScreenSpots {
    data class Spot(val box: Rect, val cx: Int, val cy: Int, val color: Int, val area: Int, val fill: Double, val hollow: Boolean, val shape: String)

    /**
     * [minSide]/[maxSide] bound a spot's box (screen px); [region] limits the search; [color] keeps
     * spots whose mean colour is near it (RGB distance ≤ [colorTol]).
     */
    fun find(frame: Bitmap, minSide: Int = 12, maxSide: Int = 160, region: Rect? = null, color: Int? = null, colorTol: Int = 70, limit: Int = 80): List<Spot> {
        val k = maxOf(1, (maxOf(frame.width, frame.height) / 640.0).roundToInt())
        val w = frame.width / k; val h = frame.height / k
        if (w < 8 || h < 8) return emptyList()
        val small = if (k == 1) frame else Bitmap.createScaledBitmap(frame, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== frame) small.recycle()
        // Background = the colours (quantised to 4 bits per channel) that cover large areas.
        val hist = HashMap<Int, Int>()
        for (p in px) { val q = quant(p); hist[q] = (hist[q] ?: 0) + 1 }
        val bgMin = (w * h * 0.04).toInt().coerceAtLeast(16)
        val bg = hist.filterValues { it >= bgMin }.keys
        val mask = BooleanArray(w * h) { i -> quant(px[i]) !in bg && !nearAny(px[i], bg) }
        val rx0 = region?.let { (it.left / k).coerceIn(0, w - 1) } ?: 0
        val ry0 = region?.let { (it.top / k).coerceIn(0, h - 1) } ?: 0
        val rx1 = region?.let { (it.right / k).coerceIn(1, w) } ?: w
        val ry1 = region?.let { (it.bottom / k).coerceIn(1, h) } ?: h
        val seen = BooleanArray(w * h)
        val out = ArrayList<Spot>()
        val stack = IntArray(w * h)
        val comp = IntArray(w * h)
        for (y in ry0 until ry1) for (x in rx0 until rx1) {
            val i0 = y * w + x
            if (!mask[i0] || seen[i0]) continue
            var sp = 0; stack[sp++] = i0; seen[i0] = true
            var minX = x; var maxX = x; var minY = y; var maxY = y; var area = 0
            var r = 0L; var g = 0L; var b = 0L
            while (sp > 0) {
                val i = stack[--sp]; val cx = i % w; val cy = i / w
                comp[area] = i
                area++
                val p = px[i]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                if (cx > rx0) { val j = i - 1; if (mask[j] && !seen[j]) { seen[j] = true; stack[sp++] = j } }
                if (cx < rx1 - 1) { val j = i + 1; if (mask[j] && !seen[j]) { seen[j] = true; stack[sp++] = j } }
                if (cy > ry0) { val j = i - w; if (mask[j] && !seen[j]) { seen[j] = true; stack[sp++] = j } }
                if (cy < ry1 - 1) { val j = i + w; if (mask[j] && !seen[j]) { seen[j] = true; stack[sp++] = j } }
            }
            val bw = (maxX - minX + 1) * k; val bh = (maxY - minY + 1) * k
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            val aspect = bw.toDouble() / bh
            if (aspect < 0.33 || aspect > 3.0) continue // lines and bars are not spots
            val fill = area.toDouble() / ((maxX - minX + 1) * (maxY - minY + 1))
            if (fill < 0.12) continue
            val mean = (0xFF shl 24) or ((r / area).toInt() shl 16) or ((g / area).toInt() shl 8) or (b / area).toInt()
            if (color != null && dist(mean, color) > colorTol) continue
            // v42: a hollow shape (ring, outlined square) is classified by its OUTLINE: holes filled in first.
            // Before, every hollow blob was "ring", so --shape circle missed outlined circles and ring matched squares.
            val bwS = maxX - minX + 1; val bhS = maxY - minY + 1
            val holes = holesIn(comp, area, w, minX, minY, bwS, bhS)
            // v43: also hollow when the middle is another colour than the rim (a white station with a black ring is one blob).
            val midY = (minY + maxY) / 2; val midX = (minX + maxX) / 2
            val rimC = (minX..midX).firstOrNull { mask[midY * w + it] }?.let { px[midY * w + it] }
            val hollow = holes >= bwS * bhS * 0.08 || (bwS >= 6 && rimC != null && dist(rimC, px[midY * w + midX]) > 90)
            val outline = (area + holes).toDouble() / (bwS * bhS)
            val shape = shapeOf(outline, aspect)
            out += Spot(Rect(minX * k, minY * k, (maxX + 1) * k, (maxY + 1) * k), (minX + maxX + 1) * k / 2, (minY + maxY + 1) * k / 2,
                mean, area * k * k, (fill * 100).roundToInt() / 100.0, hollow, shape)
        }
        return out.sortedWith(compareBy({ it.cy / 40 }, { it.cx })).take(limit)
    }

    /** Outline fill ratio + aspect → circle / square / triangle / blob. */
    fun shapeOf(outline: Double, aspect: Double): String = when {
        outline > 0.88 && aspect in 0.8..1.25 -> "square"
        outline in 0.68..0.88 && aspect in 0.8..1.25 -> "circle"
        outline in 0.38..0.62 -> "triangle"
        else -> "blob"
    }

    /** Pixels inside the blob's bounding box that are enclosed by it (not reachable from the box edge). */
    fun holesIn(comp: IntArray, area: Int, w: Int, minX: Int, minY: Int, bw: Int, bh: Int): Int {
        val n = bw * bh
        val inBlob = BooleanArray(n)
        for (t in 0 until area) { val i = comp[t]; inBlob[(i / w - minY) * bw + (i % w - minX)] = true }
        val out = BooleanArray(n); val st = IntArray(n); var sp = 0
        fun push(j: Int) { if (!inBlob[j] && !out[j]) { out[j] = true; st[sp++] = j } }
        for (x in 0 until bw) { push(x); push((bh - 1) * bw + x) }
        for (y in 0 until bh) { push(y * bw); push(y * bw + bw - 1) }
        while (sp > 0) {
            val j = st[--sp]; val x = j % bw; val y = j / bw
            if (x > 0) push(j - 1); if (x < bw - 1) push(j + 1); if (y > 0) push(j - bw); if (y < bh - 1) push(j + bw)
        }
        var holes = 0
        for (j in 0 until n) if (!inBlob[j] && !out[j]) holes++
        return holes
    }

    /** --shape words: an outline (circle/round, square, triangle, blob) and/or filled / ring (= hollow); comma = all must hold. */
    val SHAPE_WORDS = listOf("circle", "round", "square", "triangle", "blob", "filled", "ring", "hollow")

    fun shapeMatches(s: Spot, words: List<String>): Boolean = words.all { wd ->
        when (wd) {
            "circle", "round" -> s.shape == "circle"
            "ring", "hollow" -> s.hollow
            "filled" -> !s.hollow
            else -> s.shape == wd
        }
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun parseColor(s: String): Int? = runCatching {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) null else (0xFF shl 24) or t.toInt(16)
    }.getOrNull()

    private fun quant(p: Int): Int = (((p shr 20) and 15) shl 8) or (((p shr 12) and 15) shl 4) or ((p shr 4) and 15)

    /** Anti-aliased edges of the background are background too. */
    private fun nearAny(p: Int, bg: Set<Int>): Boolean {
        val r = (p shr 20) and 15; val g = (p shr 12) and 15; val b = (p shr 4) and 15
        for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
            val q = (((r + dr).coerceIn(0, 15)) shl 8) or (((g + dg).coerceIn(0, 15)) shl 4) or ((b + db).coerceIn(0, 15))
            if (q in bg) return true
        }
        return false
    }

    fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).roundToInt()
    }

    /**
     * Fraction of points along a polyline (screen px) whose colour changed between two frames
     * (same size): did the drag leave a mark (a line drawn, a piece moved)? Null when not comparable.
     */
    fun pathChanged(before: Bitmap?, after: Bitmap?, pts: List<Pair<Int, Int>>, step: Int = 8, threshold: Int = 60): Double? {
        if (before == null || after == null || before.isRecycled || after.isRecycled) return null
        if (before.width != after.width || before.height != after.height || pts.size < 2) return null
        var n = 0; var changed = 0
        for ((a, b) in pts.zipWithNext()) {
            val len = sqrt(((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second)).toDouble())
            val steps = maxOf(1, (len / step).toInt())
            // Skip the ends: the finger's own start/end spots (stations, buttons) are not the mark.
            for (s in 1 until steps) {
                val t = s.toDouble() / steps
                val x = (a.first + (b.first - a.first) * t).roundToInt().coerceIn(0, before.width - 1)
                val y = (a.second + (b.second - a.second) * t).roundToInt().coerceIn(0, before.height - 1)
                n++
                if (dist(before.getPixel(x, y), after.getPixel(x, y)) > threshold) changed++
            }
        }
        if (n == 0) return null
        return (changed * 100.0 / n).roundToInt() / 100.0
    }

    @Suppress("unused") private fun absDiff(a: Int, b: Int) = abs(a - b)
}
