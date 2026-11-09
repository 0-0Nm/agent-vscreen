package com.kilon.agentvscreen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHealthGuardTest {
    @Test fun offDisplayNeverAllowsInjection() {
        assertFalse(VScreenDisplayHealth.actionMayInject(stateKnown = true, displayOn = false))
    }

    @Test fun unknownDisplayStateNeverAllowsInjection() {
        assertFalse(VScreenDisplayHealth.actionMayInject(stateKnown = false, displayOn = true))
    }

    @Test fun onDisplayAllowsInjection() {
        assertTrue(VScreenDisplayHealth.actionMayInject(stateKnown = true, displayOn = true))
    }

    @Test fun freshFrameHasNoStaleWarning() {
        assertNull(VScreenDisplayHealth.cachedFrameNote(hasFrame = true, fresh = true, ageMs = 0))
    }

    @Test fun preActionGateChecksSafetyBeforeWakeGate() {
        assertEquals(VScreenDisplayHealth.PreActionBlock.USER_STOPPED,
            VScreenDisplayHealth.preActionBlock(userStopped = true, takeover = true, generationMatches = false, rereadRequired = true, freshPage = false))
        assertEquals(VScreenDisplayHealth.PreActionBlock.TAKEOVER,
            VScreenDisplayHealth.preActionBlock(userStopped = false, takeover = true, generationMatches = false, rereadRequired = true, freshPage = false))
        assertEquals(VScreenDisplayHealth.PreActionBlock.STALE_GENERATION,
            VScreenDisplayHealth.preActionBlock(userStopped = false, takeover = false, generationMatches = false, rereadRequired = true, freshPage = false))
        assertEquals(VScreenDisplayHealth.PreActionBlock.REREAD,
            VScreenDisplayHealth.preActionBlock(userStopped = false, takeover = false, generationMatches = true, rereadRequired = true, freshPage = false))
    }

    @Test fun freshPageClearsRereadOnlyForTheGate() {
        assertEquals(VScreenDisplayHealth.PreActionBlock.NONE,
            VScreenDisplayHealth.preActionBlock(userStopped = false, takeover = false, generationMatches = true, rereadRequired = true, freshPage = true))
    }

    @Test fun missingFrameIsNotCalledCached() {
        assertEquals(
            "没有实际画面帧：当前不是缓存画面，无法据此判断副屏场景",
            VScreenDisplayHealth.cachedFrameNote(hasFrame = false, fresh = false, ageMs = -1),
        )
    }

    @Test fun timedOutFrameIsExplicitlyCached() {
        assertEquals(
            "取帧未收到新帧（缓存年龄 1200ms）：画面仅作诊断证据，不能证明刚刚响应",
            VScreenDisplayHealth.cachedFrameNote(hasFrame = true, fresh = false, ageMs = 1200),
        )
    }
}
