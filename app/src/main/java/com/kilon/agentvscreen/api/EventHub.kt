package com.kilon.agentvscreen.api

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Events for callers that are not running when something happens (a keep saw its condition). Kept per session, at most
 * [MAX] each, until taken with `events` (CLI / HTTP / MCP). Each event is also sent as the broadcast [ACTION] so an
 * Android agent app can register a receiver instead of polling.
 */
object EventHub {
    const val ACTION = "com.kilon.agentvscreen.action.EVENT"
    private const val MAX = 50
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val queues = HashMap<String, ArrayDeque<JSONObject>>()

    /** Returns a short note of where it went. */
    fun post(ctx: Context, session: String, kind: String, name: String, text: String): String {
        val ev = JSONObject().put("session", session).put("kind", kind).put("name", name).put("text", text)
            .put("at", System.currentTimeMillis())
        lock.withLock {
            val q = queues.getOrPut(session) { ArrayDeque() }
            q.addLast(ev); while (q.size > MAX) q.removeFirst()
            arrived.signalAll()
        }
        runCatching {
            ctx.sendBroadcast(Intent(ACTION).putExtra("session", session).putExtra("kind", kind).putExtra("name", name)
                .putExtra("text", text).putExtra("json", ev.toString()))
        }
        return "已放进会话 $session 的事件队列（vscreen events 取），并发了广播 $ACTION"
    }

    /** Take every event of [session]; when there is none, wait up to [waitMs] for one. */
    fun take(session: String, waitMs: Long): List<JSONObject> = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceIn(0, 120_000))
        while ((queues[session]?.isEmpty() != false) && left > 0) left = arrived.awaitNanos(left)
        val q = queues[session] ?: return@withLock emptyList()
        val out = q.toList(); q.clear(); out
    }

    fun pending(session: String): Int = lock.withLock { queues[session]?.size ?: 0 }
}
