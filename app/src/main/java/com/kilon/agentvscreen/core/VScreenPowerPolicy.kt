package com.kilon.agentvscreen.core

/** Pure safety gate for display-scoped power activity. It never authorizes a global wake fallback. */
internal object VScreenPowerPolicy {
    private const val OWN_DISPLAY_GROUP = 1 shl 11

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(api: Int, displayId: Int, displayFlags: Int, ownedDisplayId: Int?): Decision {
        if (api < 33) return Decision(false, "Android API 不支持已验证的副屏定向策略")
        if (displayId <= 0) return Decision(false, "目标 displayId 不是副屏")
        if (ownedDisplayId != displayId) return Decision(false, "目标 display 不是本服务当前持有的 display")
        if ((displayFlags and OWN_DISPLAY_GROUP) == 0) return Decision(false, "目标 display 不具备 OWN_DISPLAY_GROUP，无法证明不会唤醒主屏")
        return Decision(true, "允许仅对目标 display 尝试 userActivity")
    }
}
