package com.kilon.agentvscreen.cmd

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.kilon.agentvscreen.host.VScreenAccessibilityService
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.ScreenSig
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenDisplayService
import com.kilon.agentvscreen.core.VScreenRemoteDisplay
import com.kilon.agentvscreen.core.VScreenDownscale
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler.Companion.lastActivity

// [vscreen] vscreen — 副屏与诊断：status / fix / apps.
// Part of VScreenOffloadHandler (see that file for the command table and shared state).

private val AUTO_FIXABLE = setOf("background_network", "not_connected", "no_accessibility", "app_owned_display", "a11y_not_ready", "accessibility_cannot_see_display")

private val FIX_WHAT = mapOf(
    "not_connected" to "重新连接特权通道",
    "not_foreground_service" to "说明：请用户打开一次 ATVSX，服务会补成前台服务",
    "frozen_in_background" to "用 Shizuku 加电池白名单和后台运行许可，并说明厂商后台管理里要用户手动打开的项",
    "no_privilege" to "Shizuku 未启动/未授权：给出用户步骤，--open 在主屏打开 Shizuku",
    "no_accessibility" to "通过 Shizuku 打开 ATVSX 的无障碍服务（改系统设置）",
    "app_owned_display" to "重新连接并释放旧副屏，下次 launch 用 Shizuku 副屏",
    "no_display_focus" to "系统版本限制（需 Android 14+），无法修复",
    "accessibility_cannot_see_display" to "重启无障碍服务后重新检查",
    "a11y_not_ready" to "等待并重新检查控件树",
    "background_network" to "通过 Shizuku 把 ATVSX 加入电池优化/后台运行/后台数据白名单（改系统设置）；没有 Shizuku 时给出手动步骤",
)

private val A11Y_SERVICE = "com.kilon.agentvscreen.host.VScreenAccessibilityService"


// ── status / state ────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.status(sid: String?, args: OffloadArgs): NativeOffloadResult {
    // After a Minis restart the screen may still live in the Shizuku service: adopt it now.
    if (sid != null && BackgroundOperation.state.value.let { it.enabled && it.displayId == null && !it.needsExplicitReconnect }) {
        runCatching { BackgroundOperation.acquire(sid, create = false) }
    }
    val t0 = SystemClock.elapsedRealtime()
    // Right after the app starts the system may still be binding the accessibility service: wait for it once
    // (not once per field: with accessibility off that cost seconds on every status).
    val a11y = a11yLive(if (BackgroundOperation.state.value.accessibilityConnected) 0 else 1_500)
    if (a11y && !BackgroundOperation.state.value.accessibilityConnected) BackgroundOperation.refreshStatus()
    val s = BackgroundOperation.state.value
    val d = BackgroundOperation.previewDisplayFor(sid ?: "")
    val idleAt = BackgroundOperation.idleReleaseAt
    val data = JSONObject()
        .put("enabled", s.enabled)
        .put("connection", s.connection.name)
        .put("connection_error", s.connectionError ?: JSONObject.NULL)
        .put("needs_explicit_reconnect", s.needsExplicitReconnect)
        .put("display_id", s.displayId ?: JSONObject.NULL)
        .put("generation", s.generation)
        .put("owned_by_this_session", s.ownerSessionId != null && s.ownerSessionId == sid)
        .put("privilege", s.privilege.name)
        .put("needs_adb", false)
        .put("accessibility_connected", a11y)
        .put("host", com.kilon.agentvscreen.host.Liveness.json()
            .put("http_error", com.kilon.agentvscreen.host.HostService.server?.lastError ?: JSONObject.NULL))
        .put("accessibility_auto", com.kilon.agentvscreen.host.A11yHeal.let { h -> when {
            h.userOff(context) -> "用户关掉了，不再自动打开"
            h.blocked(context) -> "系统拦截写入：要在 系统设置 › 无障碍 里手动打开"
            h.wanted(context) -> "断开时自动重新打开（每分钟最多一次）"
            else -> "没开过（App 首页一键配置）"
        } })
        .put("user_stopped", BackgroundOperation.state.value.userStopped)
        .also { o -> if (BackgroundOperation.state.value.userStopped) o.put("user_stopped_note", BackgroundOperation.userStoppedMessage()) }
        .put("window_hidden_by_user", s.windowHidden)
        .put("remote", when (com.kilon.agentvscreen.api.ApiConfig.tailnetMode) {
            com.kilon.agentvscreen.host.Tailnet.Mode.BUILTIN -> com.kilon.agentvscreen.host.Tailnet.status.value.json()
            com.kilon.agentvscreen.host.Tailnet.Mode.SYSTEM -> JSONObject().put("mode", "system").put("ip", com.kilon.agentvscreen.host.HostService.server?.tailAddress ?: JSONObject.NULL)
            else -> JSONObject().put("mode", "off")
        })
        .also { o -> d?.let { o.put("accessibility_sees_display", BackgroundOperation.nodeRoots(it.displayId).isNotEmpty()) } }
        .put("user_takeover", s.takeoverSessionId != null)
        .put("screen", "${s.screenWidth}x${s.screenHeight}@${s.screenDpi}dpi")
        .put("ocr_engine", VScreenOcr.engineInfo())
        .put("idle", s.idle)
        .put("power_saving", s.powerSaving)
        .put("background_network", com.kilon.agentvscreen.core.VScreenPower.check(context).json())
        .put("app_rules", JSONObject().also { o -> VScreenStore.rules.value.forEach { (k, v) -> o.put(k, v.key) } })
        .put("keeps", JSONArray(VScreenRules.list().map { it.json() }))
        .put("idle_release_in_s", if (idleAt > 0) ((idleAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0) else JSONObject.NULL)
        .also { o -> BackgroundOperation.hostRestartNote?.let { o.put("host_restarted", it) } }
        .put("version", JSONObject().put("app", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("code", com.kilon.agentvscreen.BuildConfig.VERSION_CODE)
            .put("build", com.kilon.agentvscreen.BuildConfig.VERSION_NAME).put("protocol", VScreenDisplayService.PROTOCOL)
            .put("service_connected", VScreenRemoteDisplay.isConnected())
            .put("service_tasks_api", runCatching { VScreenRemoteDisplay.listTasksIfConnected(0) != null }.getOrDefault(false)))
    data.put("screen_off", com.kilon.agentvscreen.core.VScreenAwake.json(d))
    if (d != null) {
        val fp = d.diagnostics()
        data.put("frame_pump", JSONObject()
            .put("callback_count", fp.callbackCount)
            .put("acquire_success_count", fp.acquireSuccessCount)
            .put("acquire_null_count", fp.acquireNullCount)
            .put("acquire_failure_count", fp.acquireFailureCount)
            .put("convert_begin_count", fp.convertBeginCount)
            .put("convert_success_count", fp.convertSuccessCount)
            .put("convert_failure_count", fp.convertFailureCount)
            .put("publish_count", fp.publishCount)
            .put("pending_set_count", fp.pendingSetCount)
            .put("pending_present", fp.pendingPresent)
            .put("pending_close_failure_count", fp.pendingCloseFailureCount)
            .put("callback_last_at_ms", fp.callbackLastAtMs)
            .put("acquire_last_at_ms", fp.acquireLastAtMs)
            .put("convert_begin_last_at_ms", fp.convertBeginLastAtMs)
            .put("convert_success_last_at_ms", fp.convertSuccessLastAtMs)
            .put("convert_failure_last_at_ms", fp.convertFailureLastAtMs)
            .put("publish_last_at_ms", fp.publishLastAtMs)
            .put("pending_close_failure_last_at_ms", fp.pendingCloseFailureLastAtMs)
            .put("last_frame_at_ms", fp.lastFrameAtMs)
            .put("published_version", fp.publishedVersion)
            .put("last_received_at_ms", fp.lastReceivedAtMs)
            .put("last_converted_at_ms", fp.lastConvertedAtMs)
            .put("parked", fp.parked)
            .put("thread_alive", fp.threadAlive)
            .put("last_acquire_error", fp.lastAcquireError ?: JSONObject.NULL))
        data.put("recording", com.kilon.agentvscreen.core.VScreenRecorder.status(d.displayId))
        data.put("display_flags", JSONArray(VScreenDisplayService.flagNames(d.flags)))
        data.put("ime_guard", JSONObject()
            .put("keeps_main_focus", VScreenDisplayService.keepsMainFocus(d.flags))
            .put("focus_restored", BackgroundOperation.focusRestores)
            .put("restore_failed", BackgroundOperation.focusRestoreFailures)
            .put("note", if (VScreenDisplayService.keepsMainFocus(d.flags)) "副屏不抢主屏焦点（STEAL_TOP_FOCUS_DISABLED），键盘只跟着用户的主屏"
                else "本机没接受“不抢焦点”标志：每个副屏动作后若焦点被抢走，会立刻还给主屏（focus_restored 计数）"))
            .put("display_has_own_focus", d.ownFocus)
            .put("shell_owned", d.shellOwned)
        val act = VScreenShell.topActivity(d.displayId)
        act?.let { lastActivity[d.displayId] = it }
        data.put("current_app", act?.substringBefore('/') ?: JSONObject.NULL)
            .put("current_activity", act ?: JSONObject.NULL)
            .put("resume_hint", if (act != null)
                "副屏仍保留着 ${act.substringBefore('/')} 的页面：act 看一眼就能接着做，不必重新 launch"
            else "副屏还在但上面没有应用：用 launch 打开")
    } else if (s.displayId == null) {
        data.put("resume_hint", "当前没有副屏，这是正常的：直接 launch <应用名或包名> 就会自动建屏。不需要 adb 或无线调试，不要去配置它们")
    } else if (s.ownerSessionId != sid) {
        data.put("resume_hint", if (s.idle || s.ownerSessionId == null || !BackgroundOperation.isTaskActive(s.ownerSessionId))
            "副屏 ${s.displayId} 是上一轮留下的（空闲中）：act / launch 会直接接手它，页面保留"
        else "副屏 ${s.displayId} 正被另一个会话的任务使用，等它结束后再操作")
    }
    data.put("capabilities", capabilities(d))
    runCatching {
        val dv = VScreenShell.device()
        data.put("device", JSONObject().put("vendor", dv.vendor).put("brand", dv.brand).put("model", dv.model)
            .put("os", dv.os).put("sdk", dv.sdk).put("tips", JSONArray(VScreenShell.vendorHints())))
    }
    val (deg, sug) = degraded(d)
    data.put("degraded", deg).put("suggestions", sug)
    if (deg.length() > 0) {
        val fixes = JSONArray()
        for (i in 0 until deg.length()) {
            val code = deg.getString(i)
            fixes.put(JSONObject().put("code", code).put("command", "vscreen fix $code")
                .put("auto", code in AUTO_FIXABLE).put("what", FIX_WHAT[code] ?: "说明原因和用户要做的步骤"))
        }
        data.put("fixes", fixes).put("fix_hint", "vscreen fix（不带参数）会依次处理全部问题；会改系统设置的步骤先征得用户同意")
    }
    data.put("took_ms", SystemClock.elapsedRealtime() - t0)
    return ok(args, data)
}

/** Accessibility as it is right now (not the cached status), waiting up to [waitMs] for it to bind. */
internal fun VScreenOffloadHandler.a11yLive(waitMs: Long = 0): Boolean {
    val t0 = SystemClock.elapsedRealtime()
    while (true) {
        if (VScreenAccessibilityService.getInstance() != null) return true
        if (SystemClock.elapsedRealtime() - t0 >= waitMs) return false
        Thread.sleep(150)
    }
}

internal fun VScreenOffloadHandler.degraded(d: VScreenDisplay?): Pair<JSONArray, JSONArray> {
    val s = BackgroundOperation.state.value
    val deg = JSONArray(); val sug = JSONArray()
    val live = com.kilon.agentvscreen.host.Liveness
    if (com.kilon.agentvscreen.host.HostService.isAlive && !live.foreground) {
        deg.put("not_foreground_service")
        sug.put("ATVSX 的服务没能成为前台服务（${live.foregroundError ?: "原因未知"}）：它在后台会被冻结，命令会卡住直到用户打开它。请用户打开一次 ATVSX（会自动补上）。")
    } else if (live.recentlyFrozen()) {
        deg.put("frozen_in_background")
        sug.put("亮屏时系统在后台冻结过 ATVSX ${live.screenOnFreezes} 次（最近一次 ${live.lastFreezeMs / 1000} 秒），那段时间的命令会一直等。" +
            "请用户打开 ATVSX，点首页“后台运行受限”的“去设置”，把它设为允许自启动、关联启动和后台活动（荣耀/华为：应用启动管理 › 手动管理）。")
    }
    if (s.privilege.name != "READY") {
        deg.put("no_privilege")
        sug.put("Shizuku 不可用（${s.privilege.name}）：后台操作无法建屏和注入输入。fix no_privilege 会告诉用户怎么启动/授权。")
    } else if (s.enabled && (s.connection.name == "ERROR" || s.needsExplicitReconnect)) {
        deg.put("not_connected")
        sug.put("后台连接已断开（${s.connectionError ?: "需要重新连接"}）：fix not_connected 会重新连接。")
    }
    if (!a11yLive()) {
        deg.put("no_accessibility")
        sug.put("无障碍服务当前未连接（ATVSX 刚启动时系统可能还在绑定，几秒后 status 会更新）：元素列表只有 OCR 文字（图标按钮没有文字、中文输入只能走剪贴板粘贴）。需要更稳时，请用户在 系统设置 › 无障碍 › 已安装的应用 › ATVSX 打开。")
    } else if (d != null && BackgroundOperation.nodeRoots(d.displayId).isEmpty() && VScreenShell.topActivity(d.displayId) != null) {
        deg.put("accessibility_cannot_see_display")
        sug.put("无障碍已开启，但系统没有把副屏窗口交给它：控件树为空，元素列表只有 OCR 文字和截图。")
    }
    val pw = com.kilon.agentvscreen.core.VScreenPower.check(context)
    if (!pw.ok) {
        deg.put("background_network")
        sug.put("ATVSX 切到后台后系统可能切断它的网络（${pw.problems().joinToString("、")}），模型请求会报 connection abort。" +
            (if (s.privilege.name == "READY") "fix background_network 可用 Shizuku 自动加白名单（改系统设置，先征得用户同意）。"
             else "请用户手动设置：${com.kilon.agentvscreen.core.VScreenPower.manualSteps()}"))
    }
    if (d != null && !d.ownFocus) {
        deg.put("no_display_focus")
        sug.put("副屏没有独立输入焦点（需要 Android 14+）：key back/enter 可能送不到；无效时直接 tap 页面上的返回/搜索按钮。")
    }
    if (d != null && !d.shellOwned) {
        deg.put("app_owned_display")
        sug.put("Shizuku 副屏服务不可用，只能用 App 自建副屏：系统可能把应用开到主屏。请用户在设置里重新连接后台操作。")
    }
    return deg to sug
}

internal fun VScreenOffloadHandler.fix(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val what = args.positional.getOrNull(0) ?: "auto"
    val codes: List<String> = if (what == "auto") {
        val (deg, _) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
        (0 until deg.length()).map { deg.getString(it) }
    } else listOf(when (what) { "accessibility", "a11y" -> "no_accessibility"; "shizuku", "privilege" -> "no_privilege"
        "connect", "reconnect", "connection" -> "not_connected"; else -> what })
    val results = JSONArray()
    for (code in codes) {
        val (fixed, msg) = runCatching { fixOne(sid, code, args) }.getOrElse { false to "修复时出错：${it.message}" }
        results.put(JSONObject().put("code", code).put("fixed", fixed).put("message", msg))
    }
    BackgroundOperation.refreshStatus()
    val (deg, sug) = degraded(BackgroundOperation.previewDisplayFor(sid ?: ""))
    return ok(args, JSONObject()
        .put("checked", JSONArray(codes))
        .put("results", results)
        .put("degraded_now", deg).put("suggestions", sug)
        .put("message", if (codes.isEmpty()) "没有发现问题" else if (deg.length() == 0) "全部正常了" else "还有 ${deg.length()} 项没解决，按 suggestions 请用户处理"))
}

/** One repair attempt: (fixed, what happened / what the user must do). */
internal fun VScreenOffloadHandler.fixOne(sid: String?, code: String, args: OffloadArgs): Pair<Boolean, String> {
    val s = BackgroundOperation.state.value
    return when (code) {
        "not_connected" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name == "READY") true to "已重新连接" else false to "重连失败：${r.connectionError ?: r.connection.name}"
        }
        "no_privilege" -> {
            val steps = when (s.privilege.name) {
                "NOT_INSTALLED" -> "手机上没有安装 Shizuku：请用户安装 Shizuku，用无线调试启动后，打开 ATVSX 点“授权 Shizuku”。"
                "NOT_RUNNING" -> "Shizuku 服务没在运行（重启手机后会停）：请用户打开 Shizuku，用无线调试点“启动”，再回来执行 fix not_connected。"
                "NOT_AUTHORIZED" -> "ATVSX 还没获得 Shizuku 授权：请用户打开 ATVSX 点“授权 Shizuku”并允许。"
                else -> "Shizuku 已可用"
            }
            if (s.privilege.name == "READY") return true to steps
            var opened = ""
            if (args.hasFlag("open")) {
                val pm = context.packageManager
                val intent = listOf("moe.shizuku.privileged.api", "rikka.shizuku").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                opened = if (intent != null) {
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .fold({ "（已在主屏打开 Shizuku）" }, { "（打开 Shizuku 失败：${it.message}）" })
                } else "（没找到 Shizuku 应用）"
            }
            false to steps + opened
        }
        "no_accessibility" -> {
            if (a11yLive()) return true to "无障碍服务已连接"
            if (s.privilege.name != "READY") return false to "需要先修好 Shizuku（fix no_privilege），或请用户在 系统设置 › 无障碍 › 已安装的应用 › ATVSX 手动打开"
            val comp = "${context.packageName}/$A11Y_SERVICE"
            val cur = VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
                .takeIf { it.isNotEmpty() && it != "null" }
            val list = cur?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            val next = (list.filter { it != comp } + comp).joinToString(":")
            // Re-writing the list (ours removed then added) also re-binds a crashed service.
            val w = VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", next))
            VScreenShell.run(arrayOf("settings", "put", "secure", "accessibility_enabled", "1"))
            if (w.exitCode != 0) return false to "写入系统设置失败（${w.combined.take(120)}）；请用户在 系统设置 › 无障碍 里手动打开 ATVSX"
            if (a11yLive(4_000)) true to "已通过 Shizuku 打开 ATVSX 的无障碍服务"
            else false to "已写入设置，但服务 4 秒内没有连上：部分系统（荣耀/华为）会拦截，请用户在 系统设置 › 无障碍 里手动打开一次"
        }
        "accessibility_cannot_see_display", "a11y_not_ready" -> {
            val d = BackgroundOperation.previewDisplayFor(sid ?: "") ?: return false to "当前没有副屏"
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 3_000) {
                if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to "无障碍已能看到副屏控件"
                Thread.sleep(300)
            }
            if (code == "accessibility_cannot_see_display" && s.privilege.name == "READY") {
                val comp = "${context.packageName}/$A11Y_SERVICE"
                val cur = VScreenShell.run(arrayOf("settings", "get", "secure", "enabled_accessibility_services")).stdout.trim()
                val rest = cur.split(':').filter { it.isNotBlank() && it != "null" && it != comp }
                if (rest.isEmpty()) VScreenShell.run(arrayOf("settings", "delete", "secure", "enabled_accessibility_services"))
                else VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", rest.joinToString(":")))
                Thread.sleep(500)
                VScreenShell.run(arrayOf("settings", "put", "secure", "enabled_accessibility_services", (rest + comp).joinToString(":")))
                if (a11yLive(4_000)) {
                    val t1 = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - t1 < 3_000) {
                        if (BackgroundOperation.nodeRoots(d.displayId).isNotEmpty()) return true to "已重启无障碍服务，现在能看到副屏控件"
                        Thread.sleep(300)
                    }
                }
            }
            false to "无障碍仍看不到副屏控件：这个页面可能不提供控件。可继续用 OCR，或 release 后重新 launch"
        }
        "app_owned_display" -> {
            val r = BackgroundOperation.connect()
            if (r.connection.name != "READY") return false to "重连失败：${r.connectionError}"
            if (sid != null) BackgroundOperation.releaseNow(sid)
            true to "已重新连接并释放旧副屏；下一次 launch 会用 Shizuku 副屏重建（页面不保留）"
        }
        "background_network" -> {
            val pw = com.kilon.agentvscreen.core.VScreenPower
            if (pw.check(context).ok) return true to "后台网络不受限制"
            if (s.privilege.name != "READY") return false to "没有 Shizuku，无法自动设置。请用户手动：${pw.manualSteps()}"
            val (ok, msg) = pw.whitelist(context)
            if (ok) true to msg else false to "$msg。其余请用户手动：${pw.manualSteps()}"
        }
        "no_display_focus" -> false to "副屏没有独立输入焦点是系统限制（需要 Android 14+），无法修复；back 无效时直接点页面上的返回按钮"
        "not_foreground_service" -> false to "要用户打开一次 ATVSX：在前台时服务会自动补成前台服务。之后 status 的 host.foreground_service 应为 true"
        "frozen_in_background" -> {
            val (ok, msg) = com.kilon.agentvscreen.core.VScreenPower.whitelist(context)
            false to "系统白名单：$msg${if (ok) "（已全部生效）" else ""}。厂商自己的后台管理只能用户手动设：请用户打开 ATVSX，点“后台运行受限”的“去设置”，允许自启动、关联启动和后台活动"
        }
        else -> false to "不认识的问题代码：$code（可用：${FIX_WHAT.keys.joinToString("、")}）"
    }
}

// ── apps / launch ─────────────────────────────────────────────────────

internal fun VScreenOffloadHandler.apps(args: OffloadArgs): NativeOffloadResult {
    val filter = args.positional.getOrNull(0)?.lowercase()
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val arr = JSONArray()
    pm.queryIntentActivities(intent, 0)
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }
        .filter { filter == null || it.first.lowercase().contains(filter) || it.second.lowercase().contains(filter) }
        .sortedBy { it.first }
        .take(200)
        .forEach { (label, pkg) -> arr.put(JSONObject().put("name", label).put("package", pkg)) }
    return ok(args, JSONObject().put("apps", arr))
}

/** Which mechanism each capability is using right now, for `status`. */
internal fun VScreenOffloadHandler.capabilities(d: VScreenDisplay?): JSONObject {
    val s = BackgroundOperation.state.value
    val o = JSONObject()
    o.put("screen", when { d == null -> "无副屏"; d.shellOwned && d.trusted -> "Shizuku 服务创建的受信任副屏"; d.shellOwned -> "Shizuku 服务创建（未受信任）"; else -> "应用自建副屏（受限）" })
    o.put("tap_swipe", if (d?.shellOwned == true) "input -d（坐标由工具换算）；拖动/按住/多指走副屏服务注入" else "input -d")
    o.put("keys", if (d?.ownFocus == true) "带 displayId 注入（副屏有独立焦点）" else "带 displayId 注入，失败时 input -d")
    o.put("drag", if (d?.shellOwned == true) "副屏服务 POINTER（drag 多点、按住）" else "不可用（需要 Shizuku 副屏服务）")
    o.put("ocr", VScreenOcr.engineInfo())
    o.put("accessibility", if (a11yLive()) "已连接（控件树可用）" else "未连接（只用 OCR/视觉）")
    o.put("vision", "由调用方的模型看截图（本应用不调用任何模型）")
    o.put("orientation", if (d == null) "-" else "旋转 ${d.rotation * 90}°，画面转正 ${d.bufferTurn}°（${d.geometryJson().optString("turn_source")}）")
    o.put("clipboard", "非 ASCII 文字在无障碍写不进时走剪贴板，粘贴后恢复原剪贴板；系统不让读取时清空")
    o.put("stop", "默认只关副屏里的任务；--force 才 force-stop 整个应用")
    return o
}
