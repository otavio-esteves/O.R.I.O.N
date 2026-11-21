package network.orion.core.health

/** Technical counts only; no content, IDs, hashes or confirmation receipts. */
data class TaskQueueDiagnostics(val queuedIngress: Int, val pendingConfirmation: Int,
    val runningActions: Int, val unknownActions: Int, val manualReviewActions: Int, val pendingEvents: Int,
    val ingressCapacity: OrionIngressCapacity? = null)

/** Includes retained receipts as well as queued work. */
data class OrionIngressCapacity(val stored: Int, val limit: Int) {
    val saturated: Boolean get() = stored >= limit
}
