package com.kilon.agentvscreen.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/**
 * Is this process really kept alive while it sits in the background?
 *
 * Not a Minis question: inside Minis the screen lived in the agent app itself, which the user keeps in front. Here the
 * agent is in front and we are always in the background, so everything depends on two things the system may refuse:
 *  - the foreground service (a background start of it can be rejected; then nothing keeps the process running);
 *  - the vendor's own background manager (MagicOS / EMUI / MIUI …) may still freeze us. A frozen process keeps its
 *    listening socket, so the kernel accepts the agent's connection and the request just hangs until the user opens
 *    the app — the "a command takes tens of seconds / only finishes when I switch to the app" symptom.
 *
 * Freezes are measured, not guessed: a 1 s ticker compares [SystemClock.uptimeMillis] gaps (uptime stops in deep
 * sleep, so a sleeping phone does not count; a frozen process with the CPU running does).
 */
object Liveness {
    @Volatile var foreground = false; private set
    @Volatile var foregroundError: String? = null; private set
    @Volatile var freezes = 0; private set
    @Volatile var lastFreezeAt = 0L; private set        // elapsedRealtime
    @Volatile var lastFreezeMs = 0L; private set
    @Volatile var longestFreezeMs = 0L; private set
    @Volatile var lastFreezeWall = 0L; private set     // currentTimeMillis
    private var ticker: Thread? = null

    fun foregroundOk() { foreground = true; foregroundError = null }
    fun foregroundFailed(why: String) {
        foreground = false; foregroundError = why
        AppLogger.warning("Liveness", "not a foreground service: $why")
    }
    fun serviceGone() { foreground = false }

    @Synchronized fun start() {
        if (ticker?.isAlive == true) return
        ticker = Thread({
            var last = SystemClock.uptimeMillis()
            while (true) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                val gap = now - last - 1_000
                last = now
                if (gap >= 3_000) {
                    freezes++; lastFreezeMs = gap; lastFreezeAt = SystemClock.elapsedRealtime(); lastFreezeWall = System.currentTimeMillis()
                    if (gap > longestFreezeMs) longestFreezeMs = gap
                    AppLogger.warning("Liveness", "process was frozen for ${gap / 1000}s (system / vendor background manager)")
                }
            }
        }, "vscreen-liveness").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.also { it.start() }
    }

    /** A freeze within the last 10 minutes. */
    fun recentlyFrozen(): Boolean = freezes > 0 && SystemClock.elapsedRealtime() - lastFreezeAt < 10 * 60_000L

    /** What the user should know, or null when all is well. */
    fun problem(): String? = when {
        !HostService.isAlive -> null
        !foreground -> "服务没能成为前台服务（${foregroundError ?: "原因未知"}），切到后台后系统会冻结或杀掉它。打开一次本应用会自动补上。"
        recentlyFrozen() -> "系统在后台冻结过本应用 $freezes 次（最近一次 ${lastFreezeMs / 1000} 秒）：冻结期间 agent 的命令会一直等，直到你打开本应用。" +
            "请在系统的后台管理里把 Agent-VScreen 设为“允许后台活动”。"
        else -> null
    }

    fun json(): JSONObject = JSONObject()
        .put("foreground_service", foreground)
        .put("foreground_error", foregroundError ?: JSONObject.NULL)
        .put("frozen_count", freezes)
        .put("last_frozen_s", if (freezes > 0) lastFreezeMs / 1000 else JSONObject.NULL)
        .put("last_frozen_ago_s", if (freezes > 0) (SystemClock.elapsedRealtime() - lastFreezeAt) / 1000 else JSONObject.NULL)
        .put("longest_frozen_s", if (freezes > 0) longestFreezeMs / 1000 else JSONObject.NULL)
        .put("keep_alive_window", Presence.up)
        .put("pid", android.os.Process.myPid())

    /**
     * Open the vendor page where an app is allowed to keep running in the background (launch manager / autostart);
     * fall back to the app's own settings page. Returns what was opened.
     */
    private val PAGES get() = listOf(
            "荣耀 应用启动管理" to ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            "华为 应用启动管理" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            "小米 自启动管理" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            "OPPO 自启动" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            "vivo 后台高耗电" to ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
        )

    /** This phone's own background manager page (Honor / Huawei / Xiaomi / OPPO / vivo …), or null on stock Android. */
    fun vendorPage(ctx: Context): String? = PAGES.firstOrNull { (_, cn) ->
        runCatching { ctx.packageManager.getActivityInfo(cn, 0); true }.getOrDefault(false)
    }?.first

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)

    /**
     * The vendor page cannot be read back, so the setup item counts as done once the user went there — and stops
     * counting as soon as a freeze is measured after that (the setting did not take, or was reset).
     */
    fun vendorSetupDone(ctx: Context): Boolean {
        val at = prefs(ctx).getLong("vendor_bg_visited", 0L)
        return at > 0L && lastFreezeWall < at
    }

    fun openBackgroundSettings(ctx: Context): String {
        prefs(ctx).edit().putLong("vendor_bg_visited", System.currentTimeMillis()).apply()
        for ((label, cn) in PAGES) {
            val i = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return label
        }
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "应用信息（在“电池”里选“不限制 / 允许后台活动”）"
    }
}
