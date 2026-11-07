package com.kilon.agentvscreen.core

import org.robolectric.RuntimeEnvironment
import com.kilon.agentvscreen.host.ShizukuManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [vscreen] The dumpsys parsers the background screen relies on, fed with canned output in the
 * AOSP 14 format (the shell runner seam replaces Shizuku). When a phone's output breaks one of
 * these, add that output here as a new case before fixing the parser.
 */
@RunWith(RobolectricTestRunner::class)
class VScreenParsersTest {

    private val activities = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{a1b2c3 #36165 type=standard A=10234:com.tencent.mm U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    mResumedActivity: ActivityRecord{d4e5f6 u0 com.tencent.mm/.ui.LauncherUI t36165}
    * Hist  #0: ActivityRecord{d4e5f6 u0 com.tencent.mm/.ui.LauncherUI t36165}
      Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.tencent.mm/.ui.LauncherUI }
  * Task{b2c3d4 #36170 type=standard A=10301:com.kilon.minis.vscreen U=0 visible=false visibleRequested=false mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivity t36170}
  * Task{c3d4e5 #1 type=home U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    * Task{d4e5f6 #2 type=home A=10101:com.hihonor.android.launcher U=0 visible=false mode=fullscreen translucent=false sz=1}
  ResumedActivity: ActivityRecord{d4e5f6 u0 com.tencent.mm/.ui.LauncherUI t36165}
Display #4 (activities from top to bottom):
  * Task{f00baa #36200 type=standard A=10455:com.taobao.taobao U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=2}
    topResumedActivity=ActivityRecord{abc123 u0 com.taobao.taobao/com.taobao.search.SearchActivity t36200}
    * Hist  #1: ActivityRecord{abc123 u0 com.taobao.taobao/com.taobao.search.SearchActivity t36200}
      Intent { act=android.intent.action.VIEW dat=taobao://s.taobao.com/search?q=earphone flg=0x10000000 cmp=com.taobao.taobao/com.taobao.search.SearchActivity (has extras) }
    * Hist  #0: ActivityRecord{def456 u0 com.taobao.taobao/com.taobao.tao.welcome.Welcome t36200}
      Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 cmp=com.taobao.taobao/com.taobao.tao.welcome.Welcome }
""".trimIndent()

    private val emptyLastDisplay = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{b2c3d4 #36170 type=standard A=10301:com.kilon.minis.vscreen U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    topResumedActivity=ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivity t36170}
    * Hist  #0: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivity t36170}
Display #7 (activities from top to bottom):

  ResumedActivity: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivity t36170}
  mLastFocusedRootTask=Task{b2c3d4 #36170 type=standard A=10301:com.kilon.minis.vscreen U=0 visible=true}
""".trimIndent()

    private var activityDump: String? = null

    private val packageDump = """
Activity Resolver Table:
  Schemes:
      taobao:
        5f3c1a2 com.taobao.taobao/com.taobao.tao.welcome.Welcome filter 7a9b0c1
          Action: "android.intent.action.VIEW"
          Category: "android.intent.category.BROWSABLE"
          Scheme: "taobao"
          Authority: "s.taobao.com": -1
          Authority: "item.taobao.com": -1
      https:
        5f3c1a2 com.taobao.taobao/com.taobao.tao.welcome.Welcome filter 1b2c3d4
          Scheme: "https"
          Authority: "m.taobao.com": -1

  Non-Data Actions:
      android.intent.action.MAIN:
        5f3c1a2 com.taobao.taobao/com.taobao.tao.welcome.Welcome filter 9e8d7c6

Receiver Resolver Table:
  Schemes:
      notareceiverscheme:
        1234567 com.taobao.taobao/.Receiver filter 1
""".trimIndent()

    private var saved: ((Array<String>, Long) -> ShizukuManager.ProcessResult)? = null

    @Before fun setUp() {
        saved = VScreenShell.runner
        VScreenShell.runner = { argv, _ ->
            val out = when {
                argv.joinToString(" ").contains("dumpsys activity activities") -> activityDump ?: activities
                argv.size >= 2 && argv[0] == "dumpsys" && argv[1] == "package" -> packageDump
                else -> ""
            }
            ShizukuManager.ProcessResult(0, out, "")
        }
    }

    @After fun tearDown() { saved?.let { VScreenShell.runner = it } }

    @Test fun topActivityPerDisplay() {
        assertEquals("com.taobao.taobao/com.taobao.search.SearchActivity", VScreenShell.topActivity(4))
        assertEquals("com.tencent.mm", VScreenShell.topPackage(0))
        assertNull(VScreenShell.topActivity(7))
    }

    @Test fun emptyScreenNeverReportsTheMainScreensApp() {
        activityDump = emptyLastDisplay
        try {
            assertEquals("com.kilon.minis.vscreen", VScreenShell.topPackage(0))
            // Before v26 this returned Minis, and releasing the screen force-stopped Minis itself.
            assertNull(VScreenShell.topActivity(7))
            assertTrue(VScreenHandoff.tasksOn(7).isEmpty())
        } finally { activityDump = null }
    }

    /** v26 field dump shape: a global tail (ResumedActivity, the main screen's Minis task header) printed inside the last display. */
    private val globalTailInLastDisplay = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{b2c3d4 #36219 type=standard A=10301:com.kilon.minis.vscreen U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    topResumedActivity=ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivityIconAuto t36219}
    * Hist  #0: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivityIconAuto t36219}
Display #10 (activities from top to bottom):
  * Task{c0ffee #36230 type=standard A=10222:com.android.chrome U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{aa11bb u0 com.android.chrome/com.google.android.apps.chrome.Main t36230}
  ResumedActivity: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivityIconAuto t36219}
  RootWindowContainer:
  * Task{b2c3d4 #36219 type=standard A=10301:com.kilon.minis.vscreen U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * Hist  #0: ActivityRecord{e5f6a7 u0 com.kilon.minis.vscreen/com.openminis.app.MainActivityIconAuto t36219}
""".trimIndent()

    @Test fun mainScreenTasksNeverLeakIntoTheLastDisplay() {
        activityDump = globalTailInLastDisplay
        try {
            assertEquals("com.android.chrome/com.google.android.apps.chrome.Main", VScreenShell.topActivity(10))
            assertEquals(listOf(36230), VScreenHandoff.tasksOn(10).map { it.taskId })
            // Before v27 this found Minis' main task (#36219) "on" display 10 and release removed it.
            assertNull(VScreenShell.taskIdOn(10, "com.kilon.minis.vscreen"))
            assertEquals(36219, VScreenShell.taskIdOn(0, "com.kilon.minis.vscreen"))
        } finally { activityDump = null }
    }

    @Test fun emptyDisplayWithGlobalTailHasNoApp() {
        activityDump = globalTailInLastDisplay.replace(Regex("(?s)Display #10.*?  ResumedActivity"), "Display #10 (activities from top to bottom):\n  ResumedActivity")
        try {
            assertNull(VScreenShell.topActivity(10))
            assertTrue(VScreenHandoff.tasksOn(10).isEmpty())
        } finally { activityDump = null }
    }

    @Test fun protectedPackagesAreNeverForceStopped() {
        VScreenShell.protectedPackages = setOf("com.kilon.minis.vscreen", "com.android.systemui")
        val o = VScreenShell.stop(7, "com.kilon.minis.vscreen")
        assertFalse(o.ok)
        assertEquals("PROTECTED_APP", o.code)
    }

    @Test fun taskIdOnDisplay() {
        assertEquals(36200, VScreenShell.taskIdOn(4, "com.taobao.taobao"))
        assertEquals(36165, VScreenShell.taskIdOn(0, "com.tencent.mm"))
        assertNull(VScreenShell.taskIdOn(4, "com.tencent.mm"))
    }

    @Test fun handoffTasksSkipHomeAndKeepOrder() {
        val t = VScreenHandoff.tasksOn(0)
        assertEquals(listOf(36165, 36170), t.map { it.taskId })
        assertEquals("com.tencent.mm", t[0].pkg)
        assertTrue(t[0].visible)
        assertFalse(t[1].visible)
    }

}
