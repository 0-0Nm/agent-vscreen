package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import org.json.JSONObject
import java.util.UUID

/** A detached, bounded snapshot. Bitmap is never persisted by this collector. */
internal data class LayoutSnapshot(val data: JSONObject, val bitmap: Bitmap?) {
    fun boxes(): List<LayoutInspectorPolicy.Box> {
        val a = data.optJSONArray("nodes") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val n = a.getJSONObject(i); val r = n.getJSONObject("bounds")
            LayoutInspectorPolicy.Box(n.getInt("id"), if (n.isNull("parentId")) null else n.getInt("parentId"),
                n.getInt("depth"), r.getInt("left"), r.getInt("top"), r.getInt("right"), r.getInt("bottom"),
                n.optBoolean("visible"), listOf("text", "contentDescription", "resourceId", "class").joinToString(" ") {
                    if (n.isNull(it)) "" else n.optString(it)
                })
        }
    }
}

internal object LayoutInspector {
    fun capture(session: String, maxNodes: Int = 200, maxDepth: Int = 20): LayoutSnapshot {
        if (maxNodes !in 1..200 || maxDepth !in 0..20) throw ActionError("INVALID_ARGS", "节点上限1..200，深度0..20")
        // Fail promptly in the UI rather than blocking its refresh while waiting for takeover.
        if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", "接管期间不采集布局；交还后重试")
        return BackgroundOperation.agentAction(session, null, isRead = true, describe = "读取副屏布局快照", strictReadOnly = true) { d ->
            if (d.displayId <= 0) throw ActionError("INVALID_DISPLAY", "布局检查器只允许自有副屏")
            val start = SystemClock.elapsedRealtime()
            val pkg = VScreenShell.topPackage(d.displayId)
            fun unavailable(reason: String): LayoutSnapshot = LayoutSnapshot(JSONObject()
                .put("available", false).put("reason", reason).put("display_id", d.displayId).put("generation", d.generation)
                .put("captured_at_ms", SystemClock.elapsedRealtime()).put("read_only", true), null)
            if (pkg == null) return@agentAction unavailable("TARGET_UNAVAILABLE")
            if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK) throw ActionError("APP_BLOCKED", "用户禁止读取此副屏应用")
            val activityBefore = VScreenShell.topActivity(d.displayId)
            val frame = d.cachedFrameInfo()
            val w = d.width; val h = d.height; val iw = d.inputWidth; val ih = d.inputHeight; val rotation = d.rotation; val turn = d.bufferTurn
            val roots = BackgroundOperation.nodeRoots(d.displayId)
            if (roots.isEmpty()) return@agentAction unavailable("ACCESSIBILITY_UNAVAILABLE")
            val exclusivelyTargetOwned = roots.all { runCatching { it.packageName?.toString() == pkg }.getOrDefault(false) }
            val tree = try {
                val scoped = roots.map(ScopedDump::fromAndroid).filter { it.packageName == pkg }
                if (scoped.isEmpty()) return@agentAction unavailable("TARGET_TREE_UNAVAILABLE")
                ScopedDump.dump(scoped, pkg, maxNodes, maxDepth, maxChars = 60 * 1024)
            } finally {
                @Suppress("DEPRECATION")
                roots.forEach { runCatching { it.recycle() } }
            }
            val current = BackgroundOperation.previewDisplayFor(session)
            val st = BackgroundOperation.state.value
            val pageSame = current === d && !d.released && current?.generation == d.generation &&
                st.ownerSessionId == session && !st.userStopped && st.takeoverSessionId == null &&
                VScreenStore.ruleFor(pkg) != VScreenStore.Rule.BLOCK && VScreenShell.topPackage(d.displayId) == pkg && VScreenShell.topActivity(d.displayId) == activityBefore &&
                d.width == w && d.height == h && d.inputWidth == iw && d.inputHeight == ih && d.rotation == rotation && d.bufferTurn == turn
            if (!pageSame) return@agentAction unavailable("PAGE_CHANGED")
            val end = SystemClock.elapsedRealtime()
            val a = tree.getJSONArray("nodes")
            val sensitive = (0 until a.length()).any { a.getJSONObject(it).optBoolean("password") }
            val truncated = tree.optBoolean("truncated")
            val age = frame?.let { (end - it.convertedAtMs).coerceAtLeast(0) } ?: -1L
            val frameStable = frame != null && frame.version == d.frameVersion.value
            val dimensionsMatch = frame != null && iw > 0 && ih > 0 && iw == w && ih == h && frame.bitmap.width == iw && frame.bitmap.height == ih
            var alignment = LayoutInspectorPolicy.alignment(true, frame != null, sensitive, truncated, age, frameStable, dimensionsMatch,
                VScreenAwake.stateOf(d) == Display.STATE_ON && !d.parked)
            // Suppress pictures for password nodes or a truncated tree (a password node could be outside the budget).
            // Other apps' windows/keyboard could be visible; only allow a picture for exclusively target-owned roots.
            var picture: Bitmap? = null
            if (LayoutInspectorPolicy.allowScreenshot(sensitive, truncated, exclusivelyTargetOwned) && frame != null) {
                picture = runCatching { frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            }
            // Copying can overlap a publish or target transition. Do not certify old frame coordinates.
            val finalState = BackgroundOperation.state.value
            if (BackgroundOperation.previewDisplayFor(session) !== d || d.released || finalState.userStopped ||
                finalState.takeoverSessionId != null || finalState.ownerSessionId != session ||
                VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK || VScreenShell.topPackage(d.displayId) != pkg ||
                VScreenShell.topActivity(d.displayId) != activityBefore || d.rotation != rotation || d.bufferTurn != turn ||
                d.width != w || d.height != h || d.inputWidth != iw || d.inputHeight != ih) {
                picture?.recycle()
                return@agentAction unavailable("PAGE_CHANGED")
            }
            if (frame != null && frame.version != d.frameVersion.value) alignment = "FRAME_CHANGED_DURING_CAPTURE"
            if (frame != null && SystemClock.elapsedRealtime() - frame.convertedAtMs > LayoutInspectorPolicy.MAX_FRAME_AGE_MS && alignment == "BEST_EFFORT") alignment = "CACHED_FRAME"
            if (!exclusivelyTargetOwned) alignment = "MIXED_WINDOWS"
            if (picture == null && alignment == "BEST_EFFORT") alignment = "NO_FRAME_COPY"
            tree.put("snapshot_id", UUID.randomUUID().toString()).put("display_id", d.displayId).put("generation", d.generation)
                .put("read_only", true).put("activity", activityBefore ?: JSONObject.NULL).put("source", "native_accessibility").put("tree_started_at_ms", start).put("tree_finished_at_ms", end)
                .put("width", w).put("height", h).put("input_width", iw).put("input_height", ih).put("rotation", rotation * 90).put("frame_turn", turn)
                .put("frame_version", frame?.version ?: JSONObject.NULL).put("frame_received_at_ms", frame?.receivedAtMs ?: JSONObject.NULL)
                .put("frame_converted_at_ms", frame?.convertedAtMs ?: JSONObject.NULL).put("frame_age_ms", age)
                .put("frame_width", frame?.bitmap?.width ?: 0).put("frame_height", frame?.bitmap?.height ?: 0)
                .put("alignment", alignment).put("boxes_enabled", alignment == "BEST_EFFORT" && picture != null)
                .put("screenshot_suppressed", picture == null).put("contains_password", sensitive)
                .put("non_atomic", true).put("warning", "布局和截图不是原子快照；仅作只读检查。旧图或坐标不符时不叠框，点框不会点击应用。")
            LayoutSnapshot(tree, picture)
        }
    }
}
