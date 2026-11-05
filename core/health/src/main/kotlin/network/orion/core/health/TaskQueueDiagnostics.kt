package network.orion.core.health

/** Technical counts only; no content, IDs, hashes or confirmation receipts. */
data class TaskQueueDiagnostics(val queuedIngress: Int, val pendingConfirmation: Int,
    val runningActions: Int, val unknownActions: Int, val manualReviewActions: Int, val pendingEvents: Int)
