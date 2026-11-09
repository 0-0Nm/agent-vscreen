package com.kilon.agentvscreen.api

/** The short text a user pastes into an agent's instructions (system prompt / memory / skill). Kept under ~1200 chars. */
object AgentGuide {
    /**
     * 1.1.6: the note an agent keeps in its long-term memory. Short on purpose (memories get truncated) and free of the
     * token: it only has to make a future conversation think of `vscreen`; the details come from `vscreen guide`.
     */
    fun memory(): String = "手机上装了 Agent-VScreen（后台副屏）：要打开、查看或操作这台手机上的任何应用时，用终端命令 `vscreen`，" +
        "它在看不见的副屏上操作，不打扰用户正在用的主屏，也不需要 adb、无线调试或 root。先 `vscreen guide` 看简明用法（`vscreen help all` 是完整说明）。" +
        "常用：`vscreen launch <应用名>`、`vscreen act \"tap 搜索; type 耳机; key enter\"`、`vscreen act`（看一眼）、做完 `vscreen done`。" +
        "如果提示没有 vscreen 命令或令牌不对，请用户在 Agent-VScreen App 的“接入 agent”里重新复制接入的话发给你。"

    /** The same note for agents that call HTTP / MCP instead of a shell. */
    fun memoryHttp(): String = "手机上装了 Agent-VScreen（后台副屏）：要打开、查看或操作这台手机上的应用时，调用它的 HTTP 接口 " +
        "POST ${ApiConfig.baseUrl}/v1/run（Authorization: Bearer <令牌>，JSON {\"cmd\": \"launch 设置\"}），或它的 MCP 工具 vscreen。" +
        "它在看不见的副屏上操作，不打扰用户主屏，不需要 adb。先发 {\"cmd\": \"guide\"} 看简明用法。令牌在 App 的“接入 agent”里。"

    /**
     * 1.1.6: what the user sends to the agent (copied by the app). The user asks, so the agent does it: install, keep
     * the note in its own memory, then report where with `vscreen remembered` (the app shows it).
     */
    fun onboard(): String = """
请帮我接入手机上的 Agent-VScreen（后台副屏：在不打扰我用手机的情况下，帮我打开和操作手机里的应用）。按顺序做这三步：

1. 在你的终端里执行这一行（安装 vscreen 命令，令牌会存好）：
${ApiConfig.installLine()}

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则，用你自己保存长期信息的方式）。这是我的要求：以后的新对话里你也要知道手机上有这个工具。
「${memory()}」

3. 存好以后执行：vscreen remembered "<你存在了哪里>"
然后告诉我你把它存在了哪里。
""".trim()

    /** Onboarding for an agent that only does HTTP (no shell). */
    fun onboardHttp(): String = """
请帮我接入手机上的 Agent-VScreen（后台副屏：在不打扰我用手机的情况下，帮我打开和操作手机里的应用）。

1. 它的接口：POST ${ApiConfig.baseUrl}/v1/run，请求头 Authorization: Bearer ${ApiConfig.token}，内容 {"cmd": "status"}。先发一次 status 确认能连上，再发 {"cmd": "guide"} 读简明用法。

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里你也要知道手机上有这个工具。
「${memoryHttp()}」

3. 存好以后发 {"cmd": "remembered <你存在了哪里>"}，然后告诉我你把它存在了哪里。
""".trim()

    /** `vscreen guide`: the short usage, without the install line (whoever runs it is installed). */
    fun guide(): String = prompt().lines().filterNot { it.contains("/cli | sh") }.joinToString("\n") + "\n"

    /**
     * The same guide as a generic skill file (SKILL.md with name/description frontmatter). Printed by `install` for the
     * agent to keep as its own skill; nothing is written into any agent's folders. Served at
     * /skill without a token, so it carries no token: the install line is left out (vscreen is installed by then).
     */
    fun skill(): String = """---
name: agent-vscreen
description: 在手机上后台打开、查看、操作任何应用（点、滑、输入、截图、盯守）时使用。用 vscreen 命令在 Agent-VScreen 的看不见的副屏上操作，不占用户主屏。不要自己建虚拟屏，也不要为此去连 adb、配无线调试或用 am/input/screencap。
---

""" + prompt().lines().filterNot { it.contains("/cli | sh") }.joinToString("\n").replace(
        "不要去动主屏。",
        "不要去动主屏。\n- 不要自己建虚拟屏，也不要用 adb、am start --display、input、screencap 代替 vscreen：副屏、点击、截图都由 vscreen 完成。"
    ) + "\n"

    fun prompt(): String = """
## 手机后台副屏（Agent-VScreen）
你可以用 `vscreen` 命令在看不见的副屏上操作手机里的应用，用户照常用主屏。用户要你打开/查看/操作某个应用时用它，不要去动主屏。
- 没有 vscreen 命令时先执行：${ApiConfig.installLine()}
- 不需要 adb 或无线调试（它走自己的 Shizuku 通道）：不要去连 adb、配无线调试或改 adb 密钥。status 里 display_id 为空只是还没建屏，launch 会自动建。
- `vscreen launch <应用名>` 打开；`vscreen act "<步骤>; <步骤>"` 做一段：tap x y | tap #N | tap 字 | press | swipe | drag | type 字 | key back | scroll down | wait <条件> | use 段名；每步可加 until <条件>（text:字 gone:字 change still color:x,y=#色 app:名）。
- 每次返回四块：做了什么 / 这段时间发生了什么 / 现在（编号元素 + 截图路径）/ 注意。截图要你自己看，画面什么意思由你判断，手机只报事实。
- 例：`vscreen act "tap 搜索; type 耳机; key enter until text:综合"`；`vscreen act` = 看一眼。
- 实时游戏：`vscreen note pause "<暂停步骤>"` 和 `note resume "<继续步骤>"` 记下后，每段 act 自动 继续→动作→观察→暂停。
- 之后继续盯：`vscreen act --stop "text:到货" --keep notify`（通知用户）或 `--keep wake`（之后用 `vscreen events --wait 60s` 取）。
- 一步没达到预期（until 没等到、字没输进去）这段就停在那步，后面不做，注意块写现在的画面：看图再决定。type 前先点输入框，等它标“有焦点”。
- 注意块里出现 CONFIRM_REQUIRED（付费/不可撤销：先问用户，同意后加 --confirmed）、APP_BLOCKED / APP_READ_ONLY、USER_TAKEOVER、USER_STOPPED（用户点了停止，返回里写了用户怎么解除，转告用户）时，停下告诉用户，不要重试。
- `vscreen title "在搜耳机"` 让用户在悬浮窗上看到你在做什么；做完 `vscreen done`，副屏让出、页面保留。
- 全部用法：`vscreen help all`。
""".trim()
}
