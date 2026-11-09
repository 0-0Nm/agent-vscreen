package com.kilon.agentvscreen.core

/** Small pure policy helpers for display-health gates; kept independent of the Android display service for tests. */
internal object VScreenDisplayHealth {
    enum class PreActionBlock { NONE, USER_STOPPED, TAKEOVER, STALE_GENERATION, REREAD }

    /** Pure ordering contract for the checks that must precede the wake/action gate. */
    fun preActionBlock(userStopped: Boolean, takeover: Boolean, generationMatches: Boolean, rereadRequired: Boolean, freshPage: Boolean, isRead: Boolean): PreActionBlock = when {
        userStopped -> PreActionBlock.USER_STOPPED
        takeover -> PreActionBlock.TAKEOVER
        !generationMatches -> PreActionBlock.STALE_GENERATION
        !isRead && rereadRequired && !freshPage -> PreActionBlock.REREAD
        else -> PreActionBlock.NONE
    }

    fun actionMayInject(stateKnown: Boolean, displayOn: Boolean): Boolean = stateKnown && displayOn

    fun cachedFrameNote(hasFrame: Boolean, fresh: Boolean, ageMs: Long): String? = when {
        !hasFrame -> "没有实际画面帧：当前不是缓存画面，无法据此判断副屏场景"
        fresh -> null
        else -> "取帧未收到新帧（缓存年龄 ${if (ageMs >= 0) "${ageMs}ms" else "未知"}）：画面仅作诊断证据，不能证明刚刚响应"
    }
}
