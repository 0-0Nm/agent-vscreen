package com.kilon.agentvscreen.cmd

import kotlin.math.min

/** One transform for both rendering and hit testing; no Android/UI dependencies. */
internal object LayoutInspectorViewport {
    data class Transform(val left: Float, val top: Float, val scale: Float, val sourceWidth: Int, val sourceHeight: Int) {
        fun source(x: Float, y: Float): Pair<Float, Float>? {
            if (!x.isFinite() || !y.isFinite() || !scale.isFinite() || scale <= 0) return null
            val sx = (x - left) / scale; val sy = (y - top) / scale
            return if (sx >= 0 && sy >= 0 && sx < sourceWidth && sy < sourceHeight) sx to sy else null
        }
    }
    fun fit(width: Float, height: Float, sourceWidth: Int, sourceHeight: Int, zoom: Float, panX: Float, panY: Float): Transform? {
        if (!width.isFinite() || !height.isFinite() || width <= 48 || height <= 36 || sourceWidth <= 0 || sourceHeight <= 0 ||
            !zoom.isFinite() || !panX.isFinite() || !panY.isFinite()) return null
        val scale = min((width - 42) / sourceWidth, (height - 34) / sourceHeight) * zoom.coerceIn(1f,4f)
        val cx = (34 + width - 8) / 2f + panX; val cy = (26 + height - 8) / 2f + panY
        return Transform(cx - sourceWidth * scale / 2, cy - sourceHeight * scale / 2, scale, sourceWidth, sourceHeight)
    }
}
