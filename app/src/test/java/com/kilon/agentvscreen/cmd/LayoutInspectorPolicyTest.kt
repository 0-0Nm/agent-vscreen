package com.kilon.agentvscreen.cmd

import org.junit.Assert.*
import org.junit.Test

class LayoutInspectorPolicyTest {
    private fun reason(page: Boolean = true, frame: Boolean = true, password: Boolean = false, truncated: Boolean = false,
                       age: Long = 100, stable: Boolean = true, dims: Boolean = true, on: Boolean = true) =
        LayoutInspectorPolicy.alignment(page, frame, password, truncated, age, stable, dims, on)
    @Test fun changedPageDiscardsAlignment() { assertEquals("PAGE_CHANGED", reason(page = false)) }
    @Test fun oldAndMissingFramesDoNotGetBoxes() {
        assertEquals("CACHED_FRAME", reason(age = 2001)); assertEquals("NO_FRAME", reason(frame = false))
        assertEquals("CACHED_FRAME", reason(age = -1))
    }
    @Test fun passwordAndTruncationSuppressPictures() {
        assertEquals("SENSITIVE_CONTENT", reason(password = true)); assertEquals("TREE_TRUNCATED", reason(truncated = true))
    }
    @Test fun screenshotsAreSuppressedForPasswordTruncationAndForeignWindows() {
        assertFalse(LayoutInspectorPolicy.allowScreenshot(true,false,true))
        assertFalse(LayoutInspectorPolicy.allowScreenshot(false,true,true))
        assertFalse(LayoutInspectorPolicy.allowScreenshot(false,false,false))
        assertTrue(LayoutInspectorPolicy.allowScreenshot(false,false,true))
    }
    @Test fun coordinateAndFrameChangesDisableAlignment() {
        assertEquals("COORDINATE_SPACE_MISMATCH", reason(dims = false))
        assertEquals("FRAME_CHANGED_DURING_CAPTURE", reason(stable = false))
        assertEquals("DISPLAY_NOT_ON", reason(on = false))
    }
    @Test fun stationarySnapshotIsOnlyBestEffortNotAtomic() { assertEquals("BEST_EFFORT", reason()) }
    private val boxes = listOf(
        LayoutInspectorPolicy.Box(0,null,0,0,0,100,200,true,"root"),
        LayoutInspectorPolicy.Box(1,0,1,10,20,80,90,true,"container"),
        LayoutInspectorPolicy.Box(2,1,2,20,30,40,50,true,"app:id/answer TextView answer"),
        LayoutInspectorPolicy.Box(3,0,1,20,30,30,40,false,"hidden"))
    @Test fun hitSelectsSmallestVisibleNodeWithoutInput() {
        assertEquals(2,LayoutInspectorPolicy.hit(boxes,25f,35f)); assertNull(LayoutInspectorPolicy.hit(boxes,Float.NaN,1f))
        assertNull(LayoutInspectorPolicy.hit(boxes,-1f,0f)); assertNull(LayoutInspectorPolicy.hit(boxes,100f,200f))
    }
    @Test fun collapseAndSearchPreserveParentChain() {
        assertEquals(listOf(0,1,3),LayoutInspectorPolicy.visibleRows(boxes,setOf(1),"").map { it.id })
        assertEquals(listOf(0,1,2),LayoutInspectorPolicy.visibleRows(boxes,setOf(0),"ANSWER").map { it.id })
    }
    @Test fun malformedParentCycleCannotLoop() {
        val cycle=listOf(LayoutInspectorPolicy.Box(1,2,1,0,0,10,10,true,"match"),LayoutInspectorPolicy.Box(2,1,1,0,0,10,10,true,"parent"))
        assertEquals(2,LayoutInspectorPolicy.visibleRows(cycle,emptySet(),"match").size)
    }
}
