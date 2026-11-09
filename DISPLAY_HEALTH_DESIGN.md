# ATVSX display-health 修复设计

- 任务：`a6fd7d4a-bcf4-45d9-8867-fb3f803720f4`
- 目标分支：`fix/display-health`（本地工作副本；远程分支创建/推送受 GitHub API 网络阻塞，见结果）
- 已知云端基线：成功提交 `8220279df3e73d5e75989496b9489ebe3d109d66`；本轮未把不完整旧源码快照冒充远程最新。

## 事实根因范围

1. `VScreenAct.runSegment` 在动作段开始时调用 `VScreenAwake.ensure`，但该函数只返回提示字符串；唤醒失败后流程仍可进入 `BackgroundOperation.agentAction`，存在继续触摸、输入或按键的路径。
2. `BackgroundOperation.agentAction` 在原有实现中会在动作前触发 remote-display `touch`，但没有统一的“目标 display 已确认 ON”动作门禁。
3. `VScreenDisplay.awaitFrameAfter` 超时仍返回最后缓存 Bitmap，调用者无法区分“收到新帧”和“旧缓存”，因此诊断图片可能被误读为刚刚响应。
4. 现有屏幕状态诊断已经能够返回 display state / own power group；本轮未改主屏电源路径，也未加入未经证实的 per-display `userActivity`。

## 本轮改法

- 新增 `VScreenAwake.ensureForAction`：动作专用门禁。已知 OFF/DOZE 等状态先尝试安全唤醒；唤醒后仍非 ON、display 已释放、处于 park、省电暂停或无法读取 display state 时，抛出阻止错误，动作块不会执行。
- 在 `BackgroundOperation.agentAction` 中、任何 remote touch 和实际输入之前调用该门禁；用户停止、接管、只读/禁止应用规则仍在原有路径生效，不提供绕过入口。
- 新增 `VScreenDisplay.awaitFrameAfterInfo`，保留 `fresh`、缓存年龄和采样时间元数据；旧的 Bitmap-only API 仅作为兼容包装。
- `VScreenReport.snapshot` 使用元数据，四段报告在超时返回旧图时明确写出“缓存画面/不能证明刚刚响应”。
- 新增纯策略测试 `DisplayHealthGuardTest`，覆盖 OFF、未知状态、ON 与 fresh/stale 帧提示。
- workflow 在 assemble 前执行该针对性单测；未改变既有包名、label、namespace、protocol、vscreen 工具名或 AB。

## 未证实假设 / 未做决定

- 未在本轮搬入 Mika 或 AOSP 的 per-display `userActivity`。当前远程服务是否拥有对应 PowerManager 权限、目标 display group 是否真的独立、ROM 是否会把调用作用到主屏，尚未取得本地 AOSP/设备运行证据；盲加会扩大风险。
- OFF 恢复、旧截图后的真实触摸/文本/按键阻止未在手机上验证；本轮明确不安装、不操作手机、不改 AB。
- 远程当前 HEAD、源码完整树、分支创建、Actions 构建尚因 GitHub API 出口 TLS/超时阻塞，不能声称已提交到远程或已云端构建。
