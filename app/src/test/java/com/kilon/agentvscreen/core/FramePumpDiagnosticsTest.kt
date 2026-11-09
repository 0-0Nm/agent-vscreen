package com.kilon.agentvscreen.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePumpDiagnosticsTest {
    @Test fun normalStaticNoFrameIsNotAFreezeVerdict() {
        val baseline = VScreenDisplayHealth.PublishedFrame(version = 0, convertedAtMs = 0)
        assertEquals(
            VScreenDisplayHealth.PublishedFreshness.NO_FRAME,
            VScreenDisplayHealth.publishedFreshness(
                receivedAtMs = 0, convertedAtMs = 0, version = 0,
                baseline = baseline, notBeforeMs = 100,
            ),
        )
        assertFalse(VScreenDisplayHealth.unparkRecovered(baseline, baseline, 100))
    }

    @Test fun acquireFailureIsDifferentFromNormalNull() {
        val baseline = VScreenDisplayHealth.PublishedFrame(4, 900)
        val nullAfterCallback = VScreenDisplayHealth.publishedFreshness(
            receivedAtMs = 0, convertedAtMs = 900, version = 4,
            baseline = baseline, notBeforeMs = 1000,
        )
        val failedAcquire = VScreenDisplayHealth.publishedFreshness(
            receivedAtMs = 0, convertedAtMs = 900, version = 4,
            baseline = baseline, notBeforeMs = 1000,
        )
        assertEquals(VScreenDisplayHealth.PublishedFreshness.NO_FRAME, nullAfterCallback)
        assertEquals(nullAfterCallback, failedAcquire)
    }

    @Test fun receivedButNotPublishedDoesNotRecover() {
        val baseline = VScreenDisplayHealth.PublishedFrame(7, 1200)
        val current = VScreenDisplayHealth.PublishedFrame(7, 1200)
        assertEquals(
            VScreenDisplayHealth.PublishedFreshness.RECEIVED_ONLY,
            VScreenDisplayHealth.publishedFreshness(
                receivedAtMs = 1500, convertedAtMs = 1200, version = 7,
                baseline = baseline, notBeforeMs = 1500,
            ),
        )
        assertFalse(VScreenDisplayHealth.unparkRecovered(current, baseline, 1500))
    }

    @Test fun newerPublishedFrameIsTheOnlyRecoverySignal() {
        val baseline = VScreenDisplayHealth.PublishedFrame(7, 1200)
        val current = VScreenDisplayHealth.PublishedFrame(8, 1510)
        assertTrue(VScreenDisplayHealth.unparkRecovered(current, baseline, 1500))
        assertEquals(
            VScreenDisplayHealth.PublishedFreshness.PUBLISHED,
            VScreenDisplayHealth.publishedFreshness(
                receivedAtMs = 1500, convertedAtMs = 1510, version = 8,
                baseline = baseline, notBeforeMs = 1500,
            ),
        )
    }
}
