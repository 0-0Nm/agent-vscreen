package com.kilon.agentvscreen.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAttentionPolicyTest {
    private fun snapshot(
        pending: Boolean = true,
        matches: Boolean = true,
        visible: Boolean = true,
        fullscreen: Boolean = false,
        docked: Boolean = true,
        gesture: Boolean = false,
        reminded: Boolean = false,
        id: String? = "proposal-1",
    ) = ReviewAttentionSnapshot(id, pending, matches, visible, fullscreen, docked, gesture, reminded)

    @Test fun expandsOnlyForNewPendingProposalOnVisibleDockedTab() {
        assertTrue(ReviewAttentionPolicy.shouldAutoExpand(snapshot()))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(reminded = true)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(id = null)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(pending = false)))
    }

    @Test fun safetyGatesBlockExpansion() {
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(matches = false)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(visible = false)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(fullscreen = true)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(docked = false)))
        assertFalse(ReviewAttentionPolicy.shouldAutoExpand(snapshot(gesture = true)))
    }
}
