package network.orion.core.model

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionIntentTest {
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private data class Arguments(val reference: String)

    private fun intent(
        intentType: String = "CompleteTask",
        confidence: Double = 0.8,
        source: String = "ui",
        requiresCortex: Boolean = false,
    ) = OrionIntent(
        intentType = intentType,
        confidence = confidence,
        arguments = Arguments("task-reference"),
        source = source,
        requiresCortex = requiresCortex,
        correlationId = correlation,
    )

    @Test
    fun `preserves interpreted fields and typed arguments without creating identity`() {
        val original = intent()
        val arguments: Arguments = original.arguments

        assertEquals("CompleteTask", original.intentType)
        assertEquals(0.8, original.confidence, 0.0)
        assertEquals(Arguments("task-reference"), arguments)
        assertEquals("ui", original.source)
        assertFalse(original.requiresCortex)
        assertSame(correlation, original.correlationId)
    }

    @Test
    fun `confidence includes both endpoints without inventing a routing threshold`() {
        for (confidence in listOf(0.0, 0.25, 1.0)) {
            assertEquals(confidence, intent(confidence = confidence).confidence, 0.0)
        }
    }

    @Test
    fun `rejects nonfinite or out of range confidence`() {
        for (confidence in listOf(
            Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            -Double.MIN_VALUE, Math.nextUp(1.0), -1.0, 2.0,
        )) {
            assertThrows(IllegalArgumentException::class.java) { intent(confidence = confidence) }
        }
    }

    @Test
    fun `requires a nonblank intent type and source`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { intent(intentType = blank) }
            assertThrows(IllegalArgumentException::class.java) { intent(source = blank) }
        }
    }

    @Test
    fun `cortex requirement is explicit metadata independent of confidence`() {
        assertTrue(intent(confidence = 1.0, requiresCortex = true).requiresCortex)
        assertFalse(intent(confidence = 0.0, requiresCortex = false).requiresCortex)
    }

    @Test
    fun `argumentless intent preserves Unit`() {
        val original = OrionIntent("GetTime", 1.0, Unit, "ui", false, correlation)
        assertSame(Unit, original.arguments)
    }

    @Test
    fun `diagnostics do not evaluate arguments or expose producer metadata`() {
        val arguments = object {
            override fun toString(): String = error("Arguments must not be rendered")
        }
        val original = OrionIntent(
            "private-type", 0.8, arguments, "private-source", true, correlation,
        )

        assertEquals("OrionIntent(arguments=<redacted>)", original.toString())
    }

    @Test
    fun `validation errors do not expose producer content`() {
        val failures = listOf(
            assertThrows(IllegalArgumentException::class.java) {
                intent(intentType = "private-type", source = "private-source", confidence = Double.NaN)
            },
            assertThrows(IllegalArgumentException::class.java) {
                intent(intentType = "private-type", source = " ")
            },
            assertThrows(IllegalArgumentException::class.java) {
                intent(intentType = " ", source = "private-source")
            },
        )
        for (failure in failures) {
            assertFalse(failure.message.orEmpty().contains("private-type"))
            assertFalse(failure.message.orEmpty().contains("private-source"))
        }
    }
}
