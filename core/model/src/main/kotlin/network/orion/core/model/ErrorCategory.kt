package network.orion.core.model

/**
 * Error taxonomy (Architecture V3.2 §98).
 *
 * A category alone never grants retry: the operation and its recovery contract
 * must also permit it. In particular, timeout does not prove an effect failed.
 */
enum class ErrorCategory {
    TRANSIENT,
    PERMANENT,
    USER_ACTION_REQUIRED,
    PERMISSION_DENIED,
    POLICY_DENIED,
    VALIDATION_FAILED,
    RESOURCE_DENIED,
    CANCELLED,
    TIMEOUT,
    /** Whether the external effect occurred is uncertain; generic automatic retry is forbidden. */
    EXTERNAL_STATE_UNKNOWN,
    SECURITY_FAILURE,
    NATIVE_FAILURE,
}
