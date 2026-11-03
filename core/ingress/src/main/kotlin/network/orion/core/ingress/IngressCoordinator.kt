package network.orion.core.ingress

import network.orion.core.health.CoreReadiness

enum class IngressClass { READ_ONLY_SAFE, MUTATING_DURABLE, EPHEMERAL }

/** Admission only; no value authorizes business effects or asserts durable enqueueing. */
enum class IngressAdmission { READ_ALLOWED, GATED, UNAVAILABLE }

fun interface IngressCoordinator {
    fun admit(ingressClass: IngressClass): IngressAdmission
}

/**
 * F1 has safe local reads only. A future durable coordinator must persist mutations
 * and enforce idempotency before claiming enqueueing; it cannot replace this denial
 * with an in-memory queue. No ingress payload or execution is implemented here.
 */
class FoundationIngressCoordinator(private val readiness: () -> CoreReadiness) : IngressCoordinator {
    override fun admit(ingressClass: IngressClass): IngressAdmission = when (ingressClass) {
        IngressClass.READ_ONLY_SAFE -> if (readiness().isStartupBarrierOpen) {
            IngressAdmission.READ_ALLOWED
        } else {
            IngressAdmission.GATED
        }
        IngressClass.MUTATING_DURABLE, IngressClass.EPHEMERAL -> IngressAdmission.UNAVAILABLE
    }
}
