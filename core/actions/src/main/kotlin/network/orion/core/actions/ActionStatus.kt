package network.orion.core.actions

/** ActionRequest lifecycle vocabulary (Architecture V3.2 §§50–55). */
enum class ActionStatus {
    PENDING,
    NEEDS_CONFIRMATION,
    CONFIRMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
    MANUAL_REVIEW,
}
