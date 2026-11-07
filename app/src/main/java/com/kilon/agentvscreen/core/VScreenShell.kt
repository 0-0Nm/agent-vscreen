package com.kilon.agentvscreen.core

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.host.ShizukuManager

/**
 * [vscreen] Privileged shell operations aimed at ONE display, run through
 * Shizuku (uid 2000 in ADB mode, no root).
 *
 * Every method takes the display id explicitly; nothing here ever falls
 * back to display 0. A ROM whose `input` cannot target a display gets a
 * clear NOT_SUPPORTED instead of a silent tap on the user's screen.
 */
internal object VScreenShell {
    private const val TAG = "VScreenShell"

    data class Outcome(val ok: Boolean, val code: String, val message: String)

    private fun ok(msg: String) = Outcome(true, "OK", msg)
    private fun fail(code: String, msg: String) = Outcome(false, code, msg)

    /** Test seam. */
    @Volatile internal var runner: (Array<String>, Long) -> ShizukuManager.ProcessResult =
        { argv, t -> ShizukuManager.runProcess(argv, null, null, t) }

    internal enum class InputForm { UNKNOWN, DASH_D, LONG_DISPLAY, NONE }
    @Volatile private var inputForm: InputForm = InputForm.UNKNOWN

    fun run(argv: Array<String>, timeoutMs: Long = 8_000L) = runner(argv, timeoutMs)

    // ── launch ─────────────────────────────────────────────────────────────

    /** ROOT: an app already on the background screen goes back to its first page (default). KEEP: just bring it forward. RESTART: force-stop first. */
    enum class LaunchMode { ROOT, KEEP, RESTART }

    fun launch(displayId: Int, pkg: String, activity: String?, mode0: LaunchMode = LaunchMode.ROOT, intent: List<String>? = null): Outcome {
        val clean = pkg.trim()
        if (clean.isEmpty()) return fail("INVALID_ARGS", "缺少包名")
        // v22 传送门: with an explicit intent (deep link / shortcut / learned page) the app is
        // always opened in a fresh task on the background screen (never "keep").
        val mode = if (intent != null && mode0 == LaunchMode.KEEP) LaunchMode.ROOT else mode0
        val explicit = activity?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.contains('/')) it else "$clean/$it" }
        val component: String = explicit ?: (if (intent != null) "" else resolveLauncher(clean))
            ?: return fail("APP_NOT_FOUND", "找不到 $clean 的启动入口（未安装，或没有桌面图标）")
        val targetPkg = if (component.isEmpty()) clean else component.substringBefore('/')
        val what = if (intent != null) intent.joinToString(" ") else component
        var prefix = ""
        if (mode == LaunchMode.RESTART) {
            val r = run(arrayOf("am", "force-stop", targetPkg), 8_000L)
            prefix = if (r.exitCode == 0) "已结束 $targetPkg 并冷启动；" else "结束 $targetPkg 失败（${r.combined.take(80)}），仍尝试启动；"
            Thread.sleep(300)
        } else {
            val taskId = taskIdOn(displayId, targetPkg)
            if (taskId != null) {
                if (mode == LaunchMode.KEEP) {
                    run(arrayOf("am", "start", "--display", displayId.toString(), "-n", component), 15_000L)
                    return ok("$targetPkg 已在副屏 $displayId 上（保持当前页面）")
                }
                // Back to the first page: drop the background screen's own task
                // (the app keeps running, the user's copy on the main screen is
                // untouched), then start a fresh task below.
                val removed = VScreenRemoteDisplay.removeTask(taskId, displayId)
                prefix = if (removed.getOrNull() == true) { Thread.sleep(250); "已关闭副屏上的旧页面栈，" }
                else "无法关闭旧页面栈（${removed.exceptionOrNull()?.message ?: "系统拒绝"}），新开一个任务，"
            }
        }
        // --windowingMode 1 = fullscreen on the target display. -W waits for the launch.
        // -f 0x18000000 = NEW_TASK | MULTIPLE_TASK: start a fresh task on the target
        // display instead of pulling the user's existing task (on the main screen)
        // to the front where it already lives.
        val target = intent ?: listOf("-n", component)
        val argv = arrayOf(
            "am", "start", "-W", "--display", displayId.toString(),
            "--windowingMode", "1", "-f", "0x18000000",
        ) + target.toTypedArray()
        var r = run(argv, 15_000L)
        if (r.combined.contains("Unknown option") || r.combined.contains("windowingMode")) {
            r = run(arrayOf("am", "start", "-W", "--display", displayId.toString(), "-f", "0x18000000") + target.toTypedArray(), 15_000L)
        }
        val out = r.combined.trim()
        if (r.exitCode != 0 || out.contains("Error") || out.contains("Status: error")) {
            if (intent != null && (out.contains("Permission Denial") || out.contains("not exported")))
                return fail("PORTAL_DENIED", "系统不允许从外部直接打开这个页面（$what）：${out.take(200)}。它是应用内部页面，只能从应用里点进去")
            if (intent != null && (out.contains("unable to resolve") || out.contains("Unable to resolve")))
                return fail("PORTAL_UNRESOLVED", "$targetPkg 不接受这个入口（$what）：没有页面能处理它。换一个入口，或从首页点进去")
            return fail("LAUNCH_FAILED", "在副屏 $displayId 启动 $what 失败：${out.ifBlank { "exit=${r.exitCode}" }}")
        }
        // Verify where it actually landed: on an untrusted display the system
        // quietly redirects the launch to the main screen and still says OK.
        var top: String? = null
        for (i in 0 until 6) {
            Thread.sleep(300)
            top = topPackage(displayId)
            if (top == targetPkg) return ok(if (intent != null) "${prefix}已在副屏 $displayId 直接打开 $what" else "${prefix}已在副屏 $displayId 启动 $component（首页）")
        }
        val onMain = topPackage(0) == targetPkg
        return if (onMain) {
            val why = VScreenDisplay.lastRemoteError?.let { "（Shizuku 副屏服务不可用：$it，只能用 App 自建副屏，系统不允许 shell 往上启动应用）" } ?: ""
            fail("LAUNCHED_ON_MAIN_SCREEN", "系统把 $targetPkg 开到了主屏，而不是副屏 $displayId$why。不要在主屏上继续操作，请告诉用户。")
        } else if (top == null) {
            ok("已请求在副屏 $displayId 启动 $what（无法确认副屏顶部应用，请 read 核实）")
        } else {
            fail("LAUNCH_NOT_ON_TOP", "副屏 $displayId 顶部是 $top，不是 $targetPkg；请 read 查看副屏状态")
        }
    }

    internal fun resolveLauncher(pkg: String): String? {
        val r = run(arrayOf("cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg), 8_000L)
        if (r.exitCode != 0) return null
        val comp = r.combined.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') && !it.contains(' ') }
            .lastOrNull() ?: return null
        if (!isResolver(comp)) return comp
        // Several launcher entries in one package (联系人 + 拨号 …): resolve-activity answers
        // with the system chooser, which on a background screen is often empty. Use the
        // package's own first entry instead.
        val q = run(arrayOf("cmd", "package", "query-activities", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg), 8_000L)
        return q.combined.lineSequence().map { it.trim() }
            .filter { it.startsWith("$pkg/") && !it.contains(' ') && !isResolver(it) }
            .firstOrNull()
    }

    private val RESOLVER = Regex("ResolverActivity|ChooserActivity", RegexOption.IGNORE_CASE)

    /** The system "open with" chooser rather than an app's own activity. */
    fun isResolver(component: String): Boolean = RESOLVER.containsMatchIn(component) || component.startsWith("android/")

    /**
     * v27: the system's own per-display task list (ActivityTaskManager via the Shizuku service),
     * null when the service is not connected or too old. Tests replace it.
     */
    @Volatile internal var taskSource: (Int) -> List<VScreenRemoteDisplay.DisplayTask>? =
        { id -> VScreenRemoteDisplay.listTasksIfConnected(id) }

    private val DISPLAY_HDR = Regex("^Display #(\\d+)")

    /**
     * Split `dumpsys activity activities` into per-display sections (trimmed lines). A section ends at
     * the next display header or at a line indented no deeper than its header (the dump's global tail:
     * ResumedActivity, focus, recents …, which belongs to no display).
     */
    internal fun sectionsOf(dump: String): Map<Int, List<String>> {
        val out = LinkedHashMap<Int, ArrayList<String>>()
        var cur: Int? = null
        var headerIndent = 0
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val indent = raw.length - raw.trimStart().length
            if (line.startsWith("Display #")) {
                cur = DISPLAY_HDR.find(line)?.groupValues?.get(1)?.toIntOrNull()
                headerIndent = indent
                cur?.let { out.getOrPut(it) { ArrayList() } }
                continue
            }
            if (cur != null && indent <= headerIndent) cur = null
            cur?.let { out[it]!!.add(line) }
        }
        return out
    }

    /**
     * Lines of `dumpsys activity activities` that belong to [displayId]. v27: also drops every task
     * block (and resumed line) whose task id is listed under another display, because some phones
     * print a global tail (with the main screen's tasks, e.g. Minis itself) inside the last section.
     */
    internal fun displaySection(displayId: Int): List<String>? {
        val r = run(arrayOf("sh", "-c", "dumpsys activity activities"), 8_000L)
        if (r.exitCode != 0) return null
        return isolate(sectionsOf(r.stdout), displayId)
    }

    internal fun isolate(sections: Map<Int, List<String>>, displayId: Int): List<String> {
        val mine = sections[displayId] ?: return emptyList()
        // A task listed in several sections belongs to the first one in dump order (its real display);
        // later copies are the global tail printed inside the last section.
        val owner = HashMap<Int, Int>()
        for ((disp, lines) in sections) for (t in taskIdsIn(lines)) owner.putIfAbsent(t, disp)
        val others = owner.filterValues { it != displayId }.keys
        if (others.isEmpty()) return mine
        val out = ArrayList<String>()
        var skipping = false
        for (line in mine) {
            if (isTaskLine(line)) skipping = TASK_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull() in others
            if (skipping) continue
            val t = RECORD_TASK.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (t != null && t in others) continue
            out += line
        }
        return out
    }

    private val TASK_LINE = Regex("Task(?:Record)?\\{[0-9a-f]+ #(\\d+)")
    private val RECORD_TASK = Regex(" t(\\d+)[ }]")

    /** Task ids listed in a display section (the tasks really on that display). */
    internal fun taskIdsIn(lines: List<String>): Set<Int> =
        lines.filter { isTaskLine(it) }.mapNotNull { TASK_LINE.find(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()

    /** A task's own header line ("* Task{…}"), not a line that merely mentions one (mLastFocusedRootTask=Task{…}). */
    internal fun isTaskLine(line: String): Boolean = line.startsWith("* Task{") || line.startsWith("* TaskRecord{") ||
        line.startsWith("Task{") || line.startsWith("TaskRecord{")

    /** "pkg/.Activity" resumed on [displayId], or null. Never the main screen's app (see [taskIdsIn]). */
    fun topActivity(displayId: Int): String? {
        taskSource(displayId)?.let { list -> return front(list)?.let { it.rootTop ?: it.component } }
        val lines = displaySection(displayId) ?: return null
        val tasks = taskIdsIn(lines)
        // No task on this display = nothing on it, whatever resumed line the dump prints nearby.
        if (tasks.isEmpty()) return null
        for (line in lines) {
            if (line.startsWith("topResumedActivity=") || line.startsWith("mResumedActivity:") ||
                line.startsWith("ResumedActivity:")) {
                val m = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)").find(line) ?: continue
                // The record names its task (t123): it must be one of this display's tasks. A resumed
                // line whose task is elsewhere is the dump's global "ResumedActivity" (main screen).
                val t = RECORD_TASK.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (t == null || t !in tasks) continue
                return "${m.groupValues[1]}/${m.groupValues[2]}"
            }
        }
        // No resumed activity (e.g. still launching): fall back to the top task's real activity.
        var under: Int? = null
        for (line in lines) {
            if (isTaskLine(line)) { under = TASK_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull(); continue }
            if (under == null || under !in tasks) continue
            if (line.startsWith("* Hist") || line.startsWith("Hist #") || line.startsWith("* ActivityRecord{")) {
                val m = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)").find(line) ?: continue
                return "${m.groupValues[1]}/${m.groupValues[2]}"
            }
        }
        return null
    }

    /**
     * v41: the task really in front. The system's list is not always in z-order (v40 field test: `stop` closed
     * Settings, still listed first behind a full-screen game): a visible app task wins over hidden ones.
     */
    internal fun front(list: List<VScreenRemoteDisplay.DisplayTask>): VScreenRemoteDisplay.DisplayTask? =
        list.firstOrNull { it.visible && it.isAppTask } ?: list.firstOrNull { it.visible } ?: list.firstOrNull()

    /** Package currently on top of [displayId]. */
    fun topPackage(displayId: Int): String? = topActivity(displayId)?.substringBefore('/')

    /**
     * The system's compatibility scale for [pkg]'s window on [displayId] (dumpsys mGlobalScale):
     * 1.0 = drawn at full size; >1 = the app renders smaller and the system scales it up
     * (vendor resolution downscaling). Null when not found.
     */
    fun windowScale(displayId: Int, pkg: String): Double? {
        var inWin = false
        var onDisp = false
        for (l in run(arrayOf("dumpsys", "window", "windows"), 8_000L).combined.lines()) {
            val t = l.trim()
            if (t.startsWith("Window #")) { inWin = t.contains(" $pkg/"); onDisp = false; continue }
            if (!inWin) continue
            if (t.startsWith("mDisplayId=")) onDisp = t.startsWith("mDisplayId=$displayId ")
            if (onDisp) Regex("mGlobalScale=([0-9.]+)").find(t)?.let { return it.groupValues[1].toDoubleOrNull() }
        }
        return null
    }

    /**
     * Ask the system not to render [pkg] at a reduced resolution (vendor/game downscaling). Takes
     * effect at the app's next start. Best effort; returns what each step said.
     */
    fun disableDownscale(pkg: String): String {
        fun step(label: String, argv: Array<String>): String {
            val r = run(argv, 8_000L)
            val msg = r.combined.trim().lines().firstOrNull { it.isNotBlank() }?.take(120)
            return "$label：" + if (r.exitCode == 0) (msg ?: "ok") else "失败（${msg ?: "exit ${r.exitCode}"}）"
        }
        return listOf(
            step("am compat disable DOWNSCALED", arrayOf("am", "compat", "disable", "DOWNSCALED", pkg)),
            step("cmd game set --downscale disable", arrayOf("cmd", "game", "set", "--downscale", "disable", pkg)),
        ).joinToString("；")
    }

    /** Phone maker and system, for vendor-specific hints. */
    data class Device(val vendor: String, val brand: String, val model: String, val os: String, val sdk: Int)

    @Volatile private var deviceCache: Device? = null

    fun device(): Device = deviceCache ?: run {
        fun prop(k: String) = runCatching { run(arrayOf("getprop", k), 3_000L).combined.trim() }.getOrDefault("")
        val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val b = (android.os.Build.BRAND ?: "").lowercase()
        val vendor = when {
            m.contains("vivo") || b.contains("vivo") || b.contains("iqoo") -> "vivo"
            m.contains("honor") || b.contains("honor") -> "honor"
            m.contains("huawei") -> "huawei"
            m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> "xiaomi"
            m.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> "oppo"
            m.contains("samsung") -> "samsung"
            else -> m.ifEmpty { "unknown" }
        }
        val os = when (vendor) {
            "vivo" -> prop("ro.vivo.os.build.display.id").ifEmpty { "OriginOS/FuntouchOS " + prop("ro.vivo.os.version") }
            "honor", "huawei" -> prop("ro.build.version.magic").ifEmpty { prop("ro.build.version.emui") }
            "xiaomi" -> prop("ro.mi.os.version.name").ifEmpty { "MIUI " + prop("ro.miui.ui.version.name") }
            "oppo" -> prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }
            else -> ""
        }.trim()
        Device(vendor, android.os.Build.BRAND ?: "", android.os.Build.MODEL ?: "", os, android.os.Build.VERSION.SDK_INT)
            .also { deviceCache = it }
    }

    /** Where the user turns off the system's resolution downscaling, by maker. */
    fun downscaleSettingHint(): String = when (device().vendor) {
        "vivo" -> "vivo/iQOO：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）；游戏还要在 游戏魔盒/电竞模式 里对该游戏关闭“超分辨率/插帧/画质增强”"
        "honor", "huawei" -> "荣耀/华为：设置 › 显示和亮度 › 屏幕分辨率 关闭“智能分辨率”或选最高档（这是整机设置；能否去掉该游戏的 0.85 比例未经实测，改完用 screen downscale status 看 aps.resolution 是否变成 1.0）"
        "xiaomi" -> "小米：设置 › 显示 › 屏幕分辨率 选最高档；游戏加速里关闭该游戏的“画质/分辨率”优化"
        "oppo" -> "OPPO/一加：设置 › 显示与亮度 › 屏幕分辨率 选最高档（关闭“智能切换”）"
        else -> "在系统 设置 › 显示 里把屏幕分辨率调到最高档，并关闭系统/游戏助手对该应用的降分辨率"
    }

    /** Maker-specific things that commonly break background operation, for status. */
    fun vendorHints(): List<String> = when (device().vendor) {
        "vivo" -> listOf(
            "开发者选项里打开“禁止权限监控”：否则 Shizuku 下的输入注入、wm/settings 命令可能被系统拦截（点击无效或命令失败）",
            "i管家 › 应用管理 › 权限管理 › 自启动 允许 Agent-VScreen 和 Shizuku；设置 › 电池 › 后台耗电管理 选“允许后台高耗电”，否则后台副屏会被杀",
            "Shizuku 用无线调试启动时，锁屏久了 OriginOS 可能断开无线调试：status 报 not_connected 时用 fix 重连",
            "iQOO 电竞模式/游戏魔盒会拦截通知和悬浮窗：在副屏跑游戏时不要对该游戏开“免打扰/极速模式”",
        )
        "honor", "huawei" -> listOf("设置 › 应用和服务 › 应用启动管理 把 Agent-VScreen 和 Shizuku 设为手动管理并全部允许")
        "xiaomi" -> listOf("开发者选项里打开“USB 调试（安全设置）”，否则输入注入会被拒绝；省电策略选“无限制”")
        "oppo" -> listOf("设置 › 电池 › 应用耗电管理 允许 Agent-VScreen 和 Shizuku 后台运行")
        else -> emptyList()
    }

    /** Undo [disableDownscale]. */
    fun resetDownscale(pkg: String) {
        runCatching { run(arrayOf("am", "compat", "reset", "DOWNSCALED", pkg), 8_000L) }
        runCatching { run(arrayOf("cmd", "game", "reset", pkg), 8_000L) }
    }

    /** Orientation the app's entry activity declares: true = landscape, false = portrait, null = free / unknown. */
    /** v48: the device's natural orientation (rotation 0) is landscape (a tablet) — not the screen's current state. */
    fun naturalLandscape(context: Context): Boolean = runCatching {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val disp = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val p = android.graphics.Point(); @Suppress("DEPRECATION") disp.getRealSize(p)
        (p.x > p.y) != (disp.rotation % 2 == 1)
    }.getOrDefault(false)

    fun appOrientation(context: Context, pkg: String, activity: String?): Boolean? = runCatching {
        val pm = context.packageManager
        val comp = activity?.let { a ->
            if (a.contains('/')) android.content.ComponentName.unflattenFromString(a)
            else android.content.ComponentName(pkg, if (a.startsWith(".")) pkg + a else a)
        } ?: pm.getLaunchIntentForPackage(pkg)?.component ?: return@runCatching null
        when (pm.getActivityInfo(comp, 0).screenOrientation) {
            0, 6, 8, 11 -> true   // landscape, sensorLandscape, reverseLandscape, userLandscape
            1, 7, 9, 12 -> false  // portrait, sensorPortrait, reversePortrait, userPortrait
            else -> null
        }
    }.getOrNull()

    /** Id of [pkg]'s task on [displayId] (the background screen's own copy), or null. */
    fun taskIdOn(displayId: Int, pkg: String): Int? {
        taskSource(displayId)?.let { list ->
            return list.firstOrNull { it.pkg == pkg || it.component?.substringBefore('/') == pkg }?.taskId
        }
        val lines = displaySection(displayId) ?: return null
        val re = Regex("Task(?:Record)?\\{[0-9a-f]+ #(\\d+)")
        for (line in lines) {
            val m = (if (isTaskLine(line)) re.find(line) else null) ?: continue
            if (line.contains("A=$pkg") || line.contains(":$pkg ") || line.contains(":$pkg}") || line.contains("A=${pkg} ")) {
                return m.groupValues[1].toIntOrNull()
            }
        }
        // Older dumps put the package on the activity lines under the task, not on the task line.
        var current: Int? = null
        for (line in lines) {
            if (isTaskLine(line)) re.find(line)?.let { current = it.groupValues[1].toIntOrNull() }
            if (current != null && (line.startsWith("* Hist") || line.startsWith("Hist #")) && line.contains(" $pkg/")) return current
        }
        return null
    }

    /**
     * Close [pkg]'s window on the background screen only (remove its task there). The app process
     * and any page of it on the main screen are left alone. Null when the task cannot be found.
     */
    fun closeOnDisplay(displayId: Int, pkg: String?): Outcome? {
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId) ?: return ok("副屏 $displayId 上没有应用")
        val task = taskIdOn(displayId, target) ?: return null
        // v27: never a task the main screen lists (the v26 field test closed Minis' own main task this way).
        if (displayId == 0 || VScreenHandoff.tasksOn(0).any { it.taskId == task }) return fail("MAIN_SCREEN_TASK", "$target 的这个任务在主屏上，不在副屏，没有关闭")
        val r = VScreenRemoteDisplay.removeTask(task, displayId)
        return if (r.getOrNull() == true) ok("已关闭副屏上的 $target（只移除副屏里的这个任务，应用进程和主屏上的页面不受影响）") else null
    }

    /**
     * Packages the background screen must never force-stop: Minis itself (the agent runs in it),
     * the launcher and the system UI. Set from BackgroundOperation.init.
     */
    @Volatile var protectedPackages: Set<String> = setOf("com.android.systemui", "android")

    fun isProtected(pkg: String?): Boolean = pkg != null && pkg in protectedPackages

    /** Force-stop [pkg] (default: the app on top of [displayId]). Note: this stops the app everywhere. */
    fun stop(displayId: Int, pkg: String?): Outcome {
        val target = pkg?.trim()?.takeIf { it.isNotEmpty() } ?: topPackage(displayId)
            ?: return ok("副屏 $displayId 上没有应用")
        if (isProtected(target)) return fail("PROTECTED_APP", "$target 是 Agent-VScreen 自己、桌面或系统界面，不能 force-stop（会中断任务或打断用户）")
        val r = run(arrayOf("am", "force-stop", target), 8_000L)
        return if (r.exitCode == 0) ok("已结束 $target（force-stop 会结束该应用的所有页面，包括主屏上的）")
        else fail("OPERATION_FAILED", r.combined)
    }

    /**
     * Before a screen is released: close every app task that is really on [displayId] (by task id,
     * so the app process and its main-screen pages stay). Force-stop is only a last resort for a task
     * that would not close, and never for a protected package or one also shown on the main screen.
     */
    fun closeAllOnDisplay(displayId: Int): Outcome {
        if (displayId == 0) return fail("MAIN_SCREEN", "不会关闭主屏上的任务")
        val tasks = VScreenHandoff.tasksOn(displayId)
        if (tasks.isEmpty()) return ok("副屏 $displayId 上没有应用")
        val mainIds = VScreenHandoff.tasksOn(0).map { it.taskId }.toSet()
        val notes = ArrayList<String>()
        for (t in tasks) {
            // Belt and braces: a task the main screen lists is never closed from here.
            if (t.taskId in mainIds) { notes += "${t.pkg} 的任务在主屏上，没有关闭"; continue }
            if (VScreenRemoteDisplay.removeTask(t.taskId, displayId).getOrNull() == true) { notes += "已关闭 ${t.pkg}"; continue }
            if (isProtected(t.pkg)) { notes += "${t.pkg} 受保护，没有结束"; continue }
            if (taskIdOn(0, t.pkg) != null) { notes += "${t.pkg} 在主屏也有页面，没有结束"; continue }
            val r = run(arrayOf("am", "force-stop", t.pkg), 8_000L)
            notes += if (r.exitCode == 0) "已结束 ${t.pkg}" else "结束 ${t.pkg} 失败"
        }
        return ok(notes.joinToString("；"))
    }

    /** What the input method is attached to right now (best effort, from dumpsys input_method). */
    fun imeState(): org.json.JSONObject {
        val o = org.json.JSONObject()
        val text = run(arrayOf("dumpsys", "input_method"), 6_000L).stdout
        fun first(re: String) = Regex(re).find(text)?.groupValues?.get(1)
        first("(?:mCurTokenDisplayId|curTokenDisplayId|mImeDisplayId|imeDisplayId)=(-?\\d+)")?.toIntOrNull()?.let { o.put("ime_display", it) }
        first("(?:mCurClient|curClient)=[^\\n]*?displayId=(-?\\d+)")?.toIntOrNull()?.let { o.put("client_display", it) }
        first("(?:mInputShown|inputShown|mIsInputViewShown)=(true|false)")?.let { o.put("shown", it == "true") }
        first("(?:mCurFocusedWindow|curFocusedWindow)=Window\\{[0-9a-f]+ u\\d+ ([^ }]+)")?.let { o.put("focused_window", it) }
        return o
    }

    // ── input ──────────────────────────────────────────────────────────────

    /**
     * Frame pixel -> input pixel for a display (set by BackgroundOperation). Every coordinate the
     * agent or the preview gives is in the upright frame; this is the one place it is converted.
     */
    @Volatile internal var mapper: ((Int, Float, Float) -> Pair<Float, Float>)? = null
    private fun mx(d: Int, x: Int, y: Int): Pair<Int, Int> =
        mapper?.invoke(d, x.toFloat(), y.toFloat())?.let { Math.round(it.first) to Math.round(it.second) } ?: (x to y)
    /** Where a frame point lands in input space (for diagnostics). */
    fun inputPoint(displayId: Int, x: Int, y: Int): Pair<Int, Int> = mx(displayId, x, y)

    fun tap(displayId: Int, x: Int, y: Int): Outcome {
        val (ix, iy) = mx(displayId, x, y)
        return input(displayId, listOf("tap", "$ix", "$iy"))
    }

    fun longPress(displayId: Int, x: Int, y: Int, ms: Int): Outcome {
        val (ix, iy) = mx(displayId, x, y)
        return input(displayId, listOf("swipe", "$ix", "$iy", "$ix", "$iy", "${ms.coerceIn(300, 5000)}"))
    }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Outcome {
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        return input(displayId, listOf("swipe", "$a", "$b", "$c", "$e", "${ms.coerceIn(50, 5000)}"))
    }

    /**
     * Held finger [pointerId] (multi-touch across calls): op "down" | "move" | "up" | "cancel".
     * Needs the Shizuku display service (protocol 4); there is no `input` fallback for held fingers.
     */
    fun finger(displayId: Int, op: String, pointerId: Int, x: Int, y: Int): Pair<Outcome, Int> {
        val code = when (op) { "down" -> 0; "move" -> 1; "up" -> 2; else -> 3 }
        val (ix, iy) = if (x < 0 || y < 0) (-1 to -1) else mx(displayId, x, y)
        val r = VScreenRemoteDisplay.pointer(displayId, code, pointerId, ix.toFloat(), iy.toFloat())
        val v = r.getOrNull() ?: return fail("NOT_SUPPORTED", "按住/多指需要副屏服务（Shizuku）：${r.exceptionOrNull()?.message}") to 0
        return (if (v.first) ok("手指 $pointerId $op ($x,$y)") else fail("INPUT_FAILED", "手指 $pointerId $op 注入失败（这根手指可能没按下）")) to v.second
    }

    /**
     * Precise drag: injected through the Shizuku service with a still hold
     * at the end (no fling). Falls back to `input swipe` with the hold folded
     * into a slower swipe.
     */
    fun drag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Outcome {
        val (a, b) = mx(displayId, x1, y1); val (c, e) = mx(displayId, x2, y2)
        val r = VScreenRemoteDisplay.injectDrag(displayId, a, b, c, e, durationMs.coerceIn(50, 5000), holdMs.coerceIn(0, 3000))
        if (r.getOrNull() == true) return ok("副屏 $displayId：拖动完成（注入）")
        AppLogger.warning(TAG, "injectDrag unavailable (${r.exceptionOrNull()?.message}), using input swipe")
        return input(displayId, listOf("swipe", "$a", "$b", "$c", "$e", "${(durationMs + holdMs).coerceIn(50, 5000)}"))
    }

    data class KeyResult(val outcome: Outcome, val method: String)

    /** Key event on [displayId]: injected with the display id (service), else `input -d`. */
    fun keyWithMethod(displayId: Int, key: String): KeyResult {
        val code = KEYS[key.trim().lowercase()] ?: key.trim().toIntOrNull()
            ?: return KeyResult(fail("INVALID_ARGS", "未知按键 $key（可用：${KEYS.keys.joinToString()} 或数字 keycode）"), "none")
        val r = VScreenRemoteDisplay.injectKey(displayId, code)
        if (r.getOrNull() == true) return KeyResult(ok("副屏 $displayId：按键 $key 已注入"), "inject(displayId=$displayId)")
        AppLogger.warning(TAG, "injectKey unavailable (${r.exceptionOrNull()?.message}), using input")
        return KeyResult(input(displayId, listOf("keyevent", "$code")), "input -d $displayId keyevent")
    }

    fun key(displayId: Int, key: String): Outcome = keyWithMethod(displayId, key).outcome

    /** ASCII only. Chinese goes through accessibility SET_TEXT or clipboard paste (see controller). */
    fun asciiText(displayId: Int, text: String): Outcome {
        if (text.any { it.code > 126 || it.code < 32 }) {
            return fail("NON_ASCII", "input text 只能输入 ASCII 字符")
        }
        // argv goes straight to the `input` process (no shell), so only the
        // space needs its %s form.
        val escaped = text.replace(" ", "%s")
        return input(displayId, listOf("text", escaped))
    }

    private fun input(displayId: Int, sub: List<String>): Outcome {
        val form = resolveInputForm(displayId)
        val argv = when (form) {
            InputForm.DASH_D -> arrayOf("input", "-d", "$displayId") + sub
            InputForm.LONG_DISPLAY -> arrayOf("input", "--display", "$displayId") + sub
            else -> return fail(
                "NOT_SUPPORTED",
                "这台设备的 input 命令不支持指定显示屏，已拒绝操作（不会改为点击主屏）",
            )
        }
        val r = run(argv, 10_000L)
        return if (r.exitCode == 0) ok("副屏 $displayId：${sub.first()} 完成")
        else fail("INPUT_FAILED", "副屏 $displayId 输入失败：${r.combined.ifBlank { "exit=${r.exitCode}" }}")
    }

    internal fun resolveInputForm(displayId: Int): InputForm {
        if (inputForm != InputForm.UNKNOWN) return inputForm
        val help = run(arrayOf("input"), 5_000L).combined
        val lower = help.lowercase()
        var form = when {
            help.isBlank() -> InputForm.UNKNOWN
            lower.contains("--display") -> InputForm.LONG_DISPLAY
            Regex("-d\\s+<?display").containsMatchIn(lower) || Regex("\\[-d ").containsMatchIn(lower) -> InputForm.DASH_D
            !lower.contains("display") -> InputForm.NONE
            else -> InputForm.UNKNOWN
        }
        if (form == InputForm.UNKNOWN) {
            // Harmless probe: a no-op key (KEYCODE_UNKNOWN = 0) on OUR display.
            form = when {
                run(arrayOf("input", "-d", "$displayId", "keyevent", "0"), 5_000L).exitCode == 0 -> InputForm.DASH_D
                run(arrayOf("input", "--display", "$displayId", "keyevent", "0"), 5_000L).exitCode == 0 -> InputForm.LONG_DISPLAY
                else -> InputForm.NONE
            }
        }
        AppLogger.info(TAG, "input display form = $form")
        inputForm = form
        return form
    }

    // ── IME policy (via app_process helper) ───────────────────────────────

    /**
     * Keep the soft keyboard from popping up for the background screen.
     * Untrusted virtual displays default to FALLBACK_DISPLAY, which would
     * open the keyboard on the user's MAIN screen whenever the background
     * app focuses a text field. We set DISPLAY_IME_POLICY_HIDE through a
     * tiny helper launched with app_process under the Shizuku shell uid
     * (the same technique scrcpy uses for its server), because the hidden
     * IWindowManager call is not reachable from the app process.
     */
    fun hideImeOn(context: Context, displayId: Int): Outcome {
        val apk = context.applicationInfo.sourceDir
        val r = run(arrayOf(
            "sh", "-c",
            "CLASSPATH='$apk' app_process /system/bin ${VScreenShellMain::class.java.name} ime-hide $displayId",
        ), 10_000L)
        return if (r.exitCode == 0 && r.stdout.contains("OK")) ok("键盘策略：副屏不弹出键盘")
        else fail("IME_POLICY_FAILED", "设置副屏键盘策略失败：${r.combined.take(300)}")
    }

    private val KEYS = mapOf(
        "back" to 4, "enter" to 66, "tab" to 61, "del" to 67, "delete" to 67,
        "backspace" to 67, "up" to 19, "down" to 20, "left" to 21, "right" to 22,
        "menu" to 82, "search" to 84, "paste" to 279, "escape" to 111,
        // HOME / APP_SWITCH are deliberately absent: on a display without
        // system decorations they can bring up the launcher on the MAIN screen.
    )

    internal fun resetForTest() { inputForm = InputForm.UNKNOWN }
}
