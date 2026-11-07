package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [vscreen] Pixel facts on a coarse grid: which cells changed between two frames, where, and whether a frame is one
 * flat colour. v49: the background learner that lived here (what moves "all the time", masked out of every judgement)
 * is gone with the judgements it served; changes are reported as they are and the model decides what matters.
 */
object VScreenActivity {
    const val COLS = 24
    private const val LUM_DIFF = 28
    private const val CELL_SHARE = 0.04

    fun rowsFor(w: Int, h: Int): Int = (COLS.toDouble() * h / maxOf(1, w)).roundToInt().coerceIn(6, 60)

    /** Which cells of a cols x rows grid changed between two frames of the same size (null: not comparable). */
    fun cells(a: Bitmap?, b: Bitmap?, cols: Int, rows: Int): BooleanArray? {
        if (a == null || b == null || a.isRecycled || b.isRecycled || a.width != b.width || a.height != b.height) return null
        val w = a.width; val h = a.height
        val pa = IntArray(w * h); val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h); b.getPixels(pb, 0, w, 0, 0, w, h)
        return cellsOf(pa, pb, w, h, cols, rows)
    }

    /** [cells] on raw ARGB pixel arrays (testable without Android bitmaps). */
    fun cellsOf(pa: IntArray, pb: IntArray, w: Int, h: Int, cols: Int, rows: Int): BooleanArray {
        val hit = IntArray(cols * rows); val all = IntArray(cols * rows)
        var y = 0
        while (y < h) {
            val cy = (y.toLong() * rows / h).toInt().coerceIn(0, rows - 1)
            var x = 0
            while (x < w) {
                val i = y * w + x
                val c = cy * cols + (x.toLong() * cols / w).toInt().coerceIn(0, cols - 1)
                all[c]++
                if (abs(lum(pa[i]) - lum(pb[i])) > LUM_DIFF) hit[c]++
                x += 2
            }
            y += 2
        }
        return BooleanArray(cols * rows) { all[it] > 0 && hit[it].toDouble() / all[it] >= CELL_SHARE }
    }

    fun share(cells: BooleanArray?): Double = if (cells == null || cells.isEmpty()) 0.0 else cells.count { it }.toDouble() / cells.size

    /** Connected true cells as frame boxes, biggest first. */
    fun boxes(cells: BooleanArray, cols: Int, rows: Int, w: Int, h: Int): List<Rect> {
        val seen = BooleanArray(cells.size)
        val out = ArrayList<Pair<Int, Rect>>()
        for (s in cells.indices) {
            if (!cells[s] || seen[s]) continue
            val stack = ArrayDeque<Int>(); stack.addLast(s); seen[s] = true
            var x0 = cols; var y0 = rows; var x1 = 0; var y1 = 0; var n = 0
            while (stack.isNotEmpty()) {
                val i = stack.removeLast(); val cx = i % cols; val cy = i / cols; n++
                x0 = minOf(x0, cx); y0 = minOf(y0, cy); x1 = maxOf(x1, cx); y1 = maxOf(y1, cy)
                for (k in 0 until 4) {
                    val nx = cx + intArrayOf(1, -1, 0, 0)[k]; val ny = cy + intArrayOf(0, 0, 1, -1)[k]
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val j = ny * cols + nx
                    if (cells[j] && !seen[j]) { seen[j] = true; stack.addLast(j) }
                }
            }
            out += n to Rect(x0 * w / cols, y0 * h / rows, (x1 + 1) * w / cols, (y1 + 1) * h / rows)
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** One box around all true cells (frame pixels), or null. */
    fun bbox(cells: BooleanArray, cols: Int, rows: Int, w: Int, h: Int): Rect? {
        var x0 = cols; var y0 = rows; var x1 = -1; var y1 = -1
        for (i in cells.indices) if (cells[i]) {
            val cx = i % cols; val cy = i / cols
            x0 = minOf(x0, cx); y0 = minOf(y0, cy); x1 = maxOf(x1, cx); y1 = maxOf(y1, cy)
        }
        if (x1 < 0) return null
        return Rect(x0 * w / cols, y0 * h / rows, (x1 + 1) * w / cols, (y1 + 1) * h / rows)
    }

    /** Cells whose centre lies inside frame rect [r] of a [w]x[h] frame. */
    fun cellsIn(r: Rect, cols: Int, rows: Int, w: Int, h: Int): BooleanArray = BooleanArray(cols * rows) { i ->
        val cx = i % cols; val cy = i / cols
        val x = (cx * w / cols + (cx + 1) * w / cols) / 2; val y = (cy * h / rows + (cy + 1) * h / rows) / 2
        x >= r.left && x < r.right && y >= r.top && y < r.bottom
    }

    /** A flat frame (all white / all black / one colour): a transition, not a page. */
    fun uniform(b: Bitmap?): Boolean {
        if (b == null || b.isRecycled || b.width < 4 || b.height < 4) return false
        var lo = 255; var hi = 0
        for (j in 0 until 24) for (i in 0 until 24) {
            val l = lum(b.getPixel((b.width - 1) * i / 23, (b.height - 1) * j / 23))
            if (l < lo) lo = l; if (l > hi) hi = l
            if (hi - lo > 12) return false
        }
        return true
    }

    internal fun lum(c: Int) = (((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10

}
