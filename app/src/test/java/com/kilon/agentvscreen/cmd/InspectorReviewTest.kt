package com.kilon.agentvscreen.cmd

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InspectorReviewTest {
    @Test fun descriptionAndExpiry() {
        assertTrue(ReviewGate.validDescription("下移 12 dp"))
        listOf(" ", "a\nb", "a\u202Eb", "x".repeat(241)).forEach { assertFalse(ReviewGate.validDescription(it)) }
        assertTrue(ReviewGate.within(100, 119, 20))
        assertFalse(ReviewGate.within(100, 120, 20))
        assertFalse(ReviewGate.within(100, 99, 20))
    }
    private fun page() = JSONObject("""{"available":true,"wireframe_enabled":true,"activity":"test/.Page","targetPackage":"test","display_id":4,"generation":2,"nodes":[{"number":1,"visible":true,"bounds":{"left":0,"top":0,"right":6,"bottom":7}}]}""")
    @Test fun changedPageGeometryOrNodesInvalidate() {
        val orig = LayoutInspector.reviewFingerprint(page(), 1)
        assertNotNull(orig)
        assertEquals(orig, LayoutInspector.reviewFingerprint(page().put("frame_version", 9), 1))
        assertNotEquals(orig, LayoutInspector.reviewFingerprint(page().put("activity", "test/.Other"), 1))
        assertNotEquals(orig, LayoutInspector.reviewFingerprint(page().put("rotation", 90), 1))
        val p = page(); p.getJSONArray("nodes").getJSONObject(0).getJSONObject("bounds").put("right",8)
        assertNotEquals(orig, LayoutInspector.reviewFingerprint(p, 1))
    }
    @Test fun refuseUnsafeOrUnselectableSnapshot() {
        assertNull(LayoutInspector.reviewFingerprint(page().put("contains_password", true), 1))
        assertNull(LayoutInspector.reviewFingerprint(page().put("truncated", true), 1))
        assertNull(LayoutInspector.reviewFingerprint(page().put("nodes", JSONArray()), 1))
        assertNull(LayoutInspector.reviewFingerprint(page(), 5))
    }
}
