package com.kilon.agentvscreen.ui

/** Pure guard for the one-shot review attention affordance. */
internal data class ReviewAttentionSnapshot(
    val proposalId: String?,
    val pending: Boolean,
    val matchesCurrentDisplay: Boolean,
    val previewVisible: Boolean,
    val fullscreenVisible: Boolean,
    val docked: Boolean,
    val gestureActive: Boolean,
    val alreadyReminded: Boolean,
)

internal object ReviewAttentionPolicy {
    fun shouldAutoExpand(snapshot: ReviewAttentionSnapshot): Boolean =
        snapshot.proposalId != null &&
            snapshot.pending &&
            snapshot.matchesCurrentDisplay &&
            snapshot.previewVisible &&
            !snapshot.fullscreenVisible &&
            snapshot.docked &&
            !snapshot.gestureActive &&
            !snapshot.alreadyReminded
}
