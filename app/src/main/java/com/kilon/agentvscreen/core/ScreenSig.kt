package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * [vscreen] Cheap screen signature used to tell "nothing changed" from
 * "part of the page changed" from "a different page".
 *
 * Two measures on a 33x32 grayscale thumbnail:
 *  - cell: share of the 32x32 cells whose brightness moved noticeably
 *    (catches colour/brightness changes);
 *  - hash: a 1024-bit difference hash (is each cell brighter than its right
 *    neighbour?), which follows layout and text structure. Two white list
 *    pages with different content look alike by brightness but not by
 *    structure, which is what the old 16x16 brightness grid got wrong.
 * Both are also measured on the top 1/8 (the title bar: a virtual display
 * has no status bar, so this is the app's own header).
 */
internal class ScreenSig private constructor(
    private val lum: IntArray,
    private val bits: BooleanArray,
) {
    enum class Kind(val label: String) {
        NONE("none"), LOCAL("local"), PAGE("page"), UNKNOWN("unknown")
    }

    data class Change(
        val cell: Double,
        val hash: Double,
        val topCell: Double,
        val topHash: Double,
        /** Share of pixels that changed at ~216px width (catches a digit or a tick box); 0 = not measured. */
        val fine: Double = 0.0,
    ) {
        val kind: Kind
            get() = when {
                hash < 0.015 && cell < 0.02 && fine < FINE_MIN -> Kind.NONE
                hash < 0.015 && cell < 0.02 -> Kind.LOCAL
                hash >= 0.22 || cell >= 0.45 -> Kind.PAGE
                topHash >= 0.25 && hash >= 0.08 -> Kind.PAGE
                hash < 0.10 && cell < 0.25 -> Kind.LOCAL
                else -> Kind.UNKNOWN
            }

        /** One number for the agent: the larger of the two measures. */
        val ratio: Double get() = maxOf(cell, hash, fine)

        fun describeZh(): String = when (kind) {
            Kind.NONE -> "无明显变化"
            Kind.LOCAL -> "局部变化（约 ${pct(ratio)}）"
            Kind.PAGE -> if (topHash >= 0.25) "整页切换（标题区也变了）" else "整页切换（约 ${pct(ratio)}）"
            Kind.UNKNOWN -> "unknown（变化约 ${pct(ratio)}，无法判断是局部刷新还是换页）"
        }

        private fun pct(v: Double) = if (v > 0 && v < 0.01) "<1%" else "${(v * 100).toInt()}%"
    }

    fun compare(other: ScreenSig): Change {
        var cell = 0; var hash = 0; var topCell = 0; var topHash = 0
        for (i in 0 until N * N) {
            val top = i < N * TOP_ROWS
            if (abs(lum[i] - other.lum[i]) > 10) { cell++; if (top) topCell++ }
            if (bits[i] != other.bits[i]) { hash++; if (top) topHash++ }
        }
        val all = (N * N).toDouble(); val topAll = (N * TOP_ROWS).toDouble()
        return Change(cell / all, hash / all, topCell / topAll, topHash / topAll)
    }

    /** 16 hex chars: an 8x8 average hash, stable enough to compare two reads by eye. */
    fun hex(): String {
        val block = N / 8
        val avg = IntArray(64)
        for (by in 0 until 8) for (bx in 0 until 8) {
            var s = 0
            for (y in 0 until block) for (x in 0 until block) s += lum[(by * block + y) * N + bx * block + x]
            avg[by * 8 + bx] = s / (block * block)
        }
        val mean = avg.average()
        var v = 0L
        for (i in 0 until 64) if (avg[i] > mean) v = v or (1L shl i)
        return java.lang.Long.toHexString(v).padStart(16, '0')
    }

    companion object {
        private const val N = 32
        private const val TOP_ROWS = 4
        /** A few dozen changed pixels at 216px width: a digit, a caret, a toggle. */
        const val FINE_MIN = 0.0004

        /**
         * Fraction of pixels whose luminance changed noticeably between [a] and [b],
         * compared at ~216px width. Small edits (typing, a calculator key, a check
         * box) that the coarse signature calls "none" show up here. -1 = cannot tell.
         */
        fun fineDiff(a: Bitmap?, b: Bitmap?): Double {
            if (a == null || b == null || a.isRecycled || b.isRecycled) return -1.0
            if (a.width != b.width || a.height != b.height) return 1.0
            return runCatching {
                val w = 216
                val h = (a.height.toLong() * w / a.width).toInt().coerceIn(1, 2000)
                val sa = Bitmap.createScaledBitmap(a, w, h, true)
                val sb = Bitmap.createScaledBitmap(b, w, h, true)
                val pa = IntArray(w * h); val pb = IntArray(w * h)
                sa.getPixels(pa, 0, w, 0, 0, w, h); sb.getPixels(pb, 0, w, 0, 0, w, h)
                if (sa !== a) sa.recycle(); if (sb !== b) sb.recycle()
                var n = 0
                for (i in pa.indices) {
                    val ca = pa[i]; val cb = pb[i]
                    val la = (((ca shr 16) and 0xff) * 3 + ((ca shr 8) and 0xff) * 6 + (ca and 0xff)) / 10
                    val lb = (((cb shr 16) and 0xff) * 3 + ((cb shr 8) and 0xff) * 6 + (cb and 0xff)) / 10
                    if (abs(la - lb) > 28) n++
                }
                n.toDouble() / pa.size
            }.getOrDefault(-1.0)
        }

        fun of(bmp: Bitmap?): ScreenSig? {
            if (bmp == null || bmp.isRecycled) return null
            return runCatching {
                val small = Bitmap.createScaledBitmap(bmp, N + 1, N, true)
                val px = IntArray((N + 1) * N)
                small.getPixels(px, 0, N + 1, 0, 0, N + 1, N)
                if (small !== bmp) small.recycle()
                val l = IntArray((N + 1) * N) { i ->
                    val c = px[i]
                    (((c shr 16) and 0xff) * 3 + ((c shr 8) and 0xff) * 6 + (c and 0xff)) / 10
                }
                val lum = IntArray(N * N)
                val bits = BooleanArray(N * N)
                for (y in 0 until N) for (x in 0 until N) {
                    val a = l[y * (N + 1) + x]; val b = l[y * (N + 1) + x + 1]
                    lum[y * N + x] = a
                    bits[y * N + x] = a > b
                }
                ScreenSig(lum, bits)
            }.getOrNull()
        }
    }
}
