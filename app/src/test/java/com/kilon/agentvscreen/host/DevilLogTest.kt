package com.kilon.agentvscreen.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DevilLogTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun defaultIsOffAndTailIsEmpty() {
        assertFalse(DevilLog.isEnabled())
        DevilLog.record(DevilLog.Event.CREATED, 1L)
        assertEquals("", DevilLog.tail(100))
    }
    @Test fun rotatingKeepsBoundedFiles() {
        val f = tmp.root.resolve("events.log")
        repeat(30) { DevilLog.appendBounded(f, "123456789\n", 20L) }
        assertEquals(20L, f.length())
        assertEquals(20L, tmp.root.resolve("events.1.log").length())
        DevilLog.appendBounded(f, "x".repeat(21), 20L)
        assertEquals(20L, f.length())
    }
    @Test fun payloadHasOnlyFixedEventAndNumbers() {
        assertEquals("42 PARK 1 2\n", DevilLog.encode(42, DevilLog.Event.PARK, longArrayOf(1,2)))
        assertEquals(0L, DevilLog.failure(IllegalStateException("private text")))
    }
}
