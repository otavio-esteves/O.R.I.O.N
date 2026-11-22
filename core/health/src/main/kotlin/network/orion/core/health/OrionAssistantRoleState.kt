package network.orion.core.health

/** Observation of Android's assistant selection, independent of Core readiness and push-to-talk. */
enum class OrionAssistantRoleState {
    UNAVAILABLE,
    NOT_SELECTED,
    SELECTED_INACTIVE,
    ACTIVE,
}
