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
    private const val MAX_FIELD_CHARS = 2048

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
        // Do not materialize descendants until the parent has passed the output budget.
        override val children: List<ScopedDumpNode>
            get() = (0 until n.childCount).mapNotNull { runCatching { n.getChild(it) }.getOrNull()?.let(ScopedDump::fromAndroid) }
    }

    private fun safeText(value: CharSequence?): String? = value?.toString()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length <= MAX_FIELD_CHARS) it else it.take(MAX_FIELD_CHARS - 1) + "…"
    }

    fun dump(roots: List<ScopedDumpNode>, targetPackage: String, maxNodes: Int, maxDepth: Int, maxChars: Int = MAX_JSON_CHARS): JSONObject {
        val nodes = JSONArray()
        var truncated = false
        fun envelope(): JSONObject = JSONObject().put("available", true).put("targetPackage", targetPackage)
            .put("nodes", nodes).put("nodeCount", nodes.length()).put("maxNodes", maxNodes)
            .put("maxDepth", maxDepth).put("truncated", truncated)
        fun walk(n: ScopedDumpNode, parentId: Int?, depth: Int) {
            if (truncated || n.packageName != targetPackage) return
            if (nodes.length() >= maxNodes || depth > maxDepth) { truncated = true; return }
            val id = nodes.length()
            val item = JSONObject()
                .put("id", id).put("parentId", parentId ?: JSONObject.NULL).put("depth", depth)
                .put("resourceId", n.resourceId ?: JSONObject.NULL).put("class", n.className ?: JSONObject.NULL)
                .put("bounds", JSONObject().put("left", n.bounds.left).put("top", n.bounds.top).put("right", n.bounds.right).put("bottom", n.bounds.bottom))
                .put("clickable", n.clickable).put("enabled", n.enabled).put("editable", n.editable).put("visible", n.visible)
            if (n.password) item.put("password", true).put("text", JSONObject.NULL).put("contentDescription", JSONObject.NULL)
            else {
                safeText(n.text)?.let { item.put("text", it) }
                safeText(n.contentDescription)?.let { item.put("contentDescription", it) }
            }
            // Check the actual pretty-printed envelope before enqueueing: nodeCount must equal nodes.length().
            nodes.put(item)
            val withinBudget = envelope().toString(2).length <= maxChars
            if (!withinBudget) {
                nodes.remove(nodes.length() - 1)
                truncated = true
                return
            }
            if (depth >= maxDepth) { if (n.children.isNotEmpty()) truncated = true; return }
            // Budget is established before asking Android for children.
            for (child in n.children) walk(child, id, depth + 1)
        }
        roots.forEach { walk(it, null, 0) }
        return envelope()
    }
}
