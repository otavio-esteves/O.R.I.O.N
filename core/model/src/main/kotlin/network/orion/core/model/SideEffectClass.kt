package network.orion.core.model

/**
 * Declarative effect classification for Skill metadata (Architecture V3.2 §102).
 *
 * Classification does not authorize execution. Destructive effects retain
 * mandatory confirmation regardless of ordinary user preferences.
 */
enum class SideEffectClass {
    READ_ONLY,
    LOCAL_MUTATION,
    EXTERNAL_REVERSIBLE,
    EXTERNAL_IRREVERSIBLE,
    DESTRUCTIVE,
}
