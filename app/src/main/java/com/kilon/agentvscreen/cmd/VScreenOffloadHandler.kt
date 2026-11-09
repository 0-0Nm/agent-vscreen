package com.kilon.agentvscreen.cmd

import android.content.Context
import com.kilon.agentvscreen.host.AppLogger
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenStore
import org.json.JSONObject

/**
 * [vscreen] `vscreen` — the agent's background-screen tool (v49).
 *
 * One verb does the work: `act` runs a segment of steps on the chat's background screen and returns four fixed
 * blocks (what was done / what happened meanwhile / the screen now / attention). Realtime apps whose pause and
 * resume steps were written down (`note pause|resume`) are resumed at the start of a segment and paused at its end,
 * so the agent thinks while the app stands still. The phone reports facts only: no verdicts on progress, success
 * or meaning. Everything runs on the virtual display owned by the calling chat; there is no main-screen fallback.
 *
 * Files: VScreenAct (act, launch), VScreenSteps (the step language), VScreenReport (the four blocks),
 * VScreenOpenCommands (launch internals, handoff, stop, release), VScreenNotes (note, keep),
 * VScreenHelpers (spots, zoom, collect), VScreenScreenCommands (status, apps, fix).
 */
class VScreenOffloadHandler(internal val context: Context) : NativeOffloadHandler {

    companion object {
        internal const val TAG = "VScreenOffload"
        const val DEFAULT_SESSION = "default"
        internal const val SHOT_DIR = "vscreen"

        const val QUICK_HELP = """vscreen — ATVSX：在看不见的副屏里操作应用，不占用户主屏（完整说明：help all；单条：help act）
  launch 应用名            打开应用，返回四段（做了什么 / 这段时间 / 现在 / 注意）
  act "步骤; 步骤; …"      做一段操作，返回四段；act 不带步骤 = 看一眼
        步骤：tap 目标 | press 目标 | swipe x1 y1 x2 y2 | drag x1 y1 x2 y2 … | scroll down | type 文字 | key back | wait 条件 | use 段名
        目标：x y | #N（【现在】列表的编号）| 画面上的字；动作后可加 until 条件（text:字 gone:字 change still color:x,y=#RRGGBB app:名）
        --stop 条件  --watch 2s  --keep notify|wake  --save 段名  --confirmed
  dump [--max-nodes N] [--max-depth N]
        只读当前副屏目标应用的原生无障碍树；最多 200 节点、深度 20、JSON 64KiB，超出会明确 truncated。无树返回结构化 unavailable；不读取主屏、不发输入、不请求新帧。
  note [文字] | note pause|resume "步骤" | note realtime on|off | note delete 编号
  keep | keep off 名字      查看 / 停止本轮之后的盯守
  events [--wait 30s]       取盯守（--keep wake）发来的事件
  title 文字 | done         告诉用户你在做什么（悬浮窗上显示）| 这一轮做完了（副屏让出，页面保留）
  stop | release | handoff in|out | status | apps 关键词 | spots | zoom 区域 | collect | fix
一步没达到预期（until 没等到、字没输进去）这段就停在那一步，后面的不做，【注意】里写明现在的画面。
输出很长时完整结果也存在截图目录的 last.txt（路径见 help all），可以用 head / sed 分段看。"""

        const val HELP = """vscreen — ATVSX 后台操作：在看不见的副屏里操作其他应用，不占用户主屏。截图由你（调用方的模型）看，手机只报事实。
不需要 adb 或无线调试：特权走 ATVSX 自己的 Shizuku 通道。不要去连 adb、配无线调试或改 adb 密钥。没有副屏时直接 launch，会自动建屏。

返回的四段（launch / act / handoff in）：
  【做了什么】 每一步做了什么、等到了什么或等到时间没等到、哪个停止条件触发了（只有事实，没有“成功/失败”的判断）
  【这段时间】 从你上次拿到结果到现在（包括你思考的时间）：出现/消失/出现又消失的字，画面哪里变了多久；有变化时附拼图
  【现在】     前台界面、副屏尺寸、截图路径（边上标着副屏坐标）和编号元素：#N “字” (x,y) 可点/可输入/…；同一页上只列新出现或变了的元素（编号不变），不带步骤的 act 列出全部
  【注意】     主屏被动过、付费按钮没点、用户接管、应用限制、失败原因；没有就是“无”

命令：
  launch <应用名|包名> [--landscape|--portrait] [--restart]
        打开应用（找不到就 apps 关键词）。副屏方向按应用声明的方向建；被系统缩放会自动处理。
        返回四段；【做了什么】里还会列出你以前给这个应用记的 note、存的段、暂停/继续步骤。
        --restart 冷启动；--landscape / --portrait 指定副屏方向
  act ["步骤; 步骤; …"] [--stop "<条件>"] [--watch 时长] [--paused] [--keep notify|wake] [--for 时长] [--save 名字] [--confirmed]
        做一段操作（最多 40 步，用 ; 或换行分开），返回四段。不带步骤 = 看一眼（等画面稳定，最多 3 秒）。
        步骤：
          tap 目标 | press 目标 [时长，如 1.2s；纯数字是毫秒] | swipe x1 y1 x2 y2 [时长] | drag x1 y1 x2 y2 [x3 y3 …] [时长]
          scroll up|down|left|right [#N | x y] | type 文字 | type+ 文字（追加） | key back|enter|home|tab|del|search|… | back | enter
          wait <条件> [or <条件>] [最长时长] | wait 2s | use 段名
          目标 = x y | #N（上次【现在】列表里的编号）| 画面上的字（先找完全一样的，再找包含的；只写一个数＝点画面上的这个数字）
        一步没达到预期就停在这一步，后面的步骤不做（【注意】里写现在的画面）：until 到时间没等到；type 没有获得焦点的输入框、
        或输入后框里没有你的字、或无障碍没开确认不了。type 前先点输入框，【现在】里它标“可输入 有焦点”
        每个动作后可加 until <条件> [or <条件>] [最长时长，默认 5s]；没写就等画面稳定（最多 3 秒；实时应用不等）。
        条件：text:字 | gone:字 | change[:x1,y1,x2,y2] | still[:区域] | stable | color:x,y=#RRGGBB[~容差] | app:包名或界面名
        --stop 条件：整段一直盯着，满足就停下，后面的步骤不做
        --watch 时长：步骤做完再看这么久（实时画面）；实时应用默认看 1s
        --paused：记了暂停/继续步骤的应用，这一段不继续也不暂停（画面保持暂停时看/点菜单）
        --keep notify|wake [--for 时长]：本轮结束后继续盯 --stop 的条件（默认 1 小时，最长 12 小时），
        看到就通知用户（notify）或发一条事件给调用方（wake，用 events 取），然后结束
        --save 名字：把这段步骤存成这个应用的段，以后 use 名字
        --confirmed：付费/不可撤销的按钮，用户在本轮明确同意后才加（否则不点，【注意】里是 CONFIRM_REQUIRED）
        例：act "tap 搜索; type 耳机; key enter until text:综合"
            act "drag 300 900 800 900" --watch 2s
            act --stop "text:到货" --keep notify --for 2h
  dump [--max-nodes N] [--max-depth N]
        只读当前已拥有副屏的原生无障碍树；不会连接、接管、唤醒、取消 idle geometry，也不会读取主屏。
        --max-nodes 1..200；--max-depth 0..20；无活动副屏返回 NO_OWNED_SCREEN。
  note [文字] | note pause "<步骤>" | note resume "<步骤>" | note realtime on|off | note delete <编号|pause|resume|段名>
        给当前应用记笔记（不带参数 = 列出笔记、段和暂停/继续步骤）。下次 launch 会列出来。
        暂停和继续都记了：之后每段 act 先做继续步骤，做完、观察完再做暂停步骤（实时游戏变成回合制）
  events [--wait 时长]
        取盯守（act --keep wake）看到条件时发来的事件；--wait 最多等这么久（默认不等，最长 120s）。
        这个会话没有正在运行的 --keep wake 盯守时不等，直接说明
  title [文字]
        在悬浮窗上显示你正在做什么（最多 40 字，给用户看；不带文字 = 清除；这一轮结束时自动清除）
  done
        这一轮做完了：副屏马上省电暂停、页面保留 10 分钟，别的会话可以接手（不 done 的话安静满 N 分钟自动结束，N 在 App 里设）
  keep | keep off <名字|all>
        列出 / 停止本轮之后的盯守（用 act --stop … --keep 开始）
  stop [包名] [--force]
        关掉副屏上的应用（--force 结束整个应用，主屏上的也会被结束：先征得用户同意）
  release
        立即释放副屏
  handoff in [包名] | handoff out [包名] [--note 说明]
        in：把用户主屏上的应用移到副屏接着做（返回四段）；out：交还到主屏，需要用户本人操作时用
  status
        后台操作是否可用、副屏、权限、应用限制、正在盯守的条件
  apps [关键词]
        已安装应用和包名
  spots [x1,y1,x2,y2] [--color #RRGGBB]
        本地按颜色找色块（不调模型），列出中心坐标和标注图
  zoom x1,y1,x2,y2 [--scale 倍数]
        放大看一块区域（图边上标着副屏坐标）
  collect [--pages 页数] [--until 文字]
        从当前位置往下一页页滚，收集每一行文字（去重）
  fix [code] [--open]
        修复 status 里列出的问题（会改系统设置或在主屏打开设置页：先征得用户同意）

用户接管或重启之后：下一次 launch 或 act 返回的【现在】就算重新看过画面，不需要别的命令（没有 resume）。
用户在悬浮窗上点“停止”时，只有用户能交还；关掉悬浮窗只是释放副屏，之后直接 launch。
命令行的完整输出也存在 VSCREEN_SHOT_DIR 下的 last.txt（默认 TMPDIR/vscreen-shots/last.txt），输出太长或重定向出问题时读这个文件。

规则：
- 副屏不可用时命令直接失败，不会改到主屏执行。
- 用户点“接管”时动作会等待（最多 15 秒，之后 USER_TAKEOVER）；交还后第一个 act 会先返回现在的画面（REREAD_REQUIRED），看清再继续。
- 用户在悬浮窗点“停止 AI”后，所有操作返回 USER_STOPPED（写明几点停的）：停下来，把返回里“怎么解除”那句转告用户（通知栏“允许继续”/悬浮窗“交还 AI”/App 首页），不要重试。用户只是关掉或收起悬浮窗不会停你。
- 用户可以把应用设成“禁止操作”（APP_BLOCKED）或“只读”（APP_READ_ONLY：能看、滚动、返回）。这是用户的决定，不要绕过，也不要换个入口进同一个应用。
- 涉及隐私（私信、通讯录、相册、余额）或高风险（支付、转账、发送、删除、授权、登录）的步骤，先问用户，答复后再做。
- fix 会改系统设置，先征得用户同意。
- 一个副屏同一时间只属于一个会话（请求头 X-VScreen-Session，默认 default）。别的会话在用时返回 BUSY，写明归谁、多久后自动让出；
  那个会话 done 或 release 后，或它安静满 N 分钟后，你就能接手（页面保留）。
- --keep 在你不在时替你盯：告诉用户你在盯什么、盯多久、看到后会怎样、怎么停（keep off）。"""

        /** Last activity observed per display (status shows it). */
        internal val lastActivity = HashMap<Int, String>()
        /** Per chat: when its last four-block return was made (epoch ms) — the start of the next 【这段时间】. */
        internal val lastReturn = HashMap<String, Long>()
        /** Per chat: the element list of its last 【现在】 (what #N means). */
        internal val lastElements = HashMap<String, ElSet>()

        /** Options every command takes (help, output shaping). */
        internal val GLOBAL_OPTIONS: Set<String> = setOf("help") + OffloadOutput.OUTPUT_FLAGS
        private val optionCache = HashMap<String, Set<String>>()

        /** Commands that do not look at or touch the app on the screen (no app-rule check). */
        internal val RULE_FREE = setOf("status", "apps", "release", "stop", "fix", "help", "handoff", "note", "keep", "launch", "act", "events", "done", "title")

        /** Drop everything remembered about [displayId] (called when BackgroundOperation releases it). */
        internal fun forgetDisplay(displayId: Int) {
            synchronized(lastActivity) { lastActivity.remove(displayId) }
            com.kilon.agentvscreen.core.VScreenPerception.stop(displayId)
            com.kilon.agentvscreen.core.VScreenRecorder.stop(displayId)
        }

        init { BackgroundOperation.onScreenReleased { forgetDisplay(it) } }
    }

    /** `help <命令>`: that command's lines from [HELP] (all of them for `help all` or an unknown name). */
    internal fun helpFor(cmd: String?): String {
        if (cmd == null || cmd == "all") return HELP
        val out = ArrayList<String>()
        var take = false
        for (l in HELP.lines()) {
            val starts = l.startsWith("  $cmd ") || l == "  $cmd"
            if (starts) { take = true; out += l; continue }
            if (take) { if (l.startsWith("        ")) out += l else take = false }
        }
        return if (out.isEmpty()) "没有叫 $cmd 的命令。\n\n$QUICK_HELP" else out.joinToString("\n")
    }

    /** Nothing a command does may take the app down: any throwable becomes an error result. */
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult = try {
        handleCommand(request)
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "vscreen command crashed: ${t.javaClass.simpleName}: ${t.message} / ${t.cause?.message}")
        errText("INTERNAL_ERROR", "工具内部错误（${t.javaClass.simpleName}: ${(t.cause?.message ?: t.message ?: "").take(300)}），命令没有执行。请把这段报错发给开发者")
    }

    private fun handleCommand(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val sub = argv.firstOrNull()
        if (sub == null || sub == "--help" || sub == "-h") return NativeOffloadResult(0, QUICK_HELP + "\n")
        if (sub == "help") return NativeOffloadResult(0, helpFor(argv.getOrNull(1)) + "\n")
        if (argv.drop(1).contains("--help")) return NativeOffloadResult(0, helpFor(sub) + "\n")
        val sid = request.sessionId?.takeIf { it.isNotBlank() } ?: DEFAULT_SESSION
        takeAdjusted()
        return try {
            val rest = VScreenOptions.normalize(sub, argv.drop(1))
            checkOptions(sub, rest)
            val args = VScreenOptions.args(sub, rest)
            BackgroundOperation.refreshStatus()
            enforceRules(sub)
            dispatch(sub, args, sid)
        } catch (e: ActionError) {
            errText(e.code, e.message ?: e.code)
        } catch (e: OffloadArgs.BadOption) {
            errText("INVALID_ARGS", e.message ?: "参数不对")
        } catch (t: Exception) {
            AppLogger.warning(TAG, "$sub failed: ${t.message}")
            errText("OPERATION_FAILED", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * An option this command does not have is refused (with the nearest right one), a switch given a value and an
     * option missing its value are refused: nothing runs, so a typo is never mistaken for "it worked".
     */
    internal fun checkOptions(sub: String, rest: List<String>) {
        val allowed = optionsOf(sub) ?: emptySet()
        val given = rest.filter { it.startsWith("--") && it.length > 2 }.map { it.substring(2).substringBefore('=') }
        val foreign = given.filter { it !in allowed && it !in GLOBAL_OPTIONS }.distinct()
        if (foreign.isNotEmpty()) throw ActionError("UNKNOWN_OPTION", "$sub 没有参数 ${foreign.joinToString(" ") { "--$it" }}" +
            suggestFor(sub, foreign.first()) + "。命令没有执行。$sub 的参数：" + allowed.sorted().joinToString(" ") { "--$it" }.ifEmpty { "无" })
        for ((i, t) in rest.withIndex()) {
            if (!t.startsWith("--") || t.length <= 2) continue
            val key = t.substring(2).substringBefore('=')
            val kind = VScreenOptions.kindOf(sub, key)
            if ('=' in t && kind == VScreenOptions.Kind.SWITCH) throw ActionError("INVALID_ARGS", "--$key 是开关，不带值（收到 $t）。命令没有执行")
            val next = rest.getOrNull(i + 1)
            if ('=' !in t && kind == VScreenOptions.Kind.VALUE && (next == null || next.startsWith("--")))
                throw ActionError("INVALID_ARGS", "--$key 后面要跟一个值（收到的是 ${next ?: "结尾"}）。命令没有执行")
        }
    }

    /** Options of [sub], from its help lines (null: no such command). */
    internal fun optionsOf(sub: String): Set<String>? = synchronized(optionCache) {
        optionCache.getOrPut(sub) {
            val h = helpFor(sub)
            if (h.startsWith("没有叫")) emptySet() else Regex("--([a-z][a-z0-9-]*)").findAll(h).map { it.groupValues[1] }.toSet()
        }.takeIf { it.isNotEmpty() }
    }

    private fun suggestFor(sub: String, bad: String): String {
        val pool = (optionsOf(sub) ?: emptySet())
        val best = pool.minByOrNull { editDistance(it, bad) } ?: return ""
        return if (editDistance(best, bad) <= maxOf(2, bad.length / 3)) "；是不是 --$best？" else ""
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * The user's per-app rules for the helpers that read the screen (act and launch check them per step / per app).
     * block: nothing is read. readonly: reading works; collect (it scrolls) works too.
     */
    internal fun enforceRules(sub: String) {
        if (VScreenStore.rules.value.isEmpty() || sub in RULE_FREE) return
        val id = BackgroundOperation.state.value.displayId ?: return
        val pkg = com.kilon.agentvscreen.core.VScreenShell.topPackage(id) ?: return
        if (VScreenStore.ruleFor(pkg) == VScreenStore.Rule.BLOCK)
            throw ActionError("APP_BLOCKED", "副屏上现在是 $pkg，用户禁止后台操作它（不能读屏也不能操作）：act \"key back\" 离开或 stop。" +
                "这是用户在 ATVSX › 应用限制 里定的，不要绕过")
    }

    internal fun dispatch(sub: String, args: OffloadArgs, sid: String?): NativeOffloadResult = when (sub) {
        "launch" -> launchCmd(sid, args)
        "act" -> act(sid, args)
        "dump" -> dumpCmd(sid, args)
        "note" -> noteCmd(sid, args)
        "keep" -> keepCmd(sid, args)
        "stop" -> stopCmd(sid, args)
        "release" -> releaseCmd(sid)
        "handoff" -> handoffCmd(sid, args)
        "status" -> status(sid, args)
        "apps" -> apps(args)
        "events" -> eventsCmd(sid, args)
        "done" -> doneCmd(sid)
        "title" -> titleCmd(sid, args)
        "spots" -> spotsCmd(sid, args)
        "zoom" -> zoomCmd(sid, args)
        "collect" -> collectCmd(sid, args)
        "fix" -> fix(sid, args)
        "resume", "continue", "look" -> NativeOffloadResult(2, "vscreen: 没有 $sub 命令，也不需要：要看现在的画面就 act（不带步骤）；" +
            "launch 和 act 返回的【现在】就算重新看过画面，之后的动作照常执行。用户在悬浮窗上点了“停止”或接管时，只有用户能交还，这时告诉用户、等用户交还\n")
        else -> NativeOffloadResult(2, "vscreen: 没有命令 '$sub'（v49 只有一个操作动词 act，点击/输入/滑动/等待都写成 act 的步骤）\n\n$QUICK_HELP\n")
    }

    /** Facts about the display, for status. */
    internal fun base(d: VScreenDisplay) = JSONObject()
        .put("display_id", d.displayId).put("generation", d.generation)
        .put("width", d.width).put("height", d.height).put("display_rotation", d.rotation * 90)

    internal fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    internal fun dumpCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
        val maxNodes = (args.getInt("max-nodes") ?: ScopedDump.DEFAULT_MAX_NODES).also {
            if (it !in 1..ScopedDump.DEFAULT_MAX_NODES) throw ActionError("INVALID_ARGS", "--max-nodes 必须是 1..200")
        }
        val maxDepth = (args.getInt("max-depth") ?: ScopedDump.DEFAULT_MAX_DEPTH).also {
            if (it !in 0..ScopedDump.DEFAULT_MAX_DEPTH) throw ActionError("INVALID_ARGS", "--max-depth 必须是 0..20")
        }
        val s = sid ?: throw ActionError("NO_SESSION", "没有会话（请求头 X-VScreen-Session）")
        return ok(args, BackgroundOperation.agentAction(s, null, isRead = true, describe = "读取副屏原生控件树", strictReadOnly = true) { d ->
            val displayMeta = JSONObject().put("display_id", d.displayId).put("generation", d.generation)
                .put("source", "native_accessibility")
                .put("non_atomic_tree_warning", "目标包、display 与无障碍树分别读取，返回不是原子快照；目标包可能在读取期间变化")
            val target = com.kilon.agentvscreen.core.VScreenShell.topPackage(d.displayId)
            if (target == null) return@agentAction displayMeta.put("available", false).put("reason", "target_package_unavailable")
            val roots = BackgroundOperation.nodeRoots(d.displayId)
            if (roots.isEmpty()) return@agentAction displayMeta.put("available", false).put("reason", "accessibility_unavailable").put("targetPackage", target)
            val scoped = roots.map(ScopedDump::fromAndroid).filter { it.packageName == target }
            if (scoped.isEmpty()) return@agentAction displayMeta.put("available", false).put("reason", "target_tree_unavailable").put("targetPackage", target)
            ScopedDump.dump(scoped, target, maxNodes, maxDepth).also { out ->
                displayMeta.keys().forEach { key -> out.put(key, displayMeta.get(key)) }
            }
        })
    }

    private fun errText(code: String, message: String): NativeOffloadResult =
        NativeOffloadResult(1, "【错误】$code：$message\n")
}
