package com.kilon.agentvscreen.ui

/** Main-thread operation ownership: an old completion cannot unlock a newer load. */
internal class InspectorLoadGate {
    private var serial = 0L
    private var active: Long? = null
    fun begin(): Long = (++serial).also { active = it }
    fun isCurrent(id: Long): Boolean = active == id
    fun finish(id: Long): Boolean {
        if (!isCurrent(id)) return false
        active = null
        return true
    }
    fun invalidate() { ++serial; active = null }
}
