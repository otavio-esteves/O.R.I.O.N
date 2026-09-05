package network.orion.core.model

/**
 * Declarative authorization categories (Architecture V3.2 §59).
 *
 * These are categories, not an ordinal permission hierarchy. Do not use enum
 * ordering to grant authority. Policy, capability, permission and confirmation
 * checks remain required; model output cannot raise or lower authorization.
 */
enum class AuthorizationLevel {
    SAFE_READ,
    SAFE_WRITE,
    SENSITIVE_READ,
    EXTERNAL_ACTION,
    DESTRUCTIVE,
}
