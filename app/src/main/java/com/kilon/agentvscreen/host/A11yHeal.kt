package com.kilon.agentvscreen.host

import android.content.Context
import android.os.SystemClock

/**
 * Keeps the read-only accessibility service on after the user asked for it once (setup), the way android-vdisplay
 * does, with three guard rails:
 *  - append-only (Setup.enableAccessibility keeps every other service), at most once a minute;
 *  - when it goes off again shortly after we turned it back on, that is the user (or the ROM) saying no: we stop and
 *    the app says so, until the user runs setup again;
 *  - when the system blocks the write (MagicOS / HarmonyOS), we remember it and stop trying; the app shows the manual way.
 * A force-stop or an app update drops the service on some ROMs; this is what brings it back without the user.
 */
object A11yHeal {
    private const val MIN_GAP_MS = 60_000L
    private const val USER_OFF_WINDOW_MS = 10 * 60_000L
    @Volatile private var lastTry = 0L
    @Volatile private var healedAt = 0L
    @Volatile var lastResult: String? = null; private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)
    fun wanted(ctx: Context) = p(ctx).getBoolean("a11y_wanted", false)
    fun blocked(ctx: Context) = p(ctx).getBoolean("a11y_blocked", false)
    fun userOff(ctx: Context) = p(ctx).getBoolean("a11y_user_off", false)

    /** The user asked for it (setup): heal from now on, forget earlier refusals. */
    fun want(ctx: Context) { p(ctx).edit().putBoolean("a11y_wanted", true).putBoolean("a11y_blocked", false).putBoolean("a11y_user_off", false).apply() }

    /** Called when the service disconnects while the app runs. */
    fun onLost(ctx: Context) {
        if (healedAt > 0 && SystemClock.elapsedRealtime() - healedAt < USER_OFF_WINDOW_MS) {
            p(ctx).edit().putBoolean("a11y_user_off", true).apply()
            lastResult = "无障碍刚自动打开又被关掉了：当作你不想开，不再自动打开（首页“一键配置”可再开）"
            AppLogger.info("A11yHeal", "turned off again after heal: stop healing")
        }
    }

    /** Turn the service back on if the user wants it and nothing says no. Cheap when there is nothing to do. */
    fun maybeHeal(ctx: Context, why: String) {
        if (VScreenAccessibilityService.getInstance() != null) return
        if (!wanted(ctx) || blocked(ctx) || userOff(ctx) || !ShizukuManager.isReady()) return
        val now = SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < MIN_GAP_MS) return
        lastTry = now
        Thread {
            val (ok, msg) = runCatching { Setup.enableAccessibility(ctx) }.getOrElse { false to "无障碍：${it.message}" }
            lastResult = msg
            if (ok) healedAt = SystemClock.elapsedRealtime()
            else if (msg.contains("没连上") || msg.contains("写系统设置失败")) p(ctx).edit().putBoolean("a11y_blocked", true).apply()
            AppLogger.info("A11yHeal", "$why: $msg")
        }.apply { isDaemon = true; name = "a11y-heal" }.start()
    }
}
