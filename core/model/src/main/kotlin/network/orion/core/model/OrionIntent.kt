package network.orion.core.model

import java.util.UUID

/**
 * In-memory interpretation produced by the Intent Router (Architecture V3.2 §18).
 *
 * [confidence] is a finite value in [0, 1], not an authorization or a routing
 * threshold. [requiresCortex] describes the producer's interpretation; it neither
 * starts inference nor bypasses ResourceGovernor. [correlationId] is supplied by
 * the producer and follows the logical flow without creating an action identity.
 *
 * [arguments] must be an immutable domain value; use [Unit] when no arguments are
 * needed. This envelope cannot enforce deep immutability and does not normalize,
 * validate a Skill schema or establish trust in external content. Identifiers are
 * preserved as supplied, without a catalog or implicit normalization.
 *
 * This is neither an ActionRequest nor a DB/IPC format. Schema/semantic validation,
 * SkillDescriptor lookup, policy, capability, permission, confirmation and gated
 * ingress remain required before any applicable effect (Architecture §58).
 */
class OrionIntent<out A : Any>(
    val intentType: String,
    val confidence: Double,
    val arguments: A,
    val source: String,
    val requiresCortex: Boolean,
    val correlationId: UUID,
) {
    init {
        require(intentType.isNotBlank()) { "Intent type must not be blank." }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Intent confidence must be finite and between zero and one."
        }
        require(source.isNotBlank()) { "Intent source must not be blank." }
    }

    // Neither arguments nor arbitrary producer metadata may enter diagnostics.
    override fun toString(): String = "OrionIntent(arguments=<redacted>)"
}
