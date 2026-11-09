package com.kilon.agentvscreen.core

import org.junit.Assert.*
import org.junit.Test

class VScreenPowerPolicyTest {
    @Test fun rejectsDefaultDisplay() {
        assertFalse(VScreenPowerPolicy.evaluate(36, 0, 1 shl 11, 0).allowed)
    }
    @Test fun rejectsUnownedDisplay() {
        assertFalse(VScreenPowerPolicy.evaluate(36, 22, 1 shl 11, 21).allowed)
    }
    @Test fun rejectsMissingOwnGroup() {
        assertFalse(VScreenPowerPolicy.evaluate(36, 22, 0, 22).allowed)
    }
    @Test fun rejectsOldApi() {
        assertFalse(VScreenPowerPolicy.evaluate(32, 22, 1 shl 11, 22).allowed)
    }
    @Test fun permitsOnlyOwnedNonDefaultOwnGroup() {
        val d = VScreenPowerPolicy.evaluate(36, 22, 1 shl 11, 22)
        assertTrue(d.allowed)
        assertTrue(d.reason.contains("userActivity"))
    }
}
