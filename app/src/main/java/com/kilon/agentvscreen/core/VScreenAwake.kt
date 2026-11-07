package com.kilon.agentvscreen.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONObject

/**
 * [vscreen] v0.5: keeps the background screen awake while work runs on it, also with the phone's screen off.
 *
 * The background screen is its own display group (OWN_DISPLAY_GROUP): the system powers it apart from the main
 * screen, but it still goes to sleep by itself when nothing touches it for the screen timeout, and on some ROMs when
 * the main screen turns off. A sleeping display draws nothing and pauses its apps: a turn that is still running, or a
 * kept watch (act --keep), would stall or see a frozen picture.
 *
 *  - [ensure] runs before every action and every look: a display that is not ON is woken (KEYCODE_WAKEUP injected on
 *    that display only, which wakes its own group, never the main screen) and the call waits for it to draw again.
 *  - While work is pending (a chat turn owns the screen, or a watch runs) a keeper checks every [CHECK_MS]; with the
 *    phone's screen off it holds a partial wake lock and sets an inexact alarm (allowed while idle) as a timer that
 *    wakes the keeper if the process was put to sleep — the "timed wake-up" of the background screen.
 *  - Waking is done only where it is safe: Android 13+ and a display in its own group. Elsewhere waking it would turn
 *    on the user's main screen, so it is not done; status says the background screen sleeps with the phone.
 */
object VScreenAwake {
    private const val TAG = "VScreenAwake"
    private const val CHECK_MS = 15_000L
    private const val ALARM_MS = 60_000L
    private const val ACTION_TICK = "com.kilon.agentvscreen.core.AWAKE_TICK"
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12

    @Volatile var wakes = 0; private set
    @Volatile var lastWake: String? = null; private set
    @Volatile var screenOff = false; private set
    @Volatile private var appContext: Context? = null
    @Volatile private var keeper: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun init(ctx: Context) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        val pm = app.getSystemService(PowerManager::class.java)
        screenOff = pm?.isInteractive == false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> { screenOff = true; kick("熄屏") }
                    Intent.ACTION_SCREEN_ON -> { screenOff = false; releaseLock() }
                    ACTION_TICK -> kick("定时")
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(ACTION_TICK) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(rx, f)
        }.onFailure { AppLogger.warning(TAG, "receiver: ${it.message}") }
    }

    /** Whether the background screen can be woken by itself on this phone (else waking it would wake the main screen). */
    fun canWakeAlone(d: VScreenDisplay): Boolean = Build.VERSION.SDK_INT >= 33 && (d.flags and OWN_DISPLAY_GROUP) != 0

    /** Display.STATE_* of [d] (null: unknown). */
    fun stateOf(d: VScreenDisplay): Int? = runCatching {
        appContext?.getSystemService(DisplayManager::class.java)?.getDisplay(d.displayId)?.state
    }.getOrNull()

    /**
     * Before an action or a look: when [d] is asleep, wake it and wait (≤ 2 s) until it is ON. Returns a fact for the
     * caller's notes when something was done or could not be done; null when it was awake. Never throws.
     */
    fun ensure(d: VScreenDisplay): String? {
        if (d.released || d.parked) return null
        val st = stateOf(d) ?: return null
        if (st == Display.STATE_ON) return null
        if (!canWakeAlone(d)) return "副屏处于休眠（状态 ${stateName(st)}），这台手机上它和主屏一起休眠，单独唤醒会点亮主屏，所以没唤醒：画面可能不动"
        val ok = runCatching { VScreenShell.run(arrayOf("input", "-d", d.displayId.toString(), "keyevent", "KEYCODE_WAKEUP"), 4_000L).exitCode == 0 }.getOrDefault(false)
        val t0 = SystemClock.elapsedRealtime()
        while (ok && SystemClock.elapsedRealtime() - t0 < 2_000L && stateOf(d) != Display.STATE_ON) Thread.sleep(100)
        val on = stateOf(d) == Display.STATE_ON
        wakes++
        lastWake = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} ${if (on) "已唤醒" else "唤醒失败"}（原状态 ${stateName(st)}${if (screenOff) "，主屏熄屏中" else ""}）"
        AppLogger.info(TAG, "display ${d.displayId}: $lastWake")
        if (on) { d.requestFreshFrame(); d.awaitFrameAfter(SystemClock.elapsedRealtime(), 400) }
        return if (on) "副屏刚才休眠了（${stateName(st)}${if (screenOff) "，手机熄屏中" else ""}），已唤醒" else "副屏休眠（${stateName(st)}），唤醒没成功：画面可能不动"
    }

    private fun stateName(s: Int) = when (s) {
        Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"; Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON -> "ON"; else -> s.toString()
    }

    /** Work is pending on the background screen: a chat turn owns it, or a watch runs. */
    private fun needed(): Boolean = BackgroundOperation.needsAwake()

    /** Start the keeper if work is pending (called on screen off, on the timer, and when a screen / watch starts). */
    fun kick(why: String) {
        val ctx = appContext ?: return
        if (!needed()) { releaseLock(); return }
        if (screenOff) { holdLock(ctx); scheduleTick(ctx) }
        synchronized(this) {
            if (keeper?.isAlive == true) return
            keeper = Thread {
                AppLogger.info(TAG, "keeper start ($why)")
                try {
                    while (needed()) {
                        BackgroundOperation.currentDisplay()?.let { ensure(it) }
                        Thread.sleep(CHECK_MS)
                    }
                } catch (_: InterruptedException) {
                } finally { releaseLock() }
            }.apply { isDaemon = true; name = "vscreen-awake"; start() }
        }
    }

    private fun holdLock(ctx: Context) = synchronized(this) {
        if (wakeLock?.isHeld == true) return@synchronized
        wakeLock = runCatching {
            ctx.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:vscreen-awake")?.apply {
                setReferenceCounted(false); acquire(10 * 60_000L)
            }
        }.getOrNull()
    }

    private fun releaseLock() = synchronized(this) { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null }

    /** The timer: an inexact alarm allowed in Doze, re-armed while the screen is off and work is pending. */
    private fun scheduleTick(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, 7291, Intent(ACTION_TICK).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ALARM_MS, pi)
        }.onFailure { AppLogger.warning(TAG, "alarm: ${it.message}") }
    }

    /** For status. */
    fun json(d: VScreenDisplay?): JSONObject = JSONObject()
        .put("phone_screen_off", screenOff)
        .put("display_state", d?.let { stateOf(it)?.let { s -> stateName(s) } } ?: JSONObject.NULL)
        .put("own_power_group", d?.let { canWakeAlone(it) } ?: JSONObject.NULL)
        .put("runs_when_locked", d?.let { (it.flags and ALWAYS_UNLOCKED) != 0 } ?: JSONObject.NULL)
        .put("wakes", wakes)
        .put("last_wake", lastWake ?: JSONObject.NULL)
        .put("note", if (d != null && !canWakeAlone(d)) "这台手机上副屏随主屏休眠：熄屏后后台任务会停住，亮屏后继续"
            else "熄屏时副屏单独保持唤醒（每 15 秒检查，熄屏时另有定时器），主屏不会被点亮")
}
