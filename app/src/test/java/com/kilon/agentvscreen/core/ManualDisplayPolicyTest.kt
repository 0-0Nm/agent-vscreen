package com.kilon.agentvscreen.core

import org.junit.Assert.*
import org.junit.Test

class ManualDisplayPolicyTest {
    @Test fun disconnectedDoesNotCreateOrEnableService() {
        assertEquals(ManualDisplayPolicy.Decision.SERVICE_OFF,ManualDisplayPolicy.open(false,true,false))
        assertEquals(ManualDisplayPolicy.Decision.NOT_CONNECTED,ManualDisplayPolicy.open(true,false,false))
    }
    @Test fun existingDisplayIsReusedNotReassignedOrRecreated() {
        assertEquals(ManualDisplayPolicy.Decision.REUSE,ManualDisplayPolicy.open(true,true,true))
        assertTrue(ManualDisplayPolicy.showExisting(true,false))
    }
    @Test fun noDisplayCanBeCreatedWithoutAnyAiTask() {
        assertEquals(ManualDisplayPolicy.Decision.CREATE,ManualDisplayPolicy.open(true,true,false))
    }
    @Test fun manualManagementRequiresItsCurrentGeneration() {
        assertTrue(ManualDisplayPolicy.mayManage(true,true,false,false))
        assertFalse(ManualDisplayPolicy.mayManage(true,false,false,false))
    }
    @Test fun agentDisplayCannotBeClosedOrChangedByManualManager() {
        assertFalse(ManualDisplayPolicy.mayManage(false,true,false,false))
        assertFalse(ManualDisplayPolicy.mayManage(true,true,true,false))
    }
    @Test fun takeoverCannotBeStolen() { assertFalse(ManualDisplayPolicy.mayManage(true,true,false,true)) }
    @Test fun changedOrReleasedScreenCannotBeReportedAsShown() {
        assertFalse(ManualDisplayPolicy.showExisting(false,false));assertFalse(ManualDisplayPolicy.showExisting(true,true))
    }
}
