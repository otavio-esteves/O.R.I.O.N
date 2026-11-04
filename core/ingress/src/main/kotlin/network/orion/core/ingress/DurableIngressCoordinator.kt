package network.orion.core.ingress

import java.util.UUID
import network.orion.core.health.CoreReadiness
import network.orion.core.model.Sensitivity

/** Sensitive bytes are copied and never included in diagnostics. IDs are preferred over content. */
class DurableIngressRequest(
    val ingressId: UUID,
    val scope: String,
    val idempotencyKey: UUID,
    val source: String,
    val commandType: String,
    val schemaVersion: Int,
    val sensitivity: Sensitivity,
    val referenceSufficient: Boolean,
    val referenceId: UUID?,
    content: ByteArray?,
    val lifetimeMillis: Long,
    val idempotencyRetentionMillis: Long,
    val priority: Int = 0,
    val correlationId: UUID,
) {
    private val content = content?.also { require(it.size <= MAX_CONTENT_BYTES) { "Ingress payload exceeds limit." } }?.copyOf()
    fun copyContent(): ByteArray? = content?.copyOf()
    init {
        require(scope.matches(TECHNICAL_ID) && source.matches(TECHNICAL_ID) && commandType.matches(TECHNICAL_ID))
        require(schemaVersion > 0 && lifetimeMillis > 0 && idempotencyRetentionMillis >= lifetimeMillis)
        require(priority in 0..100)
        require(if (referenceSufficient) referenceId != null && content == null else referenceId == null && content != null)
        require(content == null || content.size <= MAX_CONTENT_BYTES)
    }
    override fun toString(): String = "DurableIngressRequest(<redacted>)"
    companion object {
        private val TECHNICAL_ID = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")
        const val MAX_CONTENT_BYTES = 256 * 1024
    }
}

enum class DurableIngressOutcome {
    QUEUED, DEDUP, IDEMPOTENCY_CONFLICT, INGRESS_ID_CONFLICT, STORAGE_UNAVAILABLE,
    KEY_UNAVAILABLE, KEY_INVALIDATED, UNREADABLE, CAPACITY_EXCEEDED,
}

/** Persistent Core port. consumeNext must atomically apply local changes and mark consumed, or roll back. */
interface DurableIngressQueue {
    fun enqueue(request: DurableIngressRequest): DurableIngressOutcome
    /** Returns false when empty. A consumer must never invoke an external effect inside the transaction. */
    fun consumeNext(): Boolean
}

/** Durable persistence is permitted while gated; processing requires the actual operational barrier. */
class DurableIngressCoordinator(
    private val queue: DurableIngressQueue,
    private val barrier: StartupBarrier,
    private val readiness: () -> CoreReadiness,
    private val storageAvailable: () -> Boolean,
) : IngressCoordinator {
    override fun admit(ingressClass: IngressClass): IngressAdmission = when (ingressClass) {
        IngressClass.READ_ONLY_SAFE -> if (storageAvailable()) IngressAdmission.READ_ALLOWED else IngressAdmission.GATED
        IngressClass.MUTATING_DURABLE -> if (storageAvailable()) IngressAdmission.GATED else IngressAdmission.UNAVAILABLE
        IngressClass.EPHEMERAL -> IngressAdmission.UNAVAILABLE
    }

    fun submit(request: DurableIngressRequest): DurableIngressOutcome =
        if (storageAvailable()) queue.enqueue(request) else DurableIngressOutcome.STORAGE_UNAVAILABLE

    fun drain(limit: Int): Int {
        require(limit in 1..100)
        var processed = 0
        repeat(limit) {
            // Recheck between transactions so a health failure stops the next admission.
            val consumed = barrier.whileOpen {
                if (storageAvailable() && readiness().isStartupBarrierOpen) queue.consumeNext() else false
            } ?: false
            if (!consumed) return processed
            processed++
        }
        return processed
    }
}
