package network.orion.app.reminderdelivery

import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import org.junit.Assert.*
import org.junit.Test

class OrionReminderDeliveryAdmissionsTest {
    @Test fun admissionRequiresMainFocusedUnlockedReadySurfaceAndFreshCapability() {
        val barrier = StartupBarrier()
        var present = true
        var capable = true
        val owner = OrionReminderDeliveryAdmissions(OrionProcessRole.MAIN, barrier, { present }, { capable })
        val ui = owner.openUiChannel()
        ui.onResumed(true); ui.onWindowFocus(true)
        assertNull(ui.capture())
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        val captured = checkNotNull(ui.capture())
        assertTrue(captured.valid())
        capable = false; assertFalse(captured.valid())
        capable = true; present = false; assertNull(ui.capture())
        present = true; ui.onWindowFocus(false); assertFalse(captured.valid())
        ui.onWindowFocus(true); assertFalse(captured.valid())
        val fresh = checkNotNull(ui.capture())
        owner.invalidateAdmissions(); assertFalse(fresh.valid())
        ui.close(); assertFalse(owner.replayAllowed())
    }

    @Test fun auxiliaryRoleAndReplacedChannelCannotAuthorize() {
        val barrier = StartupBarrier()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        val auxiliary = OrionReminderDeliveryAdmissions(OrionProcessRole.AI, barrier, { true }, { true })
        auxiliary.openUiChannel().apply { onResumed(true); onWindowFocus(true); assertNull(capture()) }
        val main = OrionReminderDeliveryAdmissions(OrionProcessRole.MAIN, barrier, { true }, { true })
        val old = main.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        val admitted = checkNotNull(old.capture())
        main.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        assertFalse(admitted.valid())
        assertNull(old.capture())
    }
}
