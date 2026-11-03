package network.orion.core.actions

import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class IdempotencyKeyPolicyTest {
    private val policy = IdempotencyKeyPolicy("core", "orion_tasks", Duration.ofDays(90), 1, "orion_key_unique")
    private val original = IdempotencyBinding("orion_tasks", "private-key", "a".repeat(64), 1)
    @Test fun `identical replay deduplicates while changed payload conflicts`() {
        assertEquals(IdempotencyReplay.DEDUP, policy.compare(original, original))
        assertEquals(IdempotencyReplay.IDEMPOTENCY_CONFLICT,
            policy.compare(original, IdempotencyBinding("orion_tasks", "private-key", "b".repeat(64), 1)))
        assertEquals(IdempotencyReplay.NEW_KEY,
            policy.compare(original, IdempotencyBinding("orion_tasks", "other-key", "b".repeat(64), 1)))
        assertThrows(IllegalArgumentException::class.java) {
            policy.compare(original, IdempotencyBinding("other_scope", "private-key", "a".repeat(64), 1))
        }
    }
    @Test fun `policy cannot silently discard retention or payload version`() {
        assertThrows(IllegalArgumentException::class.java) { policy.copy(retention = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(canonicalPayloadSchemaVersion = 0) }
        assertThrows(IllegalArgumentException::class.java) { IdempotencyBinding("orion_tasks", "k", "raw-private-content", 1) }
        assertThrows(IllegalArgumentException::class.java) {
            policy.compare(original, IdempotencyBinding("orion_tasks", "private-key", "a".repeat(64), 2))
        }
        assertFalse(original.toString().contains("private-key"))
        assertFalse(original.toString().contains("a".repeat(64)))
    }
}
