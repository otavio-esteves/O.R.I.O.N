package network.orion.core.model

/**
 * Ephemeral, derived operational state (Architecture V3.2 §97).
 *
 * Never use this as persistent business truth. After process death, reconstruct it
 * from durable state, subsystem health and active sessions.
 */
enum class AgentState {
    OFFLINE,
    IDLE,
    LISTENING,
    PROCESSING_SPEECH,
    THINKING,
    ACTING,
    SPEAKING,
    LOW_POWER,
    THERMAL_LIMIT,
    RECOVERY,
    DEGRADED,
    ERROR,
}
