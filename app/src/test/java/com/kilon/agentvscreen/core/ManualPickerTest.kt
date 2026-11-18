package com.kilon.agentvscreen.core

import org.junit.Assert.*
import org.junit.Test

class ManualPickerTest {
    @Test fun pickerIsBoundToAnEmptyOrExactScreen() {
        assertTrue(ManualDisplayPolicy.matchesPicker(null, 7, null, 7))
        assertFalse(ManualDisplayPolicy.matchesPicker(null, 7, 15, 8))
        assertTrue(ManualDisplayPolicy.matchesPicker(15, 8, 15, 8))
        assertFalse(ManualDisplayPolicy.matchesPicker(15, 8, 15, 9))
        assertFalse(ManualDisplayPolicy.matchesPicker(15, 8, 16, 8))
        assertFalse(ManualDisplayPolicy.matchesPicker(15, 8, null, 8))
    }
    @Test fun noImplicitTakeover() {
        assertTrue(ManualDisplayPolicy.maySwitch(true, "local", null))
        assertTrue(ManualDisplayPolicy.maySwitch(false, "agent", "agent"))
        assertFalse(ManualDisplayPolicy.maySwitch(false, "agent", null))
        assertFalse(ManualDisplayPolicy.maySwitch(false, "agent", "other"))
        assertFalse(ManualDisplayPolicy.maySwitch(false, null, null))
    }
}
