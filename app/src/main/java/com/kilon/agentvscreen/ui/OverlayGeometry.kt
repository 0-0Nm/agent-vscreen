package com.kilon.agentvscreen.ui

import kotlin.math.max
import kotlin.math.min

/** Pure placement policy for the overlay. It never delegates an inverted range to Float.coerceIn. */
internal object OverlayGeometry {
    data class Result(val x: Float, val y: Float, val deferred: Boolean = false)

    fun finite(value: Float, fallback: Float = 0f): Float =
        if (value.isFinite()) value else fallback

    fun place(
        screenW: Float, screenH: Float,
        windowW: Float, windowH: Float,
        topInset: Float, bottomInset: Float,
        requestedX: Float, requestedY: Float,
    ): Result {
        val sw = finite(screenW); val sh = finite(screenH)
        val ww = finite(windowW); val wh = finite(windowH)
        if (sw <= 0f || sh <= 0f || ww <= 0f || wh <= 0f) return Result(0f, 0f, deferred = true)

        val maxX = max(0f, sw - ww)
        val maxY = max(0f, sh - wh)
        val top = finite(topInset).coerceIn(0f, sh)
        val bottom = finite(bottomInset).coerceIn(0f, sh)
        val insetLow = top
        val insetHigh = sh - wh - bottom
        val (lowY, highY) = if (insetLow <= insetHigh) insetLow to insetHigh else 0f to maxY
        return Result(
            x = finite(requestedX).coerceIn(0f, maxX),
            y = finite(requestedY).coerceIn(lowY.coerceIn(0f, maxY), highY.coerceIn(0f, maxY)),
        )
    }

    fun rangeClamp(value: Float, minValue: Float, maxValue: Float): Float {
        val v = finite(value)
        val lo = finite(minValue)
        val hi = finite(maxValue)
        return if (lo <= hi) v.coerceIn(lo, hi) else v.coerceIn(min(lo, hi), max(lo, hi))
    }
}
