package network.orion.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract fixtures transcribed from Architecture V3.2, not inferred from enum ordinals. */
class ModelVocabularyTest {
    @Test
    fun `agent states match architecture section 97`() {
        assertEquals(
            setOf(
                "OFFLINE", "IDLE", "LISTENING", "PROCESSING_SPEECH", "THINKING",
                "ACTING", "SPEAKING", "LOW_POWER", "THERMAL_LIMIT", "RECOVERY", "DEGRADED", "ERROR",
            ),
            AgentState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `error taxonomy preserves ambiguous external outcomes from section 98`() {
        assertEquals(
            setOf(
                "TRANSIENT", "PERMANENT", "USER_ACTION_REQUIRED", "PERMISSION_DENIED",
                "POLICY_DENIED", "VALIDATION_FAILED", "RESOURCE_DENIED", "CANCELLED",
                "TIMEOUT", "EXTERNAL_STATE_UNKNOWN", "SECURITY_FAILURE", "NATIVE_FAILURE",
            ),
            ErrorCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `authorization categories match architecture section 59`() {
        assertEquals(
            setOf("SAFE_READ", "SAFE_WRITE", "SENSITIVE_READ", "EXTERNAL_ACTION", "DESTRUCTIVE"),
            AuthorizationLevel.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `recovery strategies preserve no retry and manual review from section 53`() {
        assertEquals(
            setOf("RETRY_SAFE", "VERIFY_THEN_RETRY", "NO_RETRY", "MANUAL_REVIEW"),
            RecoveryStrategy.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `side effect classes distinguish irreversible and destructive effects from section 102`() {
        assertEquals(
            setOf("READ_ONLY", "LOCAL_MUTATION", "EXTERNAL_REVERSIBLE", "EXTERNAL_IRREVERSIBLE", "DESTRUCTIVE"),
            SideEffectClass.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `idempotency modes include verification and absence of guarantee from section 102`() {
        assertEquals(
            setOf("NATURAL", "KEYED", "VERIFY_THEN_RETRY", "NONE"),
            IdempotencyMode.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `sensitivity classifications preserve sensitive and secret from section 69`() {
        assertEquals(
            setOf("PUBLIC", "PERSONAL", "SENSITIVE", "SECRET"),
            Sensitivity.entries.map { it.name }.toSet(),
        )
    }
}
