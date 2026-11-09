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
 *  - [ensure] runs before every action and every look: a display that is not ON is checked and, only when ownership
 *    and group safety are proven, a display-scoped userActivity is attempted; there is no global wakeUp, power key,
 *    or settings fallback.
 *  - While work is pending (a chat turn owns the screen, or a watch runs) a keeper checks every [CHECK_MS]; with the
 *    phone's screen off it holds a partial wake lock and sets an inexact alarm (allowed while idle) as a timer that
 *    wakes the keeper if the process was put to sleep — the "timed wake-up" of the background screen.
 *  - Waking is done only where it is safe: Android 13+ and a display in its own group. Elsewhere waking it would turn
 *    on the user's main screen, so it is not done; status says the background screen sleeps with the phone.
 */
object VScreenAwake {
    class ActionBlocked(message: String) : Exception(message)

    private const val TAG = "VScreenAwake"
    private const val CHECK_MS = 4_000L
    private const val ACTIVITY_THROTTLE_MS = 3_000L
    private const val ALARM_MS = 60_000L
    private const val ACTION_TICK = "com.kilon.agentvscreen.lab.core.AWAKE_TICK"
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12

    @Volatile var wakes = 0; private set
    @Volatile var lastWake: String? = null; private set
    @Volatile var screenOff = false; private set
    @Volatile private var appContext: Context? = null
    @Volatile private var keeper: Thread? = null
    @Volatile private var lastActivityAt = 0L
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
        if (!canWakeAlone(d)) return "副屏处于休眠（状态 ${stateName(st)}），这台手机上它和主屏一起休眠，单独唤醒会点亮主屏，所以没唤醒：画面可能不动"
        val policy = VScreenPowerPolicy.evaluate(
            api = Build.VERSION.SDK_INT,
            displayId = d.displayId,
            displayFlags = d.flags,
            ownedDisplayId = d.displayId, // the service performs the authoritative owned-display check
        )
        if (!policy.allowed) return "副屏（${stateName(st)}），${policy.reason}；未发送全局唤醒，也未执行动作"
        if (st == Display.STATE_ON) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastActivityAt >= ACTIVITY_THROTTLE_MS) {
                val active = targetedUserActivity(d)
                lastActivityAt = now
                if (!active) AppLogger.warning(TAG, "display ${d.displayId}: userActivity refused while ON")
            }
            return null
        }
        val attempt = targetedWake(d)
        val t0 = SystemClock.elapsedRealtime()
        while (attempt && SystemClock.elapsedRealtime() - t0 < 2_000L && stateOf(d) != Display.STATE_ON) Thread.sleep(100)
        val on = stateOf(d) == Display.STATE_ON
        wakes++
        lastWake = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())} ${if (on) "定向唤醒后已亮" else "定向唤醒后仍未亮"}（原状态 ${stateName(st)}${if (screenOff) "，主屏熄屏中" else ""}，attempt=$attempt）"
        AppLogger.info(TAG, "display ${d.displayId}: $lastWake")
        if (on) { d.requestFreshFrame(); d.awaitFrameAfter(SystemClock.elapsedRealtime(), 400) }
        return if (on) "副屏刚才休眠了（${stateName(st)}${if (screenOff) "，手机熄屏中" else ""}），定向唤醒后已亮"
        else "副屏休眠（${stateName(st)}），定向唤醒未证实唤醒：画面可能不动；没有使用全局 wakeUp/电源键"
    }

    /**
     * Action-only gate: a cached frame may still be returned for diagnostics, but input must never
     * be injected while the target display is known to be asleep or its state cannot be verified.
     */
    fun ensureForAction(d: VScreenDisplay): String? {
        if (d.released) throw ActionBlocked("副屏已释放，动作没有执行")
        if (d.parked) throw ActionBlocked("副屏处于省电暂停，动作没有执行：先恢复并重新 read")
        val before = stateOf(d) ?: throw ActionBlocked("读不到副屏电源状态，动作没有执行：先 read 确认副屏")
        if (VScreenDisplayHealth.actionMayInject(stateKnown = true, displayOn = before == Display.STATE_ON)) return null
        val note = ensure(d)
        val after = stateOf(d)
        if (!VScreenDisplayHealth.actionMayInject(stateKnown = after != null, displayOn = after == Display.STATE_ON)) {
            throw ActionBlocked("副屏仍未唤醒（${stateName(after ?: before)}），未执行请求的触摸、输入或业务按键动作；门禁内部可能已发送唤醒键")
        }
        return note
    }

    private fun stateName(s: Int) = when (s) {
        Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"; Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON -> "ON"; else -> s.toString()
    }


    /** Invoke only the shell UserService per-display IPowerManager overload; never the default-display API. */
    private fun targetedUserActivity(d: VScreenDisplay): Boolean =
        VScreenRemoteDisplay.userActivity(d.displayId).getOrDefault(false)

    private fun targetedWake(d: VScreenDisplay): Boolean =
        VScreenRemoteDisplay.wakeUpWithDisplayId(d.displayId).getOrDefault(false)

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

