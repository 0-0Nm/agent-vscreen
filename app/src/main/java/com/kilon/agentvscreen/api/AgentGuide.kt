package com.kilon.agentvscreen.api

/** The short text a user pastes into an agent's instructions (system prompt / memory / skill). Kept under ~1200 chars. */
object AgentGuide {
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
- 注意块里出现 CONFIRM_REQUIRED（付费/不可撤销：先问用户，同意后加 --confirmed）、APP_BLOCKED / APP_READ_ONLY、USER_TAKEOVER、USER_STOPPED（用户点了停止）时，停下告诉用户，不要重试。
- `vscreen title "在搜耳机"` 让用户在悬浮窗上看到你在做什么；做完 `vscreen done`，副屏让出、页面保留。
- 全部用法：`vscreen help all`。
""".trim()
}
