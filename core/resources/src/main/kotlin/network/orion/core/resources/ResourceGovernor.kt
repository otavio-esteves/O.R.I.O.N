package network.orion.core.resources

import network.orion.core.exitinfo.ProcessExitSignal

/** Minimal model declaration; not a model selection or benchmark qualification. */
data class InferenceModelProfile(val modelId: String, val estimatedMemoryBytes: Long) {
    init {
        require(modelId.isNotBlank() && estimatedMemoryBytes > 0) { "Model profile must identify a positive memory estimate." }
    }
}

enum class MemoryRisk { UNKNOWN, LOW, HIGH, CRITICAL }

/**
 * Every inference/load requires a fresh decision using current and previous-exit
 * evidence. Main and aggregate app budgets both matter. Headroom is best-effort.
 */
interface ResourceGovernor {
    fun canRunInference(): Boolean
    fun allowedModelTier(): String?
    fun maxThreads(): Int
    fun maxContext(): Int
    fun maxTokens(): Int
    fun keepAliveMs(): Long
    fun memoryRisk(): MemoryRisk
    fun estimatedHeadroom(): Long?
    fun recentProcessExitSignals(): List<ProcessExitSignal>
    fun canLoadModel(modelProfile: InferenceModelProfile): Boolean
}

/** Foundation never loads models, preloads or restarts an inference process. */
class UnavailableResourceGovernor(private val exits: List<ProcessExitSignal> = emptyList()) : ResourceGovernor {
    override fun canRunInference(): Boolean = false
    override fun allowedModelTier(): String? = null
    override fun maxThreads(): Int = 0
    override fun maxContext(): Int = 0
    override fun maxTokens(): Int = 0
    override fun keepAliveMs(): Long = 0
    override fun memoryRisk(): MemoryRisk = MemoryRisk.UNKNOWN
    override fun estimatedHeadroom(): Long? = null
    override fun recentProcessExitSignals(): List<ProcessExitSignal> = exits.toList()
    override fun canLoadModel(modelProfile: InferenceModelProfile): Boolean = false
}
