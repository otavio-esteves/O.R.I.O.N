package network.orion.app.reminderdelivery

import java.util.concurrent.CancellationException
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier

/** MAIN's current resumed/focused surface. Its epoch cannot grant authority after recreation/recovery. */
internal class OrionReminderDeliveryAdmissions(
    private val role: OrionProcessRole,
    private val barrier: StartupBarrier,
    private val presence: () -> Boolean,
    private val capability: () -> Boolean,
) {
    private var current: UiChannel? = null
    private var epoch = 0L
    @Synchronized fun openUiChannel() = UiChannel().also { advance(); current = it }
    @Synchronized fun invalidateAdmissions() { advance() }
    private fun advance() { epoch = Math.addExact(epoch, 1) }

    inner class UiChannel internal constructor() {
        private var resumed = false
        private var focused = false
        private var closed = false
        fun onResumed(value: Boolean) = synchronized(this@OrionReminderDeliveryAdmissions) {
            if (!closed && resumed != value) { resumed = value; advance() }
        }
        fun onWindowFocus(value: Boolean) = synchronized(this@OrionReminderDeliveryAdmissions) {
            if (!closed && focused != value) { focused = value; advance() }
        }
        fun close() = synchronized(this@OrionReminderDeliveryAdmissions) {
            if (!closed) { closed = true; if (current === this) current = null; advance() }
        }
        private fun active() = !closed && current === this && resumed && focused
        fun capture(): Admission? = synchronized(this@OrionReminderDeliveryAdmissions) {
            if (!active() || !evidence()) null else Admission(this, epoch)
        }
        internal fun isCurrent() = active()
    }

    inner class Admission internal constructor(private val channel: UiChannel, private val capturedEpoch: Long) {
        fun valid() = synchronized(this@OrionReminderDeliveryAdmissions) {
            current === channel && channel.isCurrent() && capturedEpoch == epoch && evidence()
        }
    }

    @Synchronized fun replayAllowed(): Boolean = current?.isCurrent() == true && evidence()

    private fun evidence(): Boolean = try {
        role == OrionProcessRole.MAIN && barrier.isOpen() && presence() && capability()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
    catch (_: Exception) { false }
}
