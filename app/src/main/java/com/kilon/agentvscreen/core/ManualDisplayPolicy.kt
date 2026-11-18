package com.kilon.agentvscreen.core

/** Local human creation never needs an AI task or changes its stopped flag. */
internal object ManualDisplayPolicy {
    enum class Decision { REUSE, CREATE, SERVICE_OFF, NOT_CONNECTED }
    fun open(enabled: Boolean, ready: Boolean, liveDisplay: Boolean): Decision = when {
        !enabled -> Decision.SERVICE_OFF
        !ready -> Decision.NOT_CONNECTED
        liveDisplay -> Decision.REUSE
        else -> Decision.CREATE
    }
    fun mayManage(manual: Boolean, currentGeneration: Boolean, activeAi: Boolean, takeover: Boolean): Boolean =
        manual && currentGeneration && !activeAi && !takeover
    fun showExisting(liveDisplay: Boolean, ownerChanged: Boolean): Boolean = liveDisplay && !ownerChanged
}
