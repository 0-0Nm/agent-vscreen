package com.kilon.agentvscreen.api

import com.kilon.agentvscreen.cmd.ShotStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiParsingTest {
    @Test fun splitKeepsQuotedSteps() {
        assertEquals(listOf("act", "tap 搜索; type 耳机; key enter until text:综合", "--watch", "2s"),
            ApiServer.splitCommand("act \"tap 搜索; type 耳机; key enter until text:综合\" --watch 2s"))
        assertEquals(listOf("act", "type \"a b\""), ApiServer.splitCommand("vscreen act 'type \"a b\"'"))
        assertEquals(listOf("note", "it's"), ApiServer.splitCommand("note \"it's\""))
        assertEquals(listOf("act", "type x\"y"), ApiServer.splitCommand("act \"type x\\\"y\""))
        assertEquals(listOf("status"), ApiServer.splitCommand("  status  "))
        assertEquals(listOf("act", ""), ApiServer.splitCommand("act \"\""))
    }

    @Test fun formRepeatsAndDecodes() {
        val f = ApiServer.parseForm("a=act&a=tap%20%E6%90%9C%E7%B4%A2%3B%20type%20x&session=s1")
        assertEquals(listOf("act", "tap 搜索; type x"), f["a"])
        assertEquals(listOf("s1"), f["session"])
    }

    @Test fun shotRefsFound() {
        val t = "截图 ${ShotStore.REF}vscreen_now_1_2.jpg\n拼图 ${ShotStore.REF}vscreen_sheet_3_4.jpg 和 ${ShotStore.REF}vscreen_now_1_2.jpg"
        assertEquals(listOf("vscreen_now_1_2.jpg", "vscreen_sheet_3_4.jpg"), ShotStore.refs(t))
        assertEquals(false, ShotStore.NAME.matches("../etc/passwd"))
    }
}
