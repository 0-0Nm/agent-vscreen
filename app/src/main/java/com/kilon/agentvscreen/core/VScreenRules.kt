package com.kilon.agentvscreen.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [vscreen] v49: keeps — an act's stop condition that goes on watching after the turn (`act --stop C --keep wake|notify`).
 *
 * Between turns, on the phone only (the same condition evaluator as act, no model calls), for up to 12 hours: when it
 * is seen, wake the chat or notify the user, and end. Nothing is done on the screen by itself. While one runs the idle
 * screen is neither paused nor released, a foreground notification shows what is watched (with a stop button) and a
 * partial wake lock keeps the checks going. They end when Agent-VScreen is force-stopped or restarted.
 */
internal object VScreenRules {
    private const val TAG = "VScreenRules"
    const val MAX = 5

    // ── background rules: kept watching between turns, on the phone only ──

    class Spec(
        val name: String, val sid: String,
        /** The condition as written in act --stop. */
        val cond: String,
        val app: String?, val everyMs: Long, val forMs: Long,
        val wake: Boolean, val notify: Boolean,
    ) {
        fun describe(): String = "满足 $cond" + (app?.let { "（$it）" } ?: "")
    }

    class Live(val spec: Spec) {
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        @Volatile var checks = 0
        @Volatile var status = "启动中"
        @Volatile var lastValue: String? = null
        @Volatile var fired = 0
        @Volatile var lastFire: String? = null
        @Volatile var lastFireAt = 0L
        @Volatile var stopped = false
        @Volatile var endReason: String? = null
        @Volatile var endedAt = 0L
        @Volatile var thread: Thread? = null

        fun json(): JSONObject {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            return JSONObject().put("name", spec.name).put("condition", spec.describe())
                .put("every_ms", spec.everyMs)
                .put("running", !stopped)
                .put("status", if (stopped) (endReason ?: "已结束") else status)
                .put("started", fmt.format(java.util.Date(startedAt)))
                .put("ends_in_min", if (stopped) JSONObject.NULL else ((spec.forMs - (SystemClock.elapsedRealtime() - startedElapsed)) / 60_000L).coerceAtLeast(0))
                .put("checks", checks).put("fired", fired)
                .put("last_value", lastValue ?: JSONObject.NULL)
                .put("last_fire", lastFire ?: JSONObject.NULL)
                .put("last_fire_at", if (lastFireAt > 0) fmt.format(java.util.Date(lastFireAt)) else JSONObject.NULL)
                .put("action", JSONArray(listOfNotNull(if (spec.wake) "发事件给调用方（events 取）" else null, if (spec.notify) "通知用户" else null)))
        }
    }

    /** What the tool handler gives the engine. */
    interface Host {
        /** null = not met; otherwise what was seen. */
        fun check(l: Live, d: VScreenDisplay): String?
    }

    @Volatile private var appContext: Context? = null
    private val live = LinkedHashMap<String, Live>()
    private val ended = ArrayDeque<Live>()

    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }

    fun list(): List<Live> = synchronized(live) { live.values.toList() + ended.toList().reversed().filter { it.spec.name !in live } }
    fun running(): List<Live> = synchronized(live) { live.values.toList() }

    fun start(spec: Spec, host: Host): Live {
        val ctx = appContext ?: throw IllegalStateException("后台操作未初始化")
        val l = Live(spec)
        synchronized(live) {
            live[spec.name]?.let { stopInner(it, "被同名的新盯守替换") }
            if (live.size >= MAX) throw IllegalStateException("最多同时盯 $MAX 个条件，先 keep off 一个")
            live[spec.name] = l
        }
        BackgroundOperation.hold(true)
        l.thread = Thread { loop(ctx, l, host) }.apply { isDaemon = true; name = "vscreen-rule-${spec.name.take(12)}" }
        l.thread?.start()
        RuleService.refresh(ctx)
        AppLogger.info(TAG, "keep ${spec.name} started: ${spec.describe()}")
        return l
    }

    fun stop(name: String, reason: String = "已按要求停止"): Boolean {
        val l = synchronized(live) { live[name] } ?: return false
        stopInner(l, reason)
        return true
    }

    /** Forget ended watches ([name] null = all); the number forgotten. */
    fun clearEnded(name: String? = null): Int = synchronized(live) {
        val before = ended.size
        ended.removeAll { name == null || it.spec.name == name }
        before - ended.size
    }

    fun stopAll(reason: String = "已按要求停止"): Int {
        val all = running()
        all.forEach { stopInner(it, reason) }
        return all.size
    }

    private fun stopInner(l: Live, reason: String) {
        val removed = synchronized(live) {
            if (l.stopped) return
            l.stopped = true; l.endReason = reason; l.endedAt = System.currentTimeMillis()
            val r = live.remove(l.spec.name) != null
            ended.removeAll { it.spec.name == l.spec.name }
            ended.addLast(l); while (ended.size > 10) ended.removeFirst()
            r
        }
        l.thread?.interrupt()
        if (removed) BackgroundOperation.hold(false)
        appContext?.let { RuleService.refresh(it) }
        AppLogger.info(TAG, "keep ${l.spec.name} ended: $reason")
    }

    private fun loop(ctx: Context, l: Live, host: Host) {
        val s = l.spec
        var appOk = true
        var n = 0
        try {
            while (!l.stopped) {
                if (SystemClock.elapsedRealtime() - l.startedElapsed >= s.forMs) {
                    stopInner(l, if (l.fired > 0) "到时间了（期间触发 ${l.fired} 次）" else "到时间了，没等到条件")
                    break
                }
                val st = BackgroundOperation.state.value
                val d = BackgroundOperation.previewDisplayFor(s.sid)
                when {
                    d == null -> l.status = if (st.ownerSessionId != null && st.ownerSessionId != s.sid)
                        "副屏正被另一个会话使用，暂停检查（那边用完、副屏空闲后不会自动回来；需要时在本会话重新操作一次副屏）"
                    else "副屏已释放，暂停检查（在本会话里重新 launch 后自动继续）"
                    st.takeoverSessionId != null -> l.status = "用户正在接管副屏，暂停检查"
                    else -> {
                        if (d.parked) runCatching { BackgroundOperation.unpark(d) }
                        if (s.app != null && (n % maxOf(1, (3_000 / s.everyMs).toInt()) == 0)) appOk = VScreenShell.topPackage(d.displayId) == s.app
                        n++
                        if (!appOk) l.status = "副屏顶部不是 ${s.app}，暂停检查"
                        else {
                            var failed: String? = null
                            val hit = try { host.check(l, d) } catch (e: InterruptedException) { throw e } catch (t: Throwable) {
                                failed = "检查出错：${t.javaClass.simpleName}: ${t.message?.take(80)}"; null }
                            l.checks++
                            l.status = failed ?: "正在盯"
                            if (hit != null) fire(ctx, l, host, hit)
                        }
                    }
                }
                if (l.stopped) break
                Thread.sleep(s.everyMs)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "keep ${s.name}: ${t.message}")
            stopInner(l, "出错停止：${t.javaClass.simpleName}: ${t.message?.take(100)}")
        }
    }

    private fun fire(ctx: Context, l: Live, @Suppress("UNUSED_PARAMETER") host: Host, hit: String) {
        val s = l.spec
        l.fired++
        l.lastFireAt = System.currentTimeMillis()
        val parts = arrayListOf("看到：$hit")
        var woke: String? = null
        if (s.wake) {
            val text = "[盯守「${s.name}」看到了] $hit（条件 ${s.cond}）\n\n（这是你之前用 vscreen act --keep wake 设的盯守发出的事件。" +
                "副屏停在看到时的页面：先 vscreen act 看现在的画面和这段时间发生了什么，再决定下一步。）"
            woke = runCatching { com.kilon.agentvscreen.api.EventHub.post(ctx, s.sid, "keep", s.name, text) }
                .getOrElse { "失败：${it.message}" }
            parts += "事件：$woke"
        }
        if (s.notify || woke?.let { it == "Rejected" || it.startsWith("失败") } == true) {
            val okN = VScreenHandoff.alert(ctx, 7300 + (s.name.hashCode() and 0xff), "盯守「${s.name}」看到了", parts.joinToString("\n"))
            if (!okN) parts += "（通知没发出去：${VScreenHandoff.notifyBlocked(ctx) ?: "发送失败"}）"
        }
        l.lastFire = parts.joinToString("；")
        BackgroundOperation.backgroundDone(s.sid)
        AppLogger.info(TAG, "keep ${s.name} fired: ${l.lastFire}")
        stopInner(l, "已看到：${l.lastFire?.take(160)}")
    }

    // ── keeping it alive: foreground notification + wake lock ─────────────

    class RuleService : Service() {
        private var wake: PowerManager.WakeLock? = null

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (intent?.action == ACTION_STOP) {
                stopAll("用户在通知里点了全部停止")
                stopSelfSafely(); return START_NOT_STICKY
            }
            val list = running()
            if (list.isEmpty()) { stopSelfSafely(); return START_NOT_STICKY }
            val n = notification(this, list)
            runCatching {
                com.kilon.agentvscreen.host.Fg.start(this, NOTE_ID, n)
            }.onFailure { AppLogger.warning(TAG, "startForeground: ${it.message}") }
            if (wake == null) runCatching {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentvscreen:rules").apply { setReferenceCounted(false); acquire(12 * 3_600_000L + 60_000L) }
            }
            return START_NOT_STICKY
        }

        private fun stopSelfSafely() {
            runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
            stopSelf()
        }

        override fun onDestroy() {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
            super.onDestroy()
        }

        companion object {
            const val ACTION_STOP = "com.kilon.agentvscreen.core.RULES_STOP"
            private const val CHANNEL = "vscreen_rules"
            private const val NOTE_ID = 7299
            @Volatile var lastStartError: String? = null

            private fun notification(ctx: Context, list: List<Live>): android.app.Notification {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    if (nm?.getNotificationChannel(CHANNEL) == null)
                        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "副屏盯守", NotificationManager.IMPORTANCE_LOW))
                }
                val stop = PendingIntent.getService(ctx, 1, Intent(ctx, RuleService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val body = list.joinToString("\n") { "「${it.spec.name}」${it.spec.describe()}" }
                return NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("副屏正在盯 ${list.size} 个条件")
                    .setContentText(body.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setOngoing(true)
                    .addAction(0, "全部停止", stop)
                    .build()
            }

            /** Start, update or stop the service to match the running keeps. */
            fun refresh(ctx: Context) {
                val list = running()
                val i = Intent(ctx, RuleService::class.java)
                if (list.isEmpty()) { runCatching { ctx.stopService(i) }; return }
                lastStartError = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                    null
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                if (lastStartError != null) {
                    AppLogger.warning(TAG, "keep service not started: $lastStartError")
                    // At least show what is being watched.
                    runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(ctx, list)) }
                }
            }
        }
    }
}
