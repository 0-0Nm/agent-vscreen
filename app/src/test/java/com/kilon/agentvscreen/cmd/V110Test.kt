package com.kilon.agentvscreen.cmd

import android.content.Context
import android.graphics.Rect
import org.robolectric.RuntimeEnvironment
import com.kilon.agentvscreen.api.EventHub
import com.kilon.agentvscreen.core.VScreenOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 1.1.0: target grammar, near misses, collect blocks, event waiting. */
@RunWith(RobolectricTestRunner::class)
class V110Test {

    @Test fun oneNumberIsAWordOnTheScreen() {
        assertEquals(StepTarget.Words("1"), Steps.parse("tap 1").target)
        assertEquals(StepTarget.Words("7"), Steps.parse("press 7").target)
        assertEquals(StepTarget.At(100, 200), Steps.parse("tap 100 200").target)
        assertEquals(StepTarget.Ref(3), Steps.parse("tap #3").target)
    }

    @Test fun threeNumbersSayHowToWriteIt() {
        val e = runCatching { Steps.parse("tap 1 2 3") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("只写一个数"))
    }

    private fun el(n: Int, label: String, x: Int, y: Int, editable: Boolean = false) =
        El(n, label, Rect(x - 20, y - 10, x + 20, y + 10), if (editable) "可输入" else "可点", true, editable)

    @Test fun nearMissListsCloseWordsAndFields() {
        val els = listOf(el(1, "xbox360播放dvd", 400, 150, editable = true), el(2, "搜索", 900, 150), el(3, "新闻", 100, 600))
        val m = nearMiss(els, "搜索或输入网址")
        assertTrue(m, m.contains("“搜索”(900,150)"))
        assertTrue(m, m.contains("输入框：xbox360播放dvd(400,150)"))
        assertTrue(m, !m.contains("新闻"))
    }

    private fun item(t: String, l: Int, top: Int, r: Int, b: Int) = VScreenOcr.Item(t, Rect(l, top, r, b), emptyList())

    @Test fun longParagraphIsSplitIntoEntries() {
        val rows = (0 until 9).map { i -> item("这是一段很长的回答正文的第${i}行内容，用来测试", 40, 100 + i * 50, 1000, 140 + i * 50) }
        val e = entriesOf(rows)
        assertTrue("got ${e.size}: $e", e.size >= 3)
        assertTrue(e.all { it.length <= 90 })
    }

    @Test fun eventsWaitBlocksUntilTimeoutThenReturnsEmpty() {
        val t0 = System.currentTimeMillis()
        val got = EventHub.take("t-wait", 300L)
        val dt = System.currentTimeMillis() - t0
        assertTrue(got.isEmpty())
        assertTrue("waited $dt ms", dt >= 280L)
    }

    @Test fun eventsWaitWakesOnPost() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        Thread { Thread.sleep(150); EventHub.post(ctx, "t-post", "keep", "盯1", "看到了") }.start()
        val t0 = System.currentTimeMillis()
        val got = EventHub.take("t-post", 5_000L)
        val dt = System.currentTimeMillis() - t0
        assertEquals(1, got.size)
        assertTrue("took $dt ms", dt in 100L..2_000L)
    }
}
