package com.kilon.agentvscreen.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.kilon.agentvscreen.host.AppLogger

/**
 * [vscreen] v22 接力: move an app's task, as it is (page, login, half-filled form), between
 * the user's main screen and the background screen.
 *
 *  - in:  the user's app on the main screen -> the background screen, for the agent to go on.
 *  - out: the app on the background screen -> the front of the main screen, for a step only the
 *         user can do (pay, face/fingerprint, a code).
 *
 * The move is the system's own (moveRootTaskToDisplay, shell permission MANAGE_ACTIVITY_TASKS):
 * first `am display move-stack`, then the same call through the Shizuku display service. A
 * maker's system may refuse it; then nothing is moved and the caller says so plainly.
 * The app sees a configuration change (screen size/density) and normally keeps its page.
 */
internal object VScreenHandoff {
    private const val TAG = "VScreenHandoff"

    data class TaskRef(val taskId: Int, val pkg: String, val visible: Boolean)

    /** Standard (app) tasks on [displayId], top first. Home/recents/assistant tasks are left out. */
    fun tasksOn(displayId: Int): List<TaskRef> {
        // v27: the system's own per-display list first (no dumpsys parsing, cannot leak across displays).
        VScreenShell.taskSource(displayId)?.let { list ->
            return list.filter { it.isAppTask }.mapNotNull { t -> t.pkg?.let { TaskRef(t.taskId, it, t.visible) } }
                .distinctBy { it.taskId }
        }
        val lines = VScreenShell.displaySection(displayId) ?: return emptyList()
        val taskRe = Regex("Task(?:Record)?\\{[0-9a-f]+ #(\\d+)")
        val pkgRe = Regex("A=(?:\\d+:)?([a-zA-Z0-9_.]+)")
        val actRe = Regex(" ([a-zA-Z][a-zA-Z0-9_.]*)/[a-zA-Z0-9_.$]+")
        val out = ArrayList<TaskRef>()
        var pending: Int? = null
        var pendingVisible = false
        for (l in lines) {
            val m = if (VScreenShell.isTaskLine(l)) taskRe.find(l) else null
            if (m != null) {
                pending = null
                val id = m.groupValues[1].toIntOrNull() ?: continue
                if (Regex("type=(home|recents|assistant|dream)").containsMatchIn(l)) continue
                val pkg = pkgRe.find(l)?.groupValues?.get(1)
                if (pkg != null) { if (out.none { it.taskId == id }) out += TaskRef(id, pkg, l.contains("visible=true")) }
                else { pending = id; pendingVisible = l.contains("visible=true") }
                continue
            }
            val pid = pending ?: continue
            if (!(l.startsWith("* Hist") || l.startsWith("Hist #"))) continue
            actRe.find(l)?.let { a ->
                if (out.none { it.taskId == pid }) out += TaskRef(pid, a.groupValues[1], pendingVisible)
                pending = null
            }
        }
        return out
    }

    /** The launcher (home) packages, which are never handed over. */
    fun homePackages(): Set<String> {
        val r = VScreenShell.run(arrayOf("cmd", "package", "query-activities", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"), 8_000L)
        return r.combined.lineSequence().map { it.trim() }.filter { it.contains('/') && !it.contains(' ') }
            .map { it.substringBefore('/') }.toSet()
    }

    data class Moved(val ok: Boolean, val via: String, val detail: String)

    /** Move task [taskId] (of [pkg]) to [toDisplay] and check that it is really there. */
    fun move(taskId: Int, pkg: String, toDisplay: Int): Moved {
        val notes = ArrayList<String>()
        val r = VScreenShell.run(arrayOf("am", "display", "move-stack", taskId.toString(), toDisplay.toString()), 10_000L)
        val out = r.combined.trim()
        val shellErr = r.exitCode != 0 || Regex("Exception|Error|Unknown|unknown|Permission Denial|not found").containsMatchIn(out)
        notes += "am display move-stack：" + if (shellErr) (out.lines().firstOrNull { it.isNotBlank() }?.take(160) ?: "exit ${r.exitCode}") else "已执行"
        if (!shellErr && landed(taskId, pkg, toDisplay)) return Moved(true, "am display move-stack", notes.joinToString("；"))
        val s = VScreenRemoteDisplay.moveTask(taskId, toDisplay)
        val okSvc = s.getOrNull()?.first == true
        notes += "副屏服务 moveRootTaskToDisplay：" + if (okSvc) "已执行" else (s.getOrNull()?.second ?: s.exceptionOrNull()?.message ?: "失败")
        if (okSvc && landed(taskId, pkg, toDisplay)) return Moved(true, "moveRootTaskToDisplay", notes.joinToString("；"))
        if (okSvc || !shellErr) notes += "命令执行了，但这个应用没有出现在目标屏最前面（任务可能只移了记录、没有显示）"
        AppLogger.warning(TAG, "move task $taskId ($pkg) -> display $toDisplay failed: $notes")
        return Moved(false, "", notes.joinToString("；"))
    }

    /**
     * v23: "landed" = the app is the resumed one on top of [display]. A task whose record moved
     * but stayed STOPPED behind something else (seen on MagicOS after a half move) does not count:
     * it is brought to the front once with a launcher intent, then checked again.
     */
    private fun landed(taskId: Int, pkg: String, display: Int): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        var present = false
        while (SystemClock.elapsedRealtime() - t0 < 2_000) {
            Thread.sleep(250)
            if (VScreenShell.topPackage(display) == pkg) return true
            present = present || tasksOn(display).any { it.taskId == taskId }
        }
        if (!present) return false
        val comp = VScreenShell.resolveLauncher(pkg) ?: return false
        val argv = (if (display == 0) arrayOf("am", "start") else arrayOf("am", "start", "--display", display.toString())) +
            arrayOf("-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", comp)
        VScreenShell.run(argv, 10_000L)
        repeat(8) { Thread.sleep(250); if (VScreenShell.topPackage(display) == pkg) return true }
        return false
    }

    /** The user's own button (full-screen view): put the app on the background screen in front of them. */
    fun userToMain(sessionId: String): Pair<Boolean, String> {
        val d = BackgroundOperation.previewDisplayFor(sessionId) ?: return false to "副屏已经不在了"
        val pkg = VScreenShell.topPackage(d.displayId) ?: return false to "副屏上没有应用"
        val task = VScreenShell.taskIdOn(d.displayId, pkg) ?: return false to "找不到 $pkg 在副屏上的任务"
        val m = move(task, pkg, 0)
        return if (m.ok) true to "已把 $pkg 原样移到主屏" else false to "系统不允许移到主屏（${m.detail.take(120)}），请用“接管”在这里操作"
    }

    // ── telling the user ──────────────────────────────────────────────────

    const val ALERT_CHANNEL = "vscreen_alert"

    fun ensureAlertChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "后台操作提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    /**
     * v48: why the system would drop our notifications, or null when it would show them: the app switch, the runtime
     * permission (13+), the app-op some systems set to ignore, or our alert channel switched off.
     */
    fun notifyBlocked(context: Context): String? = runCatching {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return@runCatching "系统设置里关了 ATVSX 的通知"
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return@runCatching "没有授予通知权限（POST_NOTIFICATIONS）"
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        if (ops != null && Build.VERSION.SDK_INT >= 29) {
            val mode = runCatching { ops.unsafeCheckOpNoThrow("android:post_notification", context.applicationInfo.uid, context.packageName) }.getOrNull()
            if (mode != null && mode != android.app.AppOpsManager.MODE_ALLOWED && mode != android.app.AppOpsManager.MODE_DEFAULT)
                return@runCatching "系统的通知开关（appops POST_NOTIFICATION）是拒绝/忽略"
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ensureAlertChannel(context)
            val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(ALERT_CHANNEL)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return@runCatching "“后台操作提醒”这类通知被关了"
        }
        null
    }.getOrNull()

    /** A heads-up notification; false when the system does not allow it (see [notifyBlocked]) or posting failed. */
    fun alert(context: Context, id: Int, title: String, body: String): Boolean = runCatching {
        if (notifyBlocked(context) != null) return@runCatching false
        ensureAlertChannel(context)
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        true
    }.getOrElse { AppLogger.warning(TAG, "alert: ${it.message}"); false }
}
