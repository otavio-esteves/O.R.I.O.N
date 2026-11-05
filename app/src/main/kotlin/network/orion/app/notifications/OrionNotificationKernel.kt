package network.orion.app.notifications

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.notifications.*
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.repository.*
import org.json.JSONObject

internal class OrionNotificationPending(val display:NotificationConfirmation,val preview:OrionNotificationActionPreview?,val dismissWire:OrionNotificationIngressWire) {
    override fun toString()="OrionNotificationPending(<redacted>)"
}

/** MAIN preparation queue. Its transaction never calls the external runner/poster. */
internal class OrionNotificationKernel(private val db:OrionDatabase,encryption:OrionFieldEncryption,
    private val clock:OrionClock,private val barrier:StartupBarrier,private val owner:OrionNotificationAdmissionOwner,
    readiness:()->CoreReadiness,storageAvailable:()->Boolean,val poster:OrionNotificationPoster) {
    val actions=OrionActionStore(db,encryption,clock)
    private val preparation=OrionPostNotificationActionStore(db,actions,clock,barrier)
    private val runner=OrionNotificationRunner(db,actions,clock,barrier,poster)
    private var processingId:UUID?=null
    private val store=OrionDurableIngressStore(db,encryption,clock,barrier,LocalIngressConsumer { payload->
        processingId=payload.ingressId
        val origin=try { OrionVerifiedNotificationIngress.from(payload,db,actions) }
            catch(_:IllegalArgumentException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch(_:org.json.JSONException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
            catch(_:java.io.IOException) { return@LocalIngressConsumer LocalIngressDisposition.REJECTED }
        consume(origin)
        LocalIngressDisposition.CONSUMED
    },consumerScope=OrionNotificationIngressWire.SCOPE)
    private val queue=object:DurableIngressQueue {
        override fun enqueue(request:DurableIngressRequest)=store.enqueue(request)
        override fun consumeNext():Boolean {
            check(Thread.holdsLock(barrier));processingId=null
            return try { store.consumeNext() } catch(failure:OrionNotificationTransactionBlocked.Action) {
                if(failure.reason in setOf(OrionNotificationActionFailure.STARTUP_GATED,OrionNotificationActionFailure.POLICY_DENIED,
                    OrionNotificationActionFailure.POLICY_UNAVAILABLE,OrionNotificationActionFailure.KEY_UNAVAILABLE))false
                else {
                    val row=processingId?.let { db.ingress().find(it.toString()) }?:throw failure
                    check(row.status==DurableIngressStatus.QUEUED)
                    check(db.ingress().transition(row.ingressId,row.version,DurableIngressStatus.REJECTED,clock.wallNow().toEpochMilli())==ActionMutation.APPLIED)
                    true
                }
            }
        }
    }
    val ingress=DurableIngressCoordinator(queue,barrier,readiness,storageAvailable)
    fun submit(wire:OrionNotificationIngressWire,key:UUID):NotificationOutcome {
        val bytes=wire.encode()
        val result=try { ingress.submit(DurableIngressRequest(UUID.randomUUID(),OrionNotificationIngressWire.SCOPE,key,
            OrionNotificationIngressWire.SOURCE,OrionNotificationIngressWire.TYPE,1,Sensitivity.SENSITIVE,false,null,bytes,
            Duration.ofDays(1).toMillis(),Duration.ofDays(90).toMillis(),correlationId=wire.trace)) } finally { bytes.fill(0) }
        if(result !in setOf(DurableIngressOutcome.QUEUED,DurableIngressOutcome.DEDUP))return if(result==DurableIngressOutcome.IDEMPOTENCY_CONFLICT)NotificationOutcome.REJECTED else NotificationOutcome.UNAVAILABLE
        ingress.drain(25)
        return when(db.ingress().findByKey(OrionNotificationIngressWire.SCOPE,key.toString())?.status) {
            DurableIngressStatus.CONSUMED->if(wire.actionId!=null)NotificationOutcome.APPLIED else NotificationOutcome.QUEUED // Admission acknowledged only; caller must invoke runner separately.
            DurableIngressStatus.REJECTED,DurableIngressStatus.EXPIRED,DurableIngressStatus.FAILED->NotificationOutcome.REJECTED
            else->NotificationOutcome.QUEUED
        }
    }
    fun executeFromUser(admission:OrionPostNotificationAdmission,actionId:UUID):NotificationOutcome {
        check(!db.inTransaction())
        return runner.execute(admission,actionId)
    }
    private fun consume(origin:OrionVerifiedNotificationIngress) {
        val wire=origin.wire
        if(wire.operation=="DISMISS") {
            if(!owner.mayDismiss(origin))unavailable()
            val id=wire.actionId?:invalid()
            val stored=db.actions().find(id.toString())?:invalid()
            if(stored.skill!="notification.post"||stored.version!=wire.actionVersion||
                stored.status !in setOf(ActionStatus.NEEDS_CONFIRMATION,ActionStatus.CONFIRMED)||stored.confirmationFingerprint!=wire.fingerprint)invalid()
            if(actions.cancel(id,stored.version).outcome!=ActionStoreOutcome.APPLIED)invalid()
            return
        }
        val admission=owner.replay(origin)?:unavailable()
        if(wire.actionId==null) { preparation.prepare(admission);return }
        val displayed=OrionNotificationActionPreview(admission.notification,wire.actionId,wire.actionVersion,
            wire.fingerprint,wire.policyVersion,ActionStatus.NEEDS_CONFIRMATION)
        if(wire.operation=="EXECUTE") {
            val stored=(actions.read(wire.actionId) as? ActionReadResult.Ready)?.action?:invalid()
            if(stored.status!=ActionStatus.CONFIRMED||stored.version!=wire.actionVersion||stored.confirmation!=wire.receipt||
                stored.request!=OrionPostNotificationActionStore.normalized(wire.actionId,admission.notification,wire.policyVersion)||
                ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint!=wire.fingerprint||
                stored.confirmation?.let { ActionConfirmationBinding(clock).validate(stored.request,it) }!=ConfirmationBindingResult.VALID_BINDING)invalid()
            return
        }
        if(preparation.confirmWithReceipt(admission,displayed,wire.receipt) !is OrionNotificationActionResult.Ready)invalid()
        // CONFIRMED commits with consumed ingress. External invocation happens only in executeFromUser after return.
    }
    fun pending():List<OrionNotificationPending> = db.actions().notificationConfirmations(50).map { row->
        val id=UUID.fromString(row.actionId)
        val dismiss=OrionNotificationIngressWire("DISMISS",id,UUID.fromString(row.idempotencyKey),UUID.fromString(row.correlationId),
            actionId=id,actionVersion=row.version,fingerprint=row.confirmationFingerprint,policyVersion=row.policyVersion)
        fun unavailable()=OrionNotificationPending(NotificationConfirmation(id,row.version,null,null,row.status==ActionStatus.CONFIRMED),null,dismiss)
        val stored=(actions.read(id) as? ActionReadResult.Ready)?.action?:return@map unavailable()
        try {
            val args=JSONObject(stored.request.canonicalNormalizedArguments)
            val input=(OrionPostNotificationPreparer.prepare(PostNotification(UUID.fromString(args.getString("requestId")),
                args.getString("title"),args.getString("body"),UUID.fromString(row.idempotencyKey),UUID.fromString(row.correlationId))) as? PostNotificationPreparation.Ready)?.notification?:return@map unavailable()
            if(stored.request!=OrionPostNotificationActionStore.normalized(id,input,row.policyVersion))return@map unavailable()
            val preview=OrionNotificationActionPreview(input,id,stored.version,ActionConfirmationBinding(clock).prepare(stored.request).confirmationFingerprint,row.policyVersion,stored.status)
            OrionNotificationPending(NotificationConfirmation(id,stored.version,input.title,input.body,stored.status==ActionStatus.CONFIRMED),preview,dismiss)
        } catch(_:IllegalArgumentException) { unavailable() } catch(_:org.json.JSONException) { unavailable() }
    }
    private fun unavailable():Nothing=throw OrionNotificationTransactionBlocked.Action(OrionNotificationActionFailure.POLICY_UNAVAILABLE)
    private fun invalid():Nothing=throw OrionNotificationTransactionBlocked.Action(OrionNotificationActionFailure.CONFIRMATION_MISMATCH)
}
