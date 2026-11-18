package com.kilon.agentvscreen.api
import org.junit.Assert.*
import org.junit.Test
class AgentGuideReviewTest {
    @Test fun allDetailedGuidesDescribeTheCurrentReviewContract() {
        for (text in listOf(AgentGuide.guide(), AgentGuide.skill(), AgentGuide.prompt())) {
            for (word in listOf("layout-propose", "layout-wait", "layout-review", "layout-withdraw", "receipt_only=true", "timed_out=true", "nodes[].number")) assertTrue(word, text.contains(word))
        }
    }
    @Test fun memoryAndOnboardDoNotFallBackToOldDumpInstructions() {
        for (text in listOf(AgentGuide.memory(), AgentGuide.memoryHttp(), AgentGuide.onboard(), AgentGuide.onboardHttp())) {
            assertTrue(text.contains("layout-propose")); assertTrue(text.contains("layout-wait"))
        }
    }
}
