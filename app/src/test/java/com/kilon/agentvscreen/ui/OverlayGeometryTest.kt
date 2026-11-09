package com.kilon.agentvscreen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun reproducedInvertedRangeIsFiniteAndVisible() {
        val r = OverlayGeometry.place(240f, 4.600006f, 200f, 118.799995f, 36f, 48f, 12f, 9f)
        assertFalse(r.deferred)
        assertTrue(r.x.isFinite() && r.y.isFinite())
        assertEquals(0f, r.x, 0.001f)
        assertEquals(0f, r.y, 0.001f)
    }

    @Test fun normalPortraitAndLandscapeKeepInsets() {
        val p = OverlayGeometry.place(1080f, 2400f, 520f, 900f, 108f, 144f, 700f, 1800f)
        assertEquals(560f, p.x, 0.001f)
        assertEquals(1356f, p.y, 0.001f)
        val l = OverlayGeometry.place(2400f, 1080f, 900f, 520f, 36f, 48f, 1700f, 400f)
        assertEquals(1500f, l.x, 0.001f)
        assertEquals(400f, l.y, 0.001f)
    }

    @Test fun zeroAndNaNGeometryDefersInsteadOfThrowing() {
        assertTrue(OverlayGeometry.place(0f, 0f, 100f, 100f, 0f, 0f, 0f, 0f).deferred)
        assertTrue(OverlayGeometry.place(Float.NaN, 100f, 100f, 100f, 0f, 0f, 0f, 0f).deferred)
    }

    @Test fun oversizedInsetsCollapseToVisibleFallback() {
        val r = OverlayGeometry.place(300f, 300f, 120f, 120f, 500f, 500f, 200f, 200f)
        assertTrue(r.x in 0f..180f)
        assertTrue(r.y in 0f..180f)
    }
}
