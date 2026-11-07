package com.kilon.agentvscreen.core

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.host.PowerOptimizationManager
import com.kilon.agentvscreen.host.HostService
import org.json.JSONArray
import org.json.JSONObject

/**
 * [vscreen] Keeps Minis' network alive while it is in the background.
 *
 * This fork installs under its own package, so whatever the user once allowed for the
 * official Minis (battery optimisation exemption, background activity, background data)
 * does not apply to it. On Honor/Huawei and other OEM ROMs an app that is not exempt gets
 * its sockets cut a few seconds after it leaves the screen, even while its foreground
 * service runs: the model request then fails with "Software caused connection abort" or
 * "Failed to connect".
 *
 * [check] reads the system's view; [whitelist] uses Shizuku (uid 2000) to grant the same
 * exemptions a user would grant by hand. [ensureOnce] runs [whitelist] once per process as
 * soon as Shizuku is ready.
 */
internal object VScreenPower {
    private const val TAG = "VScreenPower"
    @Volatile private var ensured = false
    @Volatile var lastFix: String? = null; private set

    data class Check(
        val batteryExempt: Boolean,
        val backgroundRestricted: Boolean,
        /** ConnectivityManager.RESTRICT_BACKGROUND_STATUS_*: 1 disabled, 2 whitelisted, 3 enabled (blocked). */
        val dataSaver: Int,
        val standbyBucket: Int,
        val foregroundServiceAlive: Boolean,
    ) {
        val dataBlocked get() = dataSaver == 3
        val bucketRestricted get() = standbyBucket >= 45 // RARE(40) still has network; RESTRICTED(45) does not
        val ok get() = batteryExempt && !backgroundRestricted && !dataBlocked && !bucketRestricted
        fun problems(): List<String> = buildList {
            if (!batteryExempt) add("未加入电池优化白名单")
            if (backgroundRestricted) add("系统限制了后台活动")
            if (dataBlocked) add("省流量模式禁止后台数据")
            if (bucketRestricted) add("应用待机分组为受限（$standbyBucket）")
        }
        fun json(): JSONObject = JSONObject()
            .put("ok", ok)
            .put("battery_optimization_exempt", batteryExempt)
            .put("background_restricted", backgroundRestricted)
            .put("data_saver", when (dataSaver) { 1 -> "off"; 2 -> "allowed"; 3 -> "blocked"; else -> "unknown" })
            .put("standby_bucket", standbyBucket)
            .put("agent_foreground_service", foregroundServiceAlive)
            .put("problems", JSONArray(problems()))
            .also { o -> lastFix?.let { o.put("last_fix", it) } }
    }

    fun check(ctx: Context): Check {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        return Check(
            batteryExempt = runCatching { PowerOptimizationManager.isIgnoringBatteryOptimizations(ctx) }.getOrDefault(true),
            backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) runCatching { am?.isBackgroundRestricted == true }.getOrDefault(false) else false,
            dataSaver = runCatching { cm?.restrictBackgroundStatus ?: 0 }.getOrDefault(0),
            standbyBucket = if (Build.VERSION.SDK_INT >= 28) runCatching { usm?.appStandbyBucket ?: 0 }.getOrDefault(0) else 0,
            foregroundServiceAlive = HostService.isAlive,
        )
    }

    /** Grants the background exemptions through Shizuku. Returns (all-ok-after, what was done). */
    fun whitelist(ctx: Context): Pair<Boolean, String> {
        if (ShizukuManager.snapshot.value.state != ShizukuManager.State.READY) return false to "Shizuku 不可用"
        val pkg = ctx.packageName
        val uid = ctx.applicationInfo.uid.toString()
        val steps = listOf(
            "电池优化白名单" to arrayOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            "允许后台运行" to arrayOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            "允许后台启动" to arrayOf("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            "省流量时允许后台数据" to arrayOf("cmd", "netpolicy", "add", "restrict-background-whitelist", uid),
            "待机分组设为活跃" to arrayOf("am", "set-standby-bucket", pkg, "active"),
        )
        val done = mutableListOf<String>(); val failed = mutableListOf<String>()
        for ((label, argv) in steps) {
            val r = runCatching { VScreenShell.run(argv, 6_000L) }.getOrNull()
            if (r != null && r.exitCode == 0) done += label else failed += label
        }
        val after = check(ctx)
        val msg = buildString {
            if (done.isNotEmpty()) append("已设置：${done.joinToString("、")}")
            if (failed.isNotEmpty()) { if (isNotEmpty()) append("；"); append("未成功：${failed.joinToString("、")}") }
            if (!after.ok) append("；仍有：${after.problems().joinToString("、")}")
        }
        lastFix = msg
        AppLogger.info(TAG, "background whitelist: $msg")
        return after.ok to msg
    }

    /** Once per process, when Shizuku is ready and the app is not yet exempt. Never throws. */
    fun ensureOnce(ctx: Context) {
        if (ensured || ShizukuManager.snapshot.value.state != ShizukuManager.State.READY) return
        ensured = true
        Thread {
            runCatching { if (!check(ctx).ok) whitelist(ctx) }.onFailure { AppLogger.warning(TAG, "ensureOnce: ${it.message}") }
        }.apply { isDaemon = true; name = "vscreen-power" }.start()
    }

    /** What the user does by hand on this phone when Shizuku is not available. */
    fun manualSteps(): String = when (VScreenShell.device().vendor.lowercase()) {
        "honor", "huawei" -> "手机管家 › 应用启动管理 › Agent-VScreen：关闭“自动管理”，手动打开“允许后台活动”；设置 › 电池 › 更多电池设置：打开“休眠时始终保持网络连接”"
        "xiaomi", "redmi" -> "设置 › 应用设置 › 应用管理 › Agent-VScreen › 省电策略：无限制；并打开“自启动”"
        "vivo", "iqoo" -> "i管家 › 应用管理 › 权限管理 › 自启动 打开 Agent-VScreen；设置 › 电池 › 后台耗电管理 › Agent-VScreen：允许后台高耗电"
        "oppo", "oneplus", "realme" -> "设置 › 电池 › 应用耗电管理 › Agent-VScreen：允许后台运行、允许完全后台行为"
        else -> "系统设置 › 应用 › Agent-VScreen › 电池：不受限制（或“不优化”），并允许后台数据"
    }
}
