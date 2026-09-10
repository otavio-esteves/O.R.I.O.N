package network.orion.core.health

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OrionHealthTest(
    private val readinessName: String,
    private val statusName: String,
    private val isValid: Boolean,
) {
    private val observedAt = Instant.parse("2026-09-07T12:00:00Z")

    @Test
    fun `health snapshot enforces the readiness and status matrix`() {
        val readiness = CoreReadiness.valueOf(readinessName)
        val status = OrionHealthStatus.valueOf(statusName)

        if (isValid) {
            val health = OrionHealth(readiness, status, observedAt)
            assertEquals(readiness, health.readiness)
            assertEquals(status, health.status)
            assertEquals(observedAt, health.observedAt)
        } else {
            assertThrows(IllegalArgumentException::class.java) {
                OrionHealth(readiness, status, observedAt)
            }
        }
    }

    @Test
    fun `copy cannot bypass readiness and status consistency`() {
        val readiness = CoreReadiness.valueOf(readinessName)
        val status = OrionHealthStatus.valueOf(statusName)
        val original = OrionHealth(CoreReadiness.READY, OrionHealthStatus.HEALTHY, observedAt)

        if (isValid) {
            assertEquals(
                OrionHealth(readiness, status, observedAt),
                original.copy(readiness = readiness, status = status),
            )
        } else {
            assertThrows(IllegalArgumentException::class.java) {
                original.copy(readiness = readiness, status = status)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1}: valid={2}")
        fun combinations(): List<Array<Any>> {
            val validStatuses = mapOf(
                "STARTING" to "UNAVAILABLE",
                "RECOVERING" to "RECOVERING",
                "READY" to "HEALTHY",
                "DEGRADED_SAFE" to "DEGRADED",
                "BLOCKED" to "FAILED",
            )
            return validStatuses.flatMap { (readiness, validStatus) ->
                validStatuses.values.map { status ->
                    arrayOf<Any>(readiness, status, status == validStatus)
                }
            }
        }
    }
}
