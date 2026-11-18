package com.kilon.agentvscreen.cmd

import org.junit.Assert.assertEquals
import org.junit.Test

class InspectorWaitTest {
    @Test fun pendingUntilDeadline() {
        assertEquals("PENDING", ReviewGate.pendingStatus(10L, 300_009L, 300_000L, true))
        assertEquals("EXPIRED", ReviewGate.pendingStatus(10L, 300_010L, 300_000L, true))
    }
    @Test fun changedOwnerOrDisplayInvalidatesBeforeExpiry() {
        assertEquals("STALE", ReviewGate.pendingStatus(10L, 11L, 300_000L, false))
    }
}
