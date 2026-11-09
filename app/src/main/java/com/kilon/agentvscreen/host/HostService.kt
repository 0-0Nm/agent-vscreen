package com.kilon.agentvscreen.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.ApiServer
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.ui.MainActivity
import com.kilon.agentvscreen.ui.Overlay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground start with the type this app declares (specialUse on 14+, none before). */
object Fg {
    fun start(s: Service, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else s.startForeground(id, n)
    }
}

/**
 * The always-on part: keeps the process alive in the foreground, owns the local API server and the floating window.
 * Started by the app (and on boot / package update when the user left it on); the notification says where it listens.
 */
class HostService : Service() {
    companion object {
        @Volatile var isAlive = false; private set
        @Volatile var server: ApiServer? = null; private set
        private const val CHANNEL = "agent_vscreen_host"
        private const val NOTE_ID = 7001
        const val ACTION_STOP = "com.kilon.agentvscreen.STOP"
        const val ACTION_ALLOW = "com.kilon.agentvscreen.ALLOW"
        const val ACTION_SHOW_WINDOW = "com.kilon.agentvscreen.SHOW_WINDOW"
        private const val STOP_CHANNEL = "agent_vscreen_stopped"
        private const val STOP_NOTE_ID = 7002

        fun start(ctx: Context) {
            val i = Intent(ctx, HostService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
                .onFailure {
                    // Android 12+ refuses to start a foreground service from the background (e.g. the process was
                    // restarted by the system). Opening the app starts it again (MainActivity.onResume).
                    Liveness.foregroundFailed("后台启动服务被系统拒绝：${it.javaClass.simpleName}")
                    AppLogger.warning("HostService", "start: ${it.message}")
                    startFromShell(ctx.applicationContext)
                }
        }

        /**
         * Learned from android-vdisplay: a foreground service started by the shell (`am start-foreground-service`) is
         * not subject to the app's background-start ban. So when the system refuses our own start (process restarted
         * in the background), ask Shizuku — once it is up — to start it as the shell does. Still append-only: it only
         * starts this app's own service.
         */
        private fun startFromShell(app: Context) {
            Thread({
                val until = android.os.SystemClock.elapsedRealtime() + 60_000L
                while (!ShizukuManager.isReady() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(1_000)
                if (!ShizukuManager.isReady() || isAlive) return@Thread
                val r = runCatching {
                    com.kilon.agentvscreen.core.VScreenShell.run(arrayOf("am", "start-foreground-service", "-n", "${app.packageName}/${HostService::class.java.name}"), 6_000L)
                }.getOrNull()
                AppLogger.info("HostService", "start via shell: ${r?.exitCode ?: "failed"} ${r?.combined?.take(120) ?: ""}")
            }, "vscreen-shell-start").apply { isDaemon = true }.start()
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, HostService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        Liveness.init(applicationContext)
        Liveness.start()
        goForeground()
        BackgroundOperation.setEnabled(true)
        server = ApiServer(applicationContext).also { it.onChange = { renotify() }; it.start(ApiConfig.port) }
        Overlay.attach(applicationContext)
        Presence.start(applicationContext)
        A11yHeal.maybeHeal(applicationContext, "service start")
        Tailnet.sync(applicationContext)
        com.kilon.agentvscreen.core.VScreenPower.heal(applicationContext, "service start")
        // 1.1.6: the Stop lock and a hidden window are never silent: both live in the notification shade.
        watch = scope.launch {
            kotlinx.coroutines.flow.combine(
                BackgroundOperation.state.map { Triple(it.userStopped, it.windowHidden && it.displayId != null, it.stoppedAt) }.distinctUntilChanged(),
                Tailnet.status.map { it.state to it.ip }.distinctUntilChanged(),
            ) { a, _ -> a }.collect { (stopped, _, _) -> renotify(); stoppedNote(stopped) }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var watch: kotlinx.coroutines.Job? = null

    /**
     * A separate, visible notification while the user's Stop holds: the user may have pressed it (or closed the screen
     * after it) and walked away; without this the agent just "stops working" for no visible reason.
     */
    private fun stoppedNote(stopped: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!stopped) { nm.cancel(STOP_NOTE_ID); return }
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(STOP_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(STOP_CHANNEL, "AI 已停止", NotificationManager.IMPORTANCE_DEFAULT))
        val allow = PendingIntent.getService(this, 3, Intent(this, HostService::class.java).setAction(ACTION_ALLOW), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val at = BackgroundOperation.state.value.stoppedAt.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) }
        val n = NotificationCompat.Builder(this, STOP_CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setContentTitle("已停止 AI" + (at?.let { "（$it）" } ?: ""))
            .setContentText("agent 的操作会被拒绝，直到你点“允许继续”")
            .setStyle(NotificationCompat.BigTextStyle().bigText("你在悬浮窗上按了“停止 AI”。在你允许之前，agent 的每个操作都会被拒绝，它也会被告知去找你。要让它继续，点下面的“允许继续”。"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFE5484D.toInt())
            .addAction(0, "允许继续", allow)
            .build()
        runCatching { nm.notify(STOP_NOTE_ID, n) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setServiceOn(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ALLOW) { BackgroundOperation.userResume(); return START_STICKY }
        if (intent?.action == ACTION_SHOW_WINDOW) { BackgroundOperation.showWindow(); return START_STICKY }
        // Started again (the user opened the app, boot, update): whatever failed before gets another go now.
        if (!Liveness.foreground) goForeground()
        if (server?.running != true) server?.start(ApiConfig.port)
        Presence.start(applicationContext) // the overlay permission may have arrived since
        Tailnet.sync(applicationContext)
        com.kilon.agentvscreen.core.VScreenPower.heal(applicationContext, "service restart")
        renotify()
        return START_STICKY
    }

    /** Without a real foreground service nothing keeps this process running in the background: check, don't assume. */
    private fun goForeground() {
        runCatching { Fg.start(this, NOTE_ID, notification()) }
            .onSuccess { Liveness.foregroundOk() }
            .onFailure { Liveness.foregroundFailed("${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun renotify() {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification()) }
    }

    override fun onDestroy() {
        watch?.cancel()
        Tailnet.stop()
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(STOP_NOTE_ID) }
        Overlay.detach()
        Presence.stop()
        server?.onChange = null; server?.stop(); server = null
        Liveness.serviceGone()
        if (!Prefs.serviceOn(this)) BackgroundOperation.setEnabled(false)
        isAlive = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Agent-VScreen 服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val err = server?.lastError ?: Liveness.problem()
        val s = BackgroundOperation.state.value
        val ts = Tailnet.status.value
        val where = "127.0.0.1:${ApiConfig.port}" + (if (ts.loggedIn) " · Tailscale ${ts.ip}" else "")
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.kilon.agentvscreen.R.drawable.ic_stat)
            .setContentTitle(when {
                s.userStopped -> "Agent-VScreen · AI 已停止"
                s.windowHidden && s.displayId != null -> "Agent-VScreen · AI 在后台工作（小窗已收起）"
                else -> "Agent-VScreen 服务运行中"
            })
            .setContentText(err ?: "$where · CLI / HTTP / MCP")
            .setContentIntent(open)
            .setOngoing(true)
        if (s.windowHidden && s.displayId != null)
            b.addAction(0, "显示小窗", PendingIntent.getService(this, 5, Intent(this, HostService::class.java).setAction(ACTION_SHOW_WINDOW), PendingIntent.FLAG_IMMUTABLE))
        else b.addAction(0, "查看副屏", PendingIntent.getActivity(this, 2, Intent(this, com.kilon.agentvscreen.ui.FullscreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        return b.addAction(0, "停止服务", stop).build()
    }
}

/** Small app-level switches. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)
    fun serviceOn(ctx: Context) = p(ctx).getBoolean("service_on", true)
    fun setServiceOn(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("service_on", on).apply() }
}
