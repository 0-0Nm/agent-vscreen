package com.kilon.agentvscreen.api

/** The short text a user pastes into an agent's instructions (system prompt / memory / skill). Keep the memory note short; detailed workflow lives in guide / help all. */
object AgentGuide {
    /**
     * 1.1.6: the note an agent keeps in its long-term memory. Short on purpose (memories get truncated) and free of the
     * token: it only has to make a future conversation think of `atvsx`; the details come from `atvsx guide`.
     */
    fun memory(): String = "手机上装了 ATVSX（后台副屏）：要打开、查看或操作这台手机上的任何应用时，用终端命令 `atvsx`，" +
        "它在看不见的副屏上操作，不打扰用户正在用的主屏，也不需要 adb、无线调试或 root。先 `atvsx guide` 看简明用法（`atvsx help all` 是完整说明）。" +
        "常用：`atvsx launch <应用名>`、`atvsx act \"tap 搜索; type 耳机; key enter\"`、`atvsx act`（看一眼）、做完 `atvsx done`。" +
        "定位控件用 layout（不是旧 dump），保留 L-快照号与 number；发建议用 layout-propose，layout-wait 等回执，确认不执行点击。" +
        "如果提示没有 atvsx 命令或令牌不对，请用户在 ATVSX App 的“接入 agent”里重新复制接入的话发给你。"

    /** The same note for agents that call HTTP / MCP instead of a shell. */
    fun memoryHttp(): String = "手机上装了 ATVSX（后台副屏）：要打开、查看或操作这台手机上的应用时，调用它的 HTTP 接口 " +
        "POST ${ApiConfig.baseUrl}/v1/run（Authorization: Bearer <令牌>，JSON {\"cmd\": \"launch 设置\"}），或它的 MCP 工具 vscreen。" +
        "它在看不见的副屏上操作，不打扰用户主屏，不需要 adb。先发 {\"cmd\": \"guide\"} 看简明用法。令牌在 App 的“接入 agent”里。定位用 layout，建议用 layout-propose / layout-wait；确认只是回执，不执行修改。"

    /**
     * 1.1.6: what the user sends to the agent (copied by the app). The user asks, so the agent does it: install, keep
     * the note in its own memory, then report where with `atvsx remembered` (the app shows it).
     */
    fun onboard(): String = """
请帮我接入手机上的 ATVSX（后台副屏：在不打扰我用手机的情况下，帮我打开和操作手机里的应用）。按顺序做这三步：

1. 在你的终端里执行这一行（安装 atvsx 命令，令牌会存好）：
${ApiConfig.installLine()}

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则，用你自己保存长期信息的方式）。这是我的要求：以后的新对话里你也要知道手机上有这个工具。
「${memory()}」

3. 存好以后执行：atvsx remembered "<你存在了哪里>"
然后告诉我你把它存在了哪里。
""".trim()

    /** Onboarding for an agent that only does HTTP (no shell). */
    fun onboardHttp(): String = """
请帮我接入手机上的 ATVSX（后台副屏：在不打扰我用手机的情况下，帮我打开和操作手机里的应用）。

1. 它的接口：POST ${ApiConfig.baseUrl}/v1/run，请求头 Authorization: Bearer ${ApiConfig.token}，内容 {"cmd": "status"}。先发一次 status 确认能连上，再发 {"cmd": "guide"} 读简明用法。

2. 把下面这段话存进你的长期记忆（或建成一个 skill / 规则）。这是我的要求：以后的新对话里你也要知道手机上有这个工具。
「${memoryHttp()}」

3. 存好以后发 {"cmd": "remembered <你存在了哪里>"}，然后告诉我你把它存在了哪里。
""".trim()

    /** `atvsx guide`: the short usage, without the install line (whoever runs it is installed). */
    fun guide(): String = prompt().lines().filterNot { it.contains("/cli | sh") }.joinToString("\n") + "\n"

    /**
     * The same guide as a generic skill file (SKILL.md with name/description frontmatter). Printed by `install` for the
     * agent to keep as its own skill; nothing is written into any agent's folders. Served at
     * /skill without a token, so it carries no token: the install line is left out (atvsx is installed by then).
     */
    fun skill(): String = """---
name: atvsx
description: 在手机上后台打开、查看、操作任何应用（点、滑、输入、截图、盯守）时使用。用 atvsx 命令在 ATVSX 的看不见的副屏上操作，不占用户主屏。不要自己建虚拟屏，也不要为此去连 adb、配无线调试或用 am/input/screencap。
---

""" + prompt().lines().filterNot { it.contains("/cli | sh") }.joinToString("\n").replace(
        "不要去动主屏。",
        "不要去动主屏。\n- 不要自己建虚拟屏，也不要用 adb、am start --display、input、screencap 代替 atvsx：副屏、点击、截图都由 atvsx 完成。"
    ) + "\n"

    fun prompt(): String = """
## 手机后台副屏（ATVSX）
你可以用 `atvsx` 命令在看不见的副屏上操作手机里的应用，用户照常用主屏。用户要你打开/查看/操作某个应用时用它，不要去动主屏。
- 没有 atvsx 命令时先执行：${ApiConfig.installLine()}
- 不需要 adb 或无线调试（它走自己的 Shizuku 通道）：不要去连 adb、配无线调试或改 adb 密钥。status 里 display_id 为空只是还没建屏，launch 会自动建。
- `atvsx launch <应用名>` 打开；`atvsx act "<步骤>; <步骤>"` 做一段：tap x y | tap #N | tap 字 | press | swipe | drag | type 字 | key back | scroll down | wait <条件> | use 段名；每步可加 until <条件>（text:字 gone:字 change still color:x,y=#色 app:名）。
- 每次返回四块：做了什么 / 这段时间发生了什么 / 现在（编号元素 + 截图路径）/ 注意。截图要你自己看，画面什么意思由你判断，手机只报事实。
- 例：`atvsx act "tap 搜索; type 耳机; key enter until text:综合"`；`atvsx act` = 看一眼。
- 新版布局协作：`atvsx layout` 获取只读快照；用户发 `L-XXXXXXXX / 46号` 时，用 `atvsx layout --snapshot L-XXXXXXXX` 回读，按 nodes[].number 找框。快照号/编号成对；不是 act 的 #N，也不是节点 id；历史数据不能直接当点击坐标。不建屏、不接管、不唤醒、不读主屏。
- 请求定位确认：`atvsx layout-propose --snapshot L-XXXXXXXX --node 46 --change "请确认此框是否为目标控件"`。用实际快照号/编号替换示例；拿到 id 后，同一会话 `atvsx layout-wait --id <建议ID> --wait 30s`，或 `layout-review --id <建议ID>` 即时查，`layout-withdraw --id <建议ID>` 撤回。只允许一条待确认；约5分钟过期。
- 用户从通知/浮窗「待确认」进入布局，打开「查看 AI 建议」，核对高亮框后确认/驳回。贴边小窗对每条新建议只展开提醒一次，不强开全屏、不恢复用户主动关闭的浮窗。加载失败可重试；等待时间不控制按钮能否点击。
- PENDING 或 timed_out=true 不是同意；APPROVED / receipt_only=true 只确认控件与建议，不点击、不改文件、不授予执行权限。后续操作另按用户目标授权，先重新看画面。STALE/EXPIRED 时重新定位；禁止反复套用旧编号。layout-wait 单次最多30秒，超时告知用户，不忙循环。dump 只用于原生树排障，不替代新版编号与确认。
- 实时游戏：`atvsx note pause "<暂停步骤>"` 和 `note resume "<继续步骤>"` 记下后，每段 act 自动 继续→动作→观察→暂停。
- 之后继续盯：`atvsx act --stop "text:到货" --keep notify`（通知用户）或 `--keep wake`（之后用 `atvsx events --wait 60s` 取）。
- 一步没达到预期（until 没等到、字没输进去）这段就停在那步，后面不做，注意块写现在的画面：看图再决定。type 前先点输入框，等它标“有焦点”。
- 注意块里出现 CONFIRM_REQUIRED（付费/不可撤销：先问用户，同意后加 --confirmed）、APP_BLOCKED / APP_READ_ONLY、USER_TAKEOVER、USER_STOPPED（用户点了停止，返回里写了用户怎么解除，转告用户）时，停下告诉用户，不要重试。
- `atvsx title "在搜耳机"` 让用户在悬浮窗上看到你在做什么；做完 `atvsx done`，副屏让出、页面保留。
- 全部用法：`atvsx help all`。
""".trim()
}
