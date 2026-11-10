package network.orion.app.ai

import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.data.database.ModelRegistryEntity
import org.junit.Assert.*
import org.junit.Test

class OrionCortexRuntimeOwnerPolicyTest {
    private val main = "network.orion"
    private val hash = "a".repeat(64)
    private val plan = OrionInferenceResourcePlan(InferenceModelProfile(hash, 1000), "fixture", 2, 512, 64,
        0, 10_000, 20_000, 100, 20, 2)
    private val model = ModelRegistryEntity(hash, "fixture", "verified/$hash.gguf", hash, 1000,
        "fixture", "fixture", 512, "READY", 0, 0)
    private fun matches(row: ModelRegistryEntity? = model, selected: OrionInferenceResourcePlan = plan,
        context: Int = 512, threads: Int = 2, tokens: Int = 64) = profileMatchesReadyModel(selected, row, context, threads, tokens)

    @Test fun `inventory includes application default and every declared component`() {
        assertEquals(setOf(main, "$main:ai"), supportedCortexInventory(main, main, listOf(null, main, ":ai")))
        assertEquals(setOf(main, "$main:ai"), supportedCortexInventory(main, main, listOf("$main:ai")))
    }

    @Test fun `voice unknown foreign absent and custom default process inventories deny`() {
        listOf(listOf("$main:ai", "$main:voice"), listOf("$main:ai", "$main:voice_session"),
            listOf("$main:ai", "$main:unknown"), listOf("$main:ai", "other.app"), listOf(main), emptyList()).forEach {
            assertNull(supportedCortexInventory(main, main, it))
        }
        assertNull(supportedCortexInventory(main, null, listOf("$main:ai")))
        assertNull(supportedCortexInventory(main, "$main:other", listOf("$main:ai")))
    }

    @Test fun `inventory cannot require unbounded copying from a lazy source`() {
        var reads = 0
        val huge = object : AbstractList<String?>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): String {
                check(++reads <= 257)
                return "$main:ai"
            }
        }
        assertNull(supportedCortexInventory(main, main, huge))
        assertEquals(257, reads)
    }

    @Test fun `READY profile must bind immutable hash and reserve at least the complete file size`() {
        assertTrue(matches())
        assertTrue(matches(model.copy(sizeBytes = 999)))
        assertFalse(matches(model.copy(sizeBytes = 1001)))
        assertFalse(matches(model.copy(sizeBytes = 0)))
        assertFalse(matches(model.copy(modelId = "b".repeat(64))))
        assertFalse(matches(model.copy(sha256 = "b".repeat(64))))
        assertFalse(matches(selected = plan.copy(model = InferenceModelProfile("b".repeat(64), 1000))))
        assertFalse(validModelHash("0".repeat(64)))
        assertFalse(validModelHash("A".repeat(64)))
    }

    @Test fun `pending invalid quarantined absent or unqualified metadata never permits a selected model`() {
        listOf("IMPORTED", "VALIDATING", "INVALID", "QUARANTINED").forEach { assertFalse(matches(model.copy(status = it))) }
        assertFalse(matches(null))
        assertFalse(matches(model.copy(contextLimit = null)))
        assertFalse(matches(model.copy(contextLimit = 511)))
    }

    @Test fun `requested execution settings fit both qualified envelope and structural model limit`() {
        assertTrue(matches(context = 32, threads = 1, tokens = 1))
        assertFalse(matches(context = 31))
        assertFalse(matches(context = 513))
        assertFalse(matches(threads = 0))
        assertFalse(matches(threads = 3))
        assertFalse(matches(tokens = 0))
        assertFalse(matches(tokens = 65))
    }
}
