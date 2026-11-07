package com.kilon.agentvscreen.api

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kilon.agentvscreen.core.BackgroundOperation

/**
 * A caller's "turn" without a chat to tell us: a session is busy from its first command until it has been quiet for
 * [quietMs] (default 3 minutes, set in the app), or until it says `vscreen done`. Inside that window the screen stays
 * awake, the recording track runs (so the next 【这段时间】 covers the caller's thinking time) and a user takeover is
 * kept. After it the screen idles exactly as it did between Minis chat turns: paused to save power, released after the
 * idle TTL, and another session may take it over (page kept).
 */
object Turns {
    @Volatile var quietMs: Long = 180_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val inFlight = HashMap<String, Int>()
    private val active = HashSet<String>()
    private val enders = HashMap<String, Runnable>()
    private val quietSince = HashMap<String, Long>()
    private val doneAsked = HashSet<String>()

    init {
        BackgroundOperation.describeOwner = { sid -> describe(sid) }
    }

    fun begin(sid: String) {
        val start = synchronized(this) {
            enders.remove(sid)?.let { main.removeCallbacks(it) }
            inFlight[sid] = (inFlight[sid] ?: 0) + 1
            quietSince.remove(sid)
            active.add(sid)
        }
        if (start || !BackgroundOperation.isTaskActive(sid)) BackgroundOperation.onTaskStarted(sid)
    }

    fun end(sid: String) {
        val now = synchronized(this) {
            val n = ((inFlight[sid] ?: 1) - 1).coerceAtLeast(0)
            inFlight[sid] = n
            if (n > 0) return
            quietSince[sid] = SystemClock.elapsedRealtime()
            if (doneAsked.remove(sid)) true else {
                val r = Runnable { finish(sid) }
                enders[sid] = r
                main.postDelayed(r, quietMs)
                false
            }
        }
        if (now) finish(sid)
    }

    /** `vscreen done`: end this session's turn as soon as the command that asked returns. */
    fun doneAfterThis(sid: String) { synchronized(this) { doneAsked.add(sid) } }

    /** End the turn now (the caller said it is done, or the app was told to stop). */
    fun finish(sid: String) {
        synchronized(this) {
            enders.remove(sid)?.let { main.removeCallbacks(it) }
            if ((inFlight[sid] ?: 0) > 0) return
            active.remove(sid)
            quietSince.remove(sid)
        }
        BackgroundOperation.onTaskFinished(sid)
    }

    /** For BUSY: is the owner running a command, or how long until its quiet window ends. */
    fun describe(sid: String): String = synchronized(this) {
        val running = inFlight[sid] ?: 0
        val since = quietSince[sid]
        when {
            running > 0 -> "（它有命令正在执行，执行完后再安静 ${fmt(quietMs)} 才自动让出）"
            since != null && sid in active -> {
                val quiet = SystemClock.elapsedRealtime() - since
                "（它的上一条命令 ${fmt(quiet)}前结束，再安静 ${fmt((quietMs - quiet).coerceAtLeast(0))} 自动让出）"
            }
            else -> ""
        }
    }

    /** Milliseconds left before [sid]'s turn ends by itself (null: no turn, or a command is running). */
    fun leftMs(sid: String): Long? = synchronized(this) {
        if ((inFlight[sid] ?: 0) > 0 || sid !in active) return null
        val since = quietSince[sid] ?: return null
        (quietMs - (SystemClock.elapsedRealtime() - since)).coerceAtLeast(0)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
    }

    fun sessions(): Set<String> = synchronized(this) { active.toSet() }
}
