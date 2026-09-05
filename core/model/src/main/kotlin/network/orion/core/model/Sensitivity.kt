package network.orion.core.model

/**
 * Shared data sensitivity classification (Architecture V3.2 §69).
 *
 * The classification alone does not protect data. Payload policy must enforce
 * minimization and encryption for SENSITIVE/SECRET content. SECRET must never
 * enter logs or raw FTS; SENSITIVE is excluded from raw FTS by default.
 * Do not use ordinals as a persisted or IPC representation.
 */
enum class Sensitivity {
    PUBLIC,
    PERSONAL,
    SENSITIVE,
    SECRET,
}
