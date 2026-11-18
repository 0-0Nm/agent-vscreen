# ATVSX 每次迭代交付检查

用户要求：以后新版迭代同步更新软件内置说明，不仅交付外部 Markdown。

## 必查说明入口
- api/AgentGuide.kt：guide、prompt、skill、memory/memoryHttp、onboard/onboardHttp。
- cmd/VScreenOffloadHandler.kt：QUICK_HELP、HELP、单命令 help 的语法与实现保持一致。
- MCP 使用的工具说明是否来自上述帮助；已连接客户端可能需要重新连接才能刷新描述。
- 用户手册与应用内行为有变化时同步更新；不得把仅确认回执描述为自动操作或修改授权。
- 版本身份与云工作流验包断言一起更新。

## 本轮 code 21 待验
- 带待确认请求进布局，只读取一次历史建议，不与普通采集竞争。
- 正常、异常、取消、刷新、退出重入后不会永久忙碌；旧结果不覆盖新结果。
- 读取失败能重试建议；页面指纹变化、接管、停止 AI、过期仍不能误确认。
- 贴边时每条新待确认展开一次，用户再收起不反复展开；多任务/窗口状态变化不提醒陈旧建议。
- 用户主动隐藏/关闭预览、全屏在前、手势期间不强行弹窗，不建屏或抢会话。
- 用户真机确认后，同一会话 layout-wait 收到 APPROVED；超时不是同意。
- 内置 guide/skill 与复制给 AI 的话均说明 layout-propose/wait/review/withdraw。

本地 JVM 策略测试不等于 Android 编译或真机通过；推送、云构建、安装须按本轮授权执行。
