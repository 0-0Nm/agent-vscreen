package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenAwake
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.InspectorViewTicket
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
    private val retained = LinkedHashMap<String, Pair<Long, LayoutSnapshot>>()
    private fun rememberSnapshot(snapshot: LayoutSnapshot) = synchronized(retained) {
        retained[snapshot.data.getString("human_ref")] = SystemClock.elapsedRealtime() to LayoutSnapshot(JSONObject(snapshot.data.toString()), null)
        while (retained.size > 4) retained.remove(retained.keys.first())
    }
    private fun limits(maxNodes: Int, maxDepth: Int) {
        if (maxNodes !in 1..200 || maxDepth !in 0..20) throw ActionError("INVALID_ARGS", "节点上限1..200，深度0..20")
    }
    /** AI entry remains session-gated. A local inspector never grants it a capability. */
    fun capture(session: String, maxNodes: Int = 200, maxDepth: Int = 20): LayoutSnapshot {
        limits(maxNodes, maxDepth)
        if (BackgroundOperation.state.value.takeoverSessionId != null) throw ActionError("USER_TAKEOVER", "接管期间不采集布局；交还后重试")
        return BackgroundOperation.agentAction(session, null, isRead = true, describe = "读取副屏布局快照", strictReadOnly = true) { d ->
            captureExisting(d, maxNodes, maxDepth) {
                val st = BackgroundOperation.state.value
                BackgroundOperation.previewDisplayFor(session) === d && st.ownerSessionId == session && !st.userStopped && st.takeoverSessionId == null
            }
        }
    }
    /** Local human-only entry; the ticket cannot be supplied through an MCP command. */
    fun captureLocal(ticket: InspectorViewTicket, maxNodes: Int = 200, maxDepth: Int = 20): LayoutSnapshot {
        limits(maxNodes, maxDepth)
        if (!BackgroundOperation.isInspectorViewValid(ticket)) throw ActionError("STALE_SCREEN", "副屏已变化，请重新打开布局")
        return BackgroundOperation.withInspectorDisplay(ticket) { d ->
            captureExisting(d, maxNodes, maxDepth) { BackgroundOperation.isInspectorViewValid(ticket) && BackgroundOperation.currentDisplay() === d }
        }
    }
    fun referenced(session: String, reference: String): LayoutSnapshot {
        val key = reference.trim().uppercase()
        if (!Regex("L-[0-9A-F]{8}").matches(key)) throw ActionError("INVALID_ARGS", "快照号格式例如 L-1234ABCD")
        return BackgroundOperation.agentAction(session, null, isRead = true, describe = "读取指定布局快照", strictReadOnly = true) { d ->
            val pair = synchronized(retained) { retained[key] } ?: throw ActionError("SNAPSHOT_EXPIRED", "快照已过期，请用户刷新后提供新编号")
            val result = pair.second; val data = result.data
            if (SystemClock.elapsedRealtime() - pair.first > 10 * 60_000L) {
                synchronized(retained) { retained.remove(key) }
                throw ActionError("SNAPSHOT_EXPIRED", "快照已过期，请刷新")
            }
            val pkg = data.getString("targetPackage")
            if (data.getInt("display_id") != d.displayId || data.getLong("generation") != d.generation ||
                VScreenShell.topPackage(d.displayId) != pkg || VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK)
                throw ActionError("STALE_SCREEN", "该编号来自其他副屏或应用，不能套用")
            // A referenced snapshot is intentionally historical; never use it to inject.
            LayoutSnapshot(JSONObject(data.toString()).put("referenced_snapshot", true).put("historical", true), null)
        }
    }
    private fun captureExisting(d: VScreenDisplay, maxNodes: Int, maxDepth: Int, stillAllowed: () -> Boolean): LayoutSnapshot {
            if (d.displayId <= 0) throw ActionError("INVALID_DISPLAY", "布局检查器只允许自有副屏")
            val start = SystemClock.elapsedRealtime()
            val pkg = VScreenShell.topPackage(d.displayId)
            fun unavailable(reason: String): LayoutSnapshot = LayoutSnapshot(JSONObject()
                .put("available", false).put("reason", reason).put("display_id", d.displayId).put("generation", d.generation)
                .put("captured_at_ms", SystemClock.elapsedRealtime()).put("read_only", true), null)
            if (pkg == null) return unavailable("TARGET_UNAVAILABLE")
            if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK) throw ActionError("APP_BLOCKED", "用户禁止读取此副屏应用")
            val activityBefore = VScreenShell.topActivity(d.displayId)
            val frame = d.cachedFrameInfo()
            val w = d.width; val h = d.height; val iw = d.inputWidth; val ih = d.inputHeight; val rotation = d.rotation; val turn = d.bufferTurn
            val roots = BackgroundOperation.nodeRoots(d.displayId)
            if (roots.isEmpty()) return unavailable("ACCESSIBILITY_UNAVAILABLE")
            val exclusivelyTargetOwned = roots.all { runCatching { it.packageName?.toString() == pkg }.getOrDefault(false) }
            val tree = try {
                val scoped = roots.map(ScopedDump::fromAndroid).filter { it.packageName == pkg }
                if (scoped.isEmpty()) return unavailable("TARGET_TREE_UNAVAILABLE")
                ScopedDump.dump(scoped, pkg, maxNodes, maxDepth, maxChars = 56 * 1024)
            } finally {
                @Suppress("DEPRECATION")
                roots.forEach { runCatching { it.recycle() } }
            }
            val pageSame = stillAllowed() && !d.released &&
                VScreenStore.ruleFor(pkg) != VScreenStore.Rule.BLOCK && VScreenShell.topPackage(d.displayId) == pkg && VScreenShell.topActivity(d.displayId) == activityBefore &&
                d.width == w && d.height == h && d.inputWidth == iw && d.inputHeight == ih && d.rotation == rotation && d.bufferTurn == turn
            if (!pageSame) return unavailable("PAGE_CHANGED")
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
            if (!stillAllowed() || d.released ||
                VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK || VScreenShell.topPackage(d.displayId) != pkg ||
                VScreenShell.topActivity(d.displayId) != activityBefore || d.rotation != rotation || d.bufferTurn != turn ||
                d.width != w || d.height != h || d.inputWidth != iw || d.inputHeight != ih) {
                picture?.recycle()
                return unavailable("PAGE_CHANGED")
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
            val snapshotId = tree.getString("snapshot_id")
            tree.put("human_ref", "L-" + snapshotId.substring(0, 8).uppercase()).put("numbering", "编号仅属于本次快照，刷新后请同时报告快照号")
            for (i in 0 until a.length()) a.getJSONObject(i).put("number", a.getJSONObject(i).getInt("id") + 1)
            // Coordinates belong to the captured tree even when a picture cannot be trusted.
            tree.put("wireframe_enabled", iw > 0 && ih > 0 && iw == w && ih == h)
            com.kilon.agentvscreen.host.DevilLog.layout(d.displayId, a.length(), truncated, alignment)
            return LayoutSnapshot(tree, picture).also { rememberSnapshot(it) }
    }
}
