package com.kilon.agentvscreen.ui
import org.junit.Assert.*
import org.junit.Test
class InspectorLoadGateTest {
    @Test fun olderCompletionCannotUnlockNewerLoad() {
        val g = InspectorLoadGate(); val a = g.begin(); val b = g.begin()
        assertFalse(g.finish(a)); assertTrue(g.isCurrent(b)); assertTrue(g.finish(b)); assertFalse(g.isCurrent(b))
    }
    @Test fun currentCompletionAlwaysReleasesOwnership() {
        val g = InspectorLoadGate(); val a = g.begin()
        assertTrue(g.finish(a)); assertFalse(g.finish(a)); assertTrue(g.finish(g.begin()))
    }
    @Test fun lifecycleInvalidationRejectsLateResults() {
        val g = InspectorLoadGate(); val a = g.begin(); g.invalidate()
        assertFalse(g.isCurrent(a)); val b = g.begin(); assertFalse(g.finish(a)); assertTrue(g.isCurrent(b))
    }
}
