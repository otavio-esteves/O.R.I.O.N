package network.orion.app.notifications

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.orion.core.actions.*
import network.orion.core.commands.notifications.*
import network.orion.core.orchestrator.notifications.*
import network.orion.core.time.OrionClock
import network.orion.data.actions.ActionReadResult

/** One Activity's real user callbacks. Permission prompting is a separate Activity UI operation. */
internal class OrionNotificationUiGateway(private val channel:OrionNotificationAdmissionOwner.UiChannel,
    private val kernel:()->OrionNotificationKernel?,private val clock:OrionClock,
    private val onStorageFailure:()->Unit={}):NotificationGateway {
    @Volatile private var displayed:Map<UUID,OrionNotificationPending> = emptyMap()
    override suspend fun prepare(command:PostNotification):PostNotificationResult {
        when(channel.captureFromUser(command)) {
            is OrionNotificationAdmissionResult.Invalid->return PostNotificationResult.REJECTED
            OrionNotificationAdmissionResult.Unavailable->return PostNotificationResult.UNAVAILABLE
            is OrionNotificationAdmissionResult.Admitted->Unit
        }
        val ready=(OrionPostNotificationPreparer.prepare(command) as PostNotificationPreparation.Ready).notification
        return when(withContext(Dispatchers.IO) { guarded { kernel()?.submit(OrionNotificationIngressWire("POST",ready.requestId,
            ready.idempotencyKey,ready.correlationId,ready.title,ready.body),ready.idempotencyKey)?:NotificationOutcome.UNAVAILABLE } }) {
            NotificationOutcome.QUEUED,NotificationOutcome.APPLIED->PostNotificationResult.QUEUED
            NotificationOutcome.REJECTED->PostNotificationResult.REJECTED
            else->PostNotificationResult.UNAVAILABLE
        }
    }
    override suspend fun pending():List<NotificationConfirmation> {
        val selected=withContext(Dispatchers.IO) { kernel()?.pending()?:emptyList() }
        displayed=selected.associateBy { it.display.actionId }
        return selected.map { it.display }
    }
    override suspend fun confirm(actionId:UUID,displayedVersion:Long):NotificationOutcome {
        val selected=displayed[actionId]?.takeIf { it.display.version==displayedVersion }?:return NotificationOutcome.REJECTED
        val preview=selected.preview?:return NotificationOutcome.REJECTED
        val input=preview.notification
        val captured=(channel.captureFromUser(PostNotification(input.requestId,input.title,input.body,input.idempotencyKey,input.correlationId))
            as? OrionNotificationAdmissionResult.Admitted)?.request?:return NotificationOutcome.BLOCKED
        // New consent is recorded only at this live gesture for a not-yet-confirmed exact action.
        val newReceipt=if(selected.display.alreadyConfirmed)null else ActionConfirmationBinding(clock).recordConfirmation(
            OrionPostNotificationActionStore.normalized(actionId,input,preview.policyVersion),clock.wallNow().plusSeconds(60))
        return withContext(Dispatchers.IO) { guarded {
            val k=kernel()?:return@guarded NotificationOutcome.UNAVAILABLE
            val receipt=newReceipt?:((k.actions.read(actionId) as? ActionReadResult.Ready)?.action?.confirmation?:return@guarded NotificationOutcome.REJECTED)
            val wire=OrionNotificationIngressWire(if(selected.display.alreadyConfirmed)"EXECUTE" else "POST",input.requestId,
                input.idempotencyKey,input.correlationId,actionId=actionId,actionVersion=preview.version,
                fingerprint=preview.confirmationFingerprint,policyVersion=preview.policyVersion,receipt=receipt)
            val admitted=k.submit(wire,UUID.randomUUID())
            if(admitted!=NotificationOutcome.APPLIED)return@guarded admitted
            // The queue transaction has committed. Only this explicit focused callback can invoke externally.
            k.executeFromUser(captured,actionId)
        } }
    }
    override suspend fun dismiss(actionId:UUID,displayedVersion:Long):NotificationOutcome {
        val selected=displayed[actionId]?.takeIf { it.display.version==displayedVersion }?:return NotificationOutcome.REJECTED
        if(!channel.captureDismissFromUser())return NotificationOutcome.BLOCKED
        return withContext(Dispatchers.IO) { guarded { kernel()?.submit(selected.dismissWire,UUID.randomUUID())?:NotificationOutcome.UNAVAILABLE } }
    }
    override suspend fun availability()=withContext(Dispatchers.IO) { kernel()?.poster?.availability()?:NotificationAvailability.UNAVAILABLE }
    override suspend fun processPending()=withContext(Dispatchers.IO) { guarded { kernel()?.ingress?.drain(25)?:0 } }
    private fun <T> guarded(body:()->T):T=try { body() }
        catch(c:kotlinx.coroutines.CancellationException) { throw c }
        catch(i:InterruptedException) { Thread.currentThread().interrupt();throw i }
        catch(f:Exception) { onStorageFailure();throw f }
}
