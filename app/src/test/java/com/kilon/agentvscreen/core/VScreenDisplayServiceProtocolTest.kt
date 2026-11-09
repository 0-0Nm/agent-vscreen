package com.kilon.agentvscreen.core

import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VScreenDisplayServiceProtocolTest {
    @Test fun transactionConstantsAreUniqueAndRotationStaysAtOriginalSlot() {
        val txns = listOf(VScreenDisplayService.CREATE, VScreenDisplayService.RELEASE, VScreenDisplayService.PING,
            VScreenDisplayService.INJECT_KEY, VScreenDisplayService.INJECT_DRAG, VScreenDisplayService.REMOVE_TASK,
            VScreenDisplayService.LIST, VScreenDisplayService.SET_SURFACE, VScreenDisplayService.TOUCH,
            VScreenDisplayService.ROTATION, VScreenDisplayService.POINTER, VScreenDisplayService.APS,
            VScreenDisplayService.PARK, VScreenDisplayService.MOVE_TASK, VScreenDisplayService.LIST_TASKS,
            VScreenDisplayService.FOCUSED, VScreenDisplayService.FOCUS_TASK, VScreenDisplayService.USER_ACTIVITY,
            VScreenDisplayService.WAKE_UP_WITH_DISPLAY)
        assertEquals(txns.size, txns.toSet().size)
        txns.forEach { assertTrue("Legal transaction must pass the production gate: $it", VScreenDisplayService.isRoutedTransaction(it)) }
        assertEquals(Binder.FIRST_CALL_TRANSACTION + 9, VScreenDisplayService.ROTATION)
        assertEquals(Binder.FIRST_CALL_TRANSACTION + 17, VScreenDisplayService.USER_ACTIVITY)
        assertEquals(Binder.FIRST_CALL_TRANSACTION + 18, VScreenDisplayService.WAKE_UP_WITH_DISPLAY)
        assertNotEquals(VScreenDisplayService.ROTATION, VScreenDisplayService.USER_ACTIVITY)
        assertEquals(10, VScreenDisplayService.PROTOCOL)
    }

    @Test fun sparsePowerTransactionsUseTheSameRouteGateAsOnTransact() {
        // Regression proof: the old contiguous range rejected both legal calls.
        assertFalse(VScreenDisplayService.USER_ACTIVITY in VScreenDisplayService.CREATE..VScreenDisplayService.FOCUS_TASK)
        assertFalse(VScreenDisplayService.WAKE_UP_WITH_DISPLAY in VScreenDisplayService.CREATE..VScreenDisplayService.FOCUS_TASK)

        assertTrue(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.USER_ACTIVITY))
        assertTrue(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.WAKE_UP_WITH_DISPLAY))
        assertTrue(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.ROTATION))
        assertTrue(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.FOCUS_TASK))
    }

    @Test fun routeGateRejectsUnknownAndKeepsLifecycleTransactionSeparate() {
        assertFalse(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.WAKE_UP_WITH_DISPLAY + 1))
        assertFalse(VScreenDisplayService.isRoutedTransaction(VScreenDisplayService.DESTROY_CODE))
        assertFalse(VScreenDisplayService.isRoutedTransaction(Binder.FIRST_CALL_TRANSACTION - 1))
    }
    @Test fun powerPolicyRejectsUnsafeTargetsWithoutAuthorizingFallback() {
        assertFalse(VScreenPowerPolicy.evaluate(32, 2, 1 shl 11, 2).allowed)
        assertFalse(VScreenPowerPolicy.evaluate(33, 0, 1 shl 11, 0).allowed)
        assertFalse(VScreenPowerPolicy.evaluate(33, 2, 1 shl 11, 3).allowed)
        assertFalse(VScreenPowerPolicy.evaluate(33, 2, 0, 2).allowed)
        assertTrue(VScreenPowerPolicy.evaluate(33, 2, 1 shl 11, 2).allowed)
    }

}
