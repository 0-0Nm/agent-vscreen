package com.kilon.agentvscreen.core

/** LOCAL HUMAN UI capability, never accepted by MCP. All registry access is under BackgroundOperation.lock. */
internal data class InspectorViewTicket internal constructor(
    val token: String, val displayId: Int, val generation: Long, val owner: String?,
)
internal class InspectorViewRegistry {
    private val tickets = LinkedHashMap<String, InspectorViewTicket>()
    fun begin(token: String, displayId: Int, generation: Long, owner: String?): InspectorViewTicket {
        require(displayId > 0 && generation > 0 && token !in tickets)
        return InspectorViewTicket(token, displayId, generation, owner).also { tickets[token] = it }
    }
    fun valid(ticket: InspectorViewTicket, displayId: Int?, generation: Long?, owner: String?): Boolean =
        tickets[ticket.token] === ticket && ticket.displayId == displayId && ticket.generation == generation && ticket.owner == owner
    fun active(displayId: Int?, generation: Long?, owner: String?): Boolean {
        // Old tickets must neither protect nor release a replacement screen.
        tickets.entries.removeAll { (_, t) -> t.displayId != displayId || t.generation != generation || t.owner != owner }
        return tickets.isNotEmpty()
    }
    fun end(ticket: InspectorViewTicket): Boolean = tickets.remove(ticket.token, ticket)
    fun clear() { tickets.clear() }
}
