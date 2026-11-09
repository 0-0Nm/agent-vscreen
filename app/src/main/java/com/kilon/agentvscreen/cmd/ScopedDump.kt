package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Small, deterministic JSON dump model; no Android tree access is needed by the serializer tests. */
internal interface ScopedDumpNode {
    val packageName: String?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val resourceId: String?
    val className: String?
    val bounds: Rect
    val clickable: Boolean
    val enabled: Boolean
    val editable: Boolean
    val visible: Boolean
    val password: Boolean
    val children: List<ScopedDumpNode>
}

internal object ScopedDump {
    const val DEFAULT_MAX_NODES = 200
    const val DEFAULT_MAX_DEPTH = 20
    const val MAX_JSON_CHARS = 64 * 1024

    fun fromAndroid(n: AccessibilityNodeInfo): ScopedDumpNode = object : ScopedDumpNode {
        override val packageName = n.packageName?.toString()
        override val text = n.text
        override val contentDescription = n.contentDescription
        override val resourceId = n.viewIdResourceName
        override val className = n.className?.toString()
        override val bounds: Rect get() = Rect().also { n.getBoundsInScreen(it) }
        override val clickable = n.isClickable
        override val enabled = n.isEnabled
        override val editable = n.isEditable
        override val visible = n.isVisibleToUser
        override val password = n.isPassword
        override val children: List<ScopedDumpNode>
            get() = (0 until n.childCount).mapNotNull { runCatching { n.getChild(it) }.getOrNull()?.let(ScopedDump::fromAndroid) }
    }

    fun dump(roots: List<ScopedDumpNode>, targetPackage: String, maxNodes: Int, maxDepth: Int, maxChars: Int = MAX_JSON_CHARS): JSONObject {
        val nodes = JSONArray()
        var count = 0
        var truncated = false
        fun walk(n: ScopedDumpNode, parentId: Int?, depth: Int) {
            if (truncated || n.packageName != targetPackage) return
            if (count >= maxNodes || depth > maxDepth) { truncated = true; return }
            val id = count++
            val item = JSONObject()
                .put("id", id)
                .put("parentId", parentId ?: JSONObject.NULL)
                .put("depth", depth)
                .put("resourceId", n.resourceId ?: JSONObject.NULL)
                .put("class", n.className ?: JSONObject.NULL)
                .put("bounds", JSONObject().put("left", n.bounds.left).put("top", n.bounds.top).put("right", n.bounds.right).put("bottom", n.bounds.bottom))
                .put("clickable", n.clickable).put("enabled", n.enabled).put("editable", n.editable).put("visible", n.visible)
            if (n.password) {
                item.put("password", true)
                item.put("text", JSONObject.NULL)
                item.put("contentDescription", JSONObject.NULL)
            } else {
                n.text?.toString()?.takeIf { it.isNotEmpty() }?.let { item.put("text", it) }
                n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { item.put("contentDescription", it) }
            }
            val projected = JSONArray(nodes.toString()).put(item)
            val candidate = JSONObject().put("available", true).put("targetPackage", targetPackage).put("nodes", projected).put("truncated", false).toString()
            if (candidate.length > maxChars) { truncated = true; return }
            nodes.put(item)
            if (depth >= maxDepth) { if (n.children.isNotEmpty()) truncated = true; return }
            for (child in n.children) walk(child, id, depth + 1)
        }
        roots.forEach { walk(it, null, 0) }
        return JSONObject().put("available", true).put("targetPackage", targetPackage).put("nodes", nodes)
            .put("nodeCount", count).put("maxNodes", maxNodes).put("maxDepth", maxDepth).put("truncated", truncated)
    }
}
