package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScopedDumpTest {
    private class N(
        override val packageName: String?, override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null, override val resourceId: String? = null,
        override val className: String? = "android.view.View", override val bounds: Rect = Rect(1, 2, 3, 4),
        override val clickable: Boolean = false, override val enabled: Boolean = true,
        override val editable: Boolean = false, override val visible: Boolean = true,
        override val password: Boolean = false, override val children: List<ScopedDumpNode> = emptyList()
    ) : ScopedDumpNode

    @Test fun filtersTargetPackageAndPreservesHierarchy() {
        val child = N("app.target", "child", resourceId = "app:id/child")
        val root = N("app.target", "root", children = listOf(child, N("other.app", "must-not-leak")))
        val json = ScopedDump.dump(listOf(root), "app.target", 200, 20)
        val nodes = json.getJSONArray("nodes")
        assertEquals(2, nodes.length())
        assertEquals(JSONObject.NULL, nodes.getJSONObject(0).get("parentId"))
        assertEquals(0, nodes.getJSONObject(1).getInt("parentId"))
        assertEquals("child", nodes.getJSONObject(1).getString("text"))
    }

    @Test fun passwordRedactsTextAndDescription() {
        val json = ScopedDump.dump(listOf(N("app.target", "secret", "secret hint", password = true)), "app.target", 200, 20)
        val node = json.getJSONArray("nodes").getJSONObject(0)
        assertTrue(node.getBoolean("password"))
        assertTrue(node.isNull("text"))
        assertTrue(node.isNull("contentDescription"))
    }

    @Test fun budgetsAreExplicitAndJsonEscapes() {
        val json = ScopedDump.dump(listOf(N("app.target", "quote \" and \\ slash", children = listOf(N("app.target", "two")))), "app.target", 1, 20)
        assertTrue(json.getBoolean("truncated"))
        assertEquals(1, json.getInt("nodeCount"))
        assertTrue(json.toString().contains("\\\""))
        val depth = ScopedDump.dump(listOf(N("app.target", "root", children = listOf(N("app.target", "child")))), "app.target", 200, 0)
        assertTrue(depth.getBoolean("truncated"))
    }

    @Test fun longTextIsBoundedAndCountMatchesArray() {
        val huge = "x".repeat(200_000)
        val json = ScopedDump.dump(listOf(N("app.target", huge, children = listOf(N("app.target", "child")))), "app.target", 200, 20)
        val nodes = json.getJSONArray("nodes")
        assertEquals(nodes.length(), json.getInt("nodeCount"))
        assertTrue(nodes.getJSONObject(0).getString("text").length <= 2048)
        assertTrue(json.toString(2).length <= ScopedDump.MAX_JSON_CHARS)
    }

    @Test fun budgetRejectDoesNotIncrementCount() {
        val json = ScopedDump.dump(listOf(N("app.target", "x".repeat(200_000))), "app.target", 200, 20, 300)
        assertEquals(0, json.getJSONArray("nodes").length())
        assertEquals(0, json.getInt("nodeCount"))
        assertTrue(json.getBoolean("truncated"))
    }

    @Test fun emptyTreeIsRepresentable() {
        val json = JSONObject().put("available", false).put("reason", "accessibility_unavailable")
        assertFalse(json.getBoolean("available"))
        assertEquals("accessibility_unavailable", json.getString("reason"))
    }
}

