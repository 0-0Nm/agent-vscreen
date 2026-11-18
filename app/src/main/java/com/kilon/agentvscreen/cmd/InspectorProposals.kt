package com.kilon.agentvscreen.cmd

import android.os.SystemClock
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.core.InspectorViewTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class InspectorProposal(val id: String, val reference: String, val number: Int,
    val description: String, val displayId: Int, val generation: Long, val status: String = "PENDING")

/** Review receipt only. No API here performs input, modifies a file or grants edit permissions. */
internal object InspectorProposals {
    internal const val TTL = 300_000L
    private data class Entry(val proposal: InspectorProposal, val owner: String, val created: Long, val tree: String)
    private val lock = java.lang.Object()
    private val mutable = MutableStateFlow<InspectorProposal?>(null)
    val state = mutable.asStateFlow()
    private var current: Entry? = null

    private fun now() = SystemClock.elapsedRealtime()
    private fun publish(entry: Entry) { current = entry; mutable.value = entry.proposal; lock.notifyAll() }
    private fun reconcile(e: Entry): Entry {
        val p = e.proposal
        if (p.status != "PENDING") return e
        val status = ReviewGate.pendingStatus(e.created, now(), TTL,
            BackgroundOperation.state.value.let { it.displayId == p.displayId && it.generation == p.generation && it.ownerSessionId == e.owner })
        return if (status != "PENDING") e.copy(proposal = p.copy(status = status)).also(::publish) else e
    }
    fun refreshPending() = synchronized(lock) { current?.let(::reconcile); Unit }
    fun remainingMs(id: String): Long = synchronized(lock) {
        val e = current?.takeIf { it.proposal.id == id && it.proposal.status == "PENDING" } ?: return@synchronized 0L
        (TTL - (now() - e.created)).coerceIn(0L, TTL)
    }

    /** Bounded, session-scoped wait; wait() releases the lock so local review can complete. */
    fun await(session: String, id: String, waitMs: Long): JSONObject = synchronized(lock) {
        val deadline = now() + waitMs.coerceIn(1L, 30_000L)
        while (true) {
            val e = current?.takeIf { it.owner == session && it.proposal.id == id }?.let(::reconcile)
                ?: throw ActionError("PROPOSAL_NOT_FOUND", "对本会话没有这条建议")
            if (e.proposal.status != "PENDING") return@synchronized json(e.proposal).put("timed_out", false)
            val until = minOf(deadline, e.created + TTL) - now()
            if (until <= 0L) return@synchronized json(reconcile(e).proposal).put("timed_out", now() >= deadline)
            try { lock.wait(until) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ActionError("WAIT_INTERRUPTED", "等待已中断；没有确认或执行修改")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    fun submit(session: String, reference: String, number: Int, description: String): JSONObject {
        if (!ReviewGate.validDescription(description) || number !in 1..200)
            throw ActionError("INVALID_ARGS", "请选择 1..200 号框，并填写 1..240 字的单行修改建议，不要输入凭据")
        val snapshot = LayoutInspector.referenced(session, reference)
        val data = snapshot.data
        val tree = LayoutInspector.reviewFingerprint(data, number) ?: throw ActionError("INVALID_TARGET", "该快照没有这个可选控件，或页面含敏感节点、布局截断")
        return synchronized(lock) {
            current?.let { reconcile(it) }?.takeIf { it.proposal.status == "PENDING" }?.let {
                throw ActionError("PROPOSAL_PENDING", "已有待确认的建议，请先在本机处理")
            }
            val p = InspectorProposal(UUID.randomUUID().toString(), data.getString("human_ref"), number, description.trim(),
                data.getInt("display_id"), data.getLong("generation"))
            publish(Entry(p, session, now(), tree))
            json(p)
        }
    }

    fun read(session: String, id: String): JSONObject = synchronized(lock) {
        val e = current?.takeIf { it.owner == session && it.proposal.id == id }
            ?: throw ActionError("PROPOSAL_NOT_FOUND", "对本会话没有这条建议")
        json(reconcile(e).proposal)
    }

    fun withdraw(session: String, id: String): JSONObject = synchronized(lock) {
        val e = current?.takeIf { it.owner == session && it.proposal.id == id }?.let(::reconcile)
            ?: throw ActionError("PROPOSAL_NOT_FOUND", "对本会话没有这条建议")
        if (e.proposal.status != "PENDING") return@synchronized json(e.proposal)
        val cancelled = e.proposal.copy(status = "CANCELLED")
        publish(e.copy(proposal = cancelled)); json(cancelled)
    }

    fun decideLocal(id: String, ticket: InspectorViewTicket, approve: Boolean): Boolean {
        val entry = synchronized(lock) { current?.let(::reconcile)?.takeIf { it.proposal.id == id && it.proposal.status == "PENDING" } } ?: return false
        val p = entry.proposal
        if (ticket.owner != entry.owner || ticket.displayId != p.displayId || ticket.generation != p.generation ||
            !BackgroundOperation.isInspectorViewValid(ticket)) return false
        if (approve && BackgroundOperation.state.value.let { it.userStopped || it.takeoverSessionId != null }) return false
        // Fresh tree + exact content/geometry identity; a still frame is not evidence of a matching page.
        val fresh = if (approve) runCatching { LayoutInspector.captureLocal(ticket).data }.getOrNull() else null
        val ticketStillValid = BackgroundOperation.isInspectorViewValid(ticket) &&
            (!approve || BackgroundOperation.state.value.let { !it.userStopped && it.takeoverSessionId == null })
        return synchronized(lock) {
            val live = current?.let(::reconcile)
            if (live !== entry || !ticketStillValid) return@synchronized false
            val status = if (!approve) "REJECTED" else if (fresh != null && LayoutInspector.reviewFingerprint(fresh, p.number) == entry.tree) "APPROVED" else "STALE"
            publish(entry.copy(proposal = p.copy(status = status)))
            status != "STALE"
        }
    }
    private fun json(p: InspectorProposal) = JSONObject().put("id",p.id).put("reference",p.reference).put("number",p.number)
        .put("status",p.status).put("display_id",p.displayId).put("generation",p.generation).put("receipt_only",true)
}

internal object ReviewGate {
    fun within(start: Long, now: Long, ttl: Long): Boolean = now >= start && now - start < ttl
    fun pendingStatus(start: Long, now: Long, ttl: Long, sameDisplay: Boolean): String = when {
        !within(start, now, ttl) -> "EXPIRED"
        !sameDisplay -> "STALE"
        else -> "PENDING"
    }
    fun validDescription(value: String): Boolean = value.trim().length in 1..240 && value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}
