package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.VScreenOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v0.5.0: press duration, junk labels, collect entries, network-down detection. */
@RunWith(RobolectricTestRunner::class)
class VScreenV050Test {

    @Test fun pressBareNumberIsMilliseconds() {
        assertEquals(1_200L, Steps.parse("press 100 200 1200").ms)
        assertEquals(1_500L, Steps.parse("press #3 1500").ms)
        assertEquals(2_000L, Steps.parse("press #3 2s").ms)
        assertEquals(StepTarget.At(100, 200), Steps.parse("press 100 200 1200").target)
    }

    @Test fun junkLabels() {
        assertTrue(isJunkLabel("a3f9c2e1"))
        assertTrue(isJunkLabel("virtualNode"))
        assertFalse(isJunkLabel("12345"))
        assertFalse(isJunkLabel("设置"))
        assertFalse(isJunkLabel("Settings"))
        assertFalse(isJunkLabel("iPhone"))
    }

    @Test fun sameEntryToleratesSplitsAndSpaces() {
        assertTrue(sameEntry("小米 14 Pro 手机 官方旗舰", "小米 14 Pro 手机"))
        assertTrue(sameEntry("今日热点新闻标题一", "今日热点新闻标题一。"))
        assertFalse(sameEntry("北京天气晴", "上海股市收盘"))
        assertFalse(sameEntry("", "x"))
    }

    private fun item(t: String, l: Int, top: Int, r: Int, b: Int) = VScreenOcr.Item(t, Rect(l, top, r, b), emptyList())

    @Test fun entriesJoinWrappedLines() {
        val e = entriesOf(listOf(
            item("第一条标题的前半", 40, 100, 600, 140),
            item("后半部分", 40, 150, 300, 190),
            item("第二条", 40, 400, 300, 440),
        ))
        assertEquals(listOf("第一条标题的前半后半部分", "第二条"), e)
    }
}
