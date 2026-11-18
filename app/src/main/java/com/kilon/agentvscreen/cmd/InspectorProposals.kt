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
    private const val TTL = 120_000L
    private data class Entry(val proposal: InspectorProposal, val owner: String, val created: Long, val tree: String)
    private val lock = Any()
    private val mutable = MutableStateFlow<InspectorProposal?>(null)
    val state = mutable.asStateFlow()
    private var current: Entry? = null

    private fun now() = SystemClock.elapsedRealtime()
    private fun publish(entry: Entry) { current = entry; mutable.value = entry.proposal }
    private fun expire(e: Entry): Entry = if (e.proposal.status == "PENDING" && !ReviewGate.within(e.created, now(), TTL)) {
        e.copy(proposal = e.proposal.copy(status = "EXPIRED")).also(::publish)
    } else e

    fun submit(session: String, reference: String, number: Int, description: String): JSONObject {
        if (!ReviewGate.validDescription(description) || number !in 1..200)
            throw ActionError("INVALID_ARGS", "请选择 1..200 号框，并填写 1..240 字的单行修改建议，不要输入凭据")
        val snapshot = LayoutInspector.referenced(session, reference)
        val data = snapshot.data
        val tree = LayoutInspector.reviewFingerprint(data, number) ?: throw ActionError("INVALID_TARGET", "该快照没有这个可选控件，或页面含敏感节点、布局截断")
        return synchronized(lock) {
            current?.let { expire(it) }?.takeIf { it.proposal.status == "PENDING" }?.let {
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
        json(expire(e).proposal)
    }

    fun withdraw(session: String, id: String): JSONObject = synchronized(lock) {
        val e = current?.takeIf { it.owner == session && it.proposal.id == id }?.let(::expire)
            ?: throw ActionError("PROPOSAL_NOT_FOUND", "对本会话没有这条建议")
        if (e.proposal.status != "PENDING") return@synchronized json(e.proposal)
        val cancelled = e.proposal.copy(status = "CANCELLED")
        publish(e.copy(proposal = cancelled)); json(cancelled)
    }

    fun decideLocal(id: String, ticket: InspectorViewTicket, approve: Boolean): Boolean {
        val entry = synchronized(lock) { current?.let(::expire)?.takeIf { it.proposal.id == id && it.proposal.status == "PENDING" } } ?: return false
        val p = entry.proposal
        if (ticket.owner != entry.owner || ticket.displayId != p.displayId || ticket.generation != p.generation ||
            !BackgroundOperation.isInspectorViewValid(ticket)) return false
        if (approve && BackgroundOperation.state.value.let { it.userStopped || it.takeoverSessionId != null }) return false
        // Fresh tree + exact content/geometry identity; a still frame is not evidence of a matching page.
        val fresh = if (approve) runCatching { LayoutInspector.captureLocal(ticket).data }.getOrNull() else null
        val ticketStillValid = BackgroundOperation.isInspectorViewValid(ticket) &&
            (!approve || BackgroundOperation.state.value.let { !it.userStopped && it.takeoverSessionId == null })
        return synchronized(lock) {
            val live = current?.let(::expire)
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
    fun validDescription(value: String): Boolean = value.trim().length in 1..240 && value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}
