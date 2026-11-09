package com.kilon.agentvscreen.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.1.6: does the agent still know about us in its next conversation?
 *
 * 1.1.4/1.1.5 printed a skill in the install output and asked the agent to save it. Most agents did not: a request
 * inside a tool's output is data to them (often treated as a prompt injection), not something their user asked. So the
 * request now comes from the user (the app copies a message the user sends to the agent, see [AgentGuide.onboard]), and
 * this ledger shows the user whether it worked, from facts only:
 *  - installed: the install script fetched the client;
 *  - remembered: the agent ran `vscreen remembered "<where>"` after saving the note (its own claim, shown as such);
 *  - came back: a call after ≥ [GAP_MS] of silence since install — a later conversation found vscreen on its own.
 * Names and times only; no command contents.
 */
object AgentLedger {
    const val GAP_MS = 30 * 60_000L
    private lateinit var prefs: SharedPreferences

    data class Snapshot(
        val installAt: Long = 0L,
        val rememberedAt: Long = 0L,
        val rememberedWhere: String? = null,
        val lastCallAt: Long = 0L,
        val comebacks: Int = 0,
        val lastComebackAt: Long = 0L,
    ) {
        val installed get() = installAt > 0
        val remembered get() = rememberedAt >= installAt && rememberedAt > 0
        val cameBack get() = comebacks > 0 && lastComebackAt > installAt
    }

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("agent_vscreen_ledger", Context.MODE_PRIVATE)
        _snap.value = Snapshot(
            prefs.getLong("install_at", 0L), prefs.getLong("remembered_at", 0L), prefs.getString("remembered_where", null),
            prefs.getLong("last_call_at", 0L), prefs.getInt("comebacks", 0), prefs.getLong("last_comeback_at", 0L),
        )
    }

    private fun save(s: Snapshot) {
        _snap.value = s
        if (!::prefs.isInitialized) return
        prefs.edit().putLong("install_at", s.installAt).putLong("remembered_at", s.rememberedAt)
            .putString("remembered_where", s.rememberedWhere).putLong("last_call_at", s.lastCallAt)
            .putInt("comebacks", s.comebacks).putLong("last_comeback_at", s.lastComebackAt).apply()
    }

    fun installed() {
        // A fresh install starts a fresh record: the old "remembered" belonged to an earlier agent / token.
        save(Snapshot(installAt = System.currentTimeMillis(), lastCallAt = System.currentTimeMillis()))
    }

    fun remembered(where: String) {
        save(_snap.value.copy(rememberedAt = System.currentTimeMillis(), rememberedWhere = where.trim().take(80).ifEmpty { "（没说）" }))
    }

    /**
     * Every command. Returns true when this call opens a new stretch of use after a long silence (likely a new
     * conversation): the reply then carries a one-line pointer to `vscreen guide`.
     */
    fun call(): Boolean {
        val now = System.currentTimeMillis()
        val s = _snap.value
        val fresh = s.lastCallAt == 0L || now - s.lastCallAt >= GAP_MS
        val comeback = fresh && s.installed && s.lastCallAt > 0
        save(s.copy(lastCallAt = now, comebacks = s.comebacks + if (comeback) 1 else 0, lastComebackAt = if (comeback) now else s.lastComebackAt))
        return fresh
    }
}
