package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [vscreen v34] Pixel facts that do not depend on what the app is: how much of a colour is in a
 * region, what colour a point has, and which other colours are close to a point. Used for
 * post-conditions (`--expect-color/--expect-more/--expect-less/--expect-change/--expect-same`)
 * and for "is this drop point too close to another line/object" checks. No model, a few ms.
 */
internal object VPixels {
    fun dist(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble(); val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** A colour that stands out from grey/white/black backgrounds (lines, pieces, markers). */
    fun vivid(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return mx - mn >= 60 && mx >= 70
    }

    fun clip(r: Rect, w: Int, h: Int): Rect =
        Rect(r.left.coerceIn(0, w), r.top.coerceIn(0, h), r.right.coerceIn(0, w), r.bottom.coerceIn(0, h))

    /** Pixels within [tol] of [color] inside [region] (null = all), sampled every [step] px. */
    fun count(bmp: Bitmap, color: Int, tol: Int, region: Rect? = null, step: Int = 2): Int {
        val r = clip(region ?: Rect(0, 0, bmp.width, bmp.height), bmp.width, bmp.height)
        var n = 0
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) { if (dist(bmp.getPixel(x, y), color) <= tol) n++; x += step }
            y += step
        }
        return n
    }

    /** Mean colour of the (2r+1)² square around (x, y). */
    fun at(bmp: Bitmap, x: Int, y: Int, r: Int = 2): Int {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (yy in (y - r)..(y + r)) for (xx in (x - r)..(x + r)) {
            if (xx < 0 || yy < 0 || xx >= bmp.width || yy >= bmp.height) continue
            val c = bmp.getPixel(xx, yy); sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
        }
        if (n == 0) return 0
        return Color.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    data class Near(val color: Int, val distance: Int, val dx: Int, val dy: Int, val pixels: Int, val share: Double = 0.0) {
        /** Fills a large part of the neighbourhood: a background / area colour, not a line or a piece. */
        val background: Boolean get() = share >= 0.3
        val direction: String get() {
            if (distance == 0) return "here"
            val a = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
            return when {
                a >= -22.5 && a < 22.5 -> "right"; a >= 22.5 && a < 67.5 -> "up-right"; a >= 67.5 && a < 112.5 -> "up"
                a >= 112.5 && a < 157.5 -> "up-left"; a >= 157.5 || a < -157.5 -> "left"; a >= -157.5 && a < -112.5 -> "down-left"
                a >= -112.5 && a < -67.5 -> "down"; else -> "down-right"
            }
        }
    }

    /**
     * Vivid colours within [radius] of (x, y), each with the distance of its nearest pixel, nearest first.
     * Colours closer than [merge] count as one colour (anti-aliasing, shading).
     */
    fun near(bmp: Bitmap, x: Int, y: Int, radius: Int, merge: Int = 70, step: Int = 2): List<Near> {
        class Acc(var color: Int, var d: Double, var dx: Int, var dy: Int, var n: Int)
        val acc = ArrayList<Acc>()
        var total = 0
        var yy = y - radius
        while (yy <= y + radius) {
            var xx = x - radius
            while (xx <= x + radius) {
                if (xx in 0 until bmp.width && yy in 0 until bmp.height) {
                    val d = hypot((xx - x).toDouble(), (yy - y).toDouble())
                    if (d <= radius) {
                        total++
                        val c = bmp.getPixel(xx, yy)
                        if (vivid(c)) {
                            val a = acc.firstOrNull { dist(it.color, c) <= merge }
                            if (a == null) acc += Acc(c, d, xx - x, yy - y, 1)
                            else { a.n++; if (d < a.d) { a.d = d; a.dx = xx - x; a.dy = yy - y } }
                        }
                    }
                }
                xx += step
            }
            yy += step
        }
        // Fewer than 6 samples = anti-aliased edge between two colours, not a colour of its own.
        return acc.filter { it.n >= 6 }.sortedBy { it.d }.map { Near(it.color, it.d.roundToInt(), it.dx, it.dy, it.n, it.n.toDouble() / total.coerceAtLeast(1)) }
    }

    /** One expectation about pixels. */
    sealed class Expect {
        abstract val label: String
        data class ColorAt(val x: Int, val y: Int, val color: Int, val tol: Int) : Expect() {
            override val label get() = "($x,$y) 是 ${hex(color)}"
        }
        data class More(val color: Int, val region: Rect?, val tol: Int) : Expect() {
            override val label get() = "${hex(color)} 变多" + (region?.let { " 在 ${box(it)}" } ?: "")
        }
        data class Less(val color: Int, val region: Rect?, val tol: Int) : Expect() {
            override val label get() = "${hex(color)} 变少" + (region?.let { " 在 ${box(it)}" } ?: "")
        }
        data class Change(val region: Rect) : Expect() { override val label get() = "区域 ${box(region)} 变了" }
        data class Same(val region: Rect) : Expect() { override val label get() = "区域 ${box(region)} 没变" }
    }

    data class Outcome(val expect: Expect, val ok: Boolean?, val detail: String)

    fun box(r: Rect) = "${r.left},${r.top},${r.right},${r.bottom}"

    fun check(e: Expect, before: Bitmap?, after: Bitmap): Outcome = when (e) {
        is Expect.ColorAt -> {
            val c = at(after, e.x, e.y)
            val d = dist(c, e.color)
            Outcome(e, d <= e.tol, "现在是 ${hex(c)}（差 ${d.roundToInt()}，容差 ${e.tol}）")
        }
        is Expect.More -> amount(e, e.color, e.region, e.tol, before, after, more = true)
        is Expect.Less -> amount(e, e.color, e.region, e.tol, before, after, more = false)
        is Expect.Change -> region(e, e.region, before, after, change = true)
        is Expect.Same -> region(e, e.region, before, after, change = false)
    }

    private fun amount(e: Expect, color: Int, region: Rect?, tol: Int, before: Bitmap?, after: Bitmap, more: Boolean): Outcome {
        val now = count(after, color, tol, region)
        if (before == null) return Outcome(e, null, "没有动作前的画面可比（现在 $now 个采样点）")
        val was = count(before, color, tol, region)
        val need = maxOf(6, (was * 0.04).roundToInt())
        val ok = if (more) now >= was + need else now <= was - need
        return Outcome(e, ok, "采样点 $was → $now（至少要变 $need）")
    }

    private fun region(e: Expect, r: Rect, before: Bitmap?, after: Bitmap, change: Boolean): Outcome {
        if (before == null) return Outcome(e, null, "没有动作前的画面可比")
        val f = regionDiff(before, after, r)
        if (f < 0) return Outcome(e, null, "区域不在画面里")
        return Outcome(e, if (change) f > 0.08 else f < 0.03, "区域里 ${(f * 100).roundToInt()}% 的像素变了")
    }

    /** Share of sampled pixels in [r0] that differ noticeably between two same-size frames; -1 = cannot compare. */
    fun regionDiff(a: Bitmap, b: Bitmap, r0: Rect): Double {
        if (a.width != b.width || a.height != b.height) return -1.0
        val r = clip(r0, a.width, a.height)
        if (r.width() <= 0 || r.height() <= 0) return -1.0
        var n = 0; var diff = 0
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) { n++; if (dist(a.getPixel(x, y), b.getPixel(x, y)) > 40) diff++; x += 2 }
            y += 2
        }
        return if (n == 0) -1.0 else diff.toDouble() / n
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    fun parseColor(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
    }

    /** true when every one passed, false when any failed, null when some could not be judged. */
    fun overall(list: List<Outcome>): Boolean? = when {
        list.any { it.ok == false } -> false
        list.all { it.ok == true } -> true
        else -> null
    }
}
