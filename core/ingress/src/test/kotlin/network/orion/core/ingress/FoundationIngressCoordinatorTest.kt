package network.orion.core.ingress

import network.orion.core.health.CoreReadiness
import org.junit.Assert.*
import org.junit.Test

class FoundationIngressCoordinatorTest {
    @Test fun `no mutable or ephemeral request is falsely queued or executed`() {
        for (readiness in CoreReadiness.entries) {
            val coordinator = FoundationIngressCoordinator { readiness }
            assertEquals(IngressAdmission.UNAVAILABLE, coordinator.admit(IngressClass.MUTATING_DURABLE))
            assertEquals(IngressAdmission.UNAVAILABLE, coordinator.admit(IngressClass.EPHEMERAL))
            assertEquals(if (readiness.isStartupBarrierOpen) IngressAdmission.READ_ALLOWED else IngressAdmission.GATED,
                coordinator.admit(IngressClass.READ_ONLY_SAFE))
        }
    }
}
