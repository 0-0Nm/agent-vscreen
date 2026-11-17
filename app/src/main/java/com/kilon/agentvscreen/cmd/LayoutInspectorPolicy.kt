package com.kilon.agentvscreen.cmd

/** Pure, conservative policy shared by the native inspector and its command. No input injection. */
internal object LayoutInspectorPolicy {
    const val MAX_FRAME_AGE_MS = 2_000L
    fun alignment(pageSame: Boolean, hasFrame: Boolean, sensitive: Boolean, truncated: Boolean,
                  ageMs: Long, frameStable: Boolean, dimensionsMatch: Boolean, displayOn: Boolean): String = when {
        !pageSame -> "PAGE_CHANGED"
        sensitive -> "SENSITIVE_CONTENT"
        truncated -> "TREE_TRUNCATED"
        !hasFrame -> "NO_FRAME"
        !displayOn -> "DISPLAY_NOT_ON"
        !dimensionsMatch -> "COORDINATE_SPACE_MISMATCH"
        ageMs < 0 || ageMs > MAX_FRAME_AGE_MS -> "CACHED_FRAME"
        !frameStable -> "FRAME_CHANGED_DURING_CAPTURE"
        else -> "BEST_EFFORT"
    }
    fun allowScreenshot(sensitive: Boolean, truncated: Boolean, exclusivelyTargetOwned: Boolean): Boolean =
        !sensitive && !truncated && exclusivelyTargetOwned
    data class Box(val id: Int, val parent: Int?, val depth: Int, val left: Int, val top: Int,
                   val right: Int, val bottom: Int, val visible: Boolean, val label: String) {
        val area: Long get() = (right.toLong() - left).coerceAtLeast(0) * (bottom.toLong() - top).coerceAtLeast(0)
    }
    fun hit(boxes: List<Box>, x: Float, y: Float): Int? {
        if (!x.isFinite() || !y.isFinite()) return null
        return boxes.filter { it.visible && it.area > 0 && x >= it.left && x < it.right && y >= it.top && y < it.bottom }
            .minWithOrNull(compareBy<Box> { it.area }.thenByDescending { it.depth })?.id
    }
    fun visibleRows(boxes: List<Box>, collapsed: Set<Int>, query: String): List<Box> {
        val byId = boxes.associateBy { it.id }
        fun ancestors(b: Box): List<Int> {
            val out = ArrayList<Int>(); var p = b.parent
            while (p != null && p !in out && out.size <= boxes.size) { out.add(p); p = byId[p]?.parent }
            return out
        }
        if (query.isNotBlank()) {
            val keep = HashSet<Int>()
            boxes.filter { it.label.contains(query, ignoreCase = true) }.forEach { keep.add(it.id); keep.addAll(ancestors(it)) }
            return boxes.filter { it.id in keep }
        }
        return boxes.filter { b -> ancestors(b).none { it in collapsed } }
    }
}
