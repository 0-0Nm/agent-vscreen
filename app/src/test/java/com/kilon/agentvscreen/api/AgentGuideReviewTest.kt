package com.kilon.agentvscreen.api
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import android.content.SharedPreferences
import java.lang.reflect.Proxy
class AgentGuideReviewTest {
    private val prefsField = ApiConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private var originalPrefs: Any? = null

    @Before fun installIsolatedPreferences() {
        originalPrefs = prefsField.get(null)
        val fixture = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "getInt" -> args!![1]
                "getString" -> if (args!![0] == "token") "fixture-only" else args[1]
                "toString" -> "AgentGuideReviewPreferencesFixture"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw AssertionError("Unexpected preferences access: ${method.name}")
            }
        } as SharedPreferences
        prefsField.set(null, fixture)
    }

    @After fun restorePreferences() { prefsField.set(null, originalPrefs) }

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
