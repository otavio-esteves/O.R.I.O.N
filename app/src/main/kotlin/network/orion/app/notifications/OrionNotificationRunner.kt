package network.orion.app.notifications

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.*
import network.orion.core.orchestrator.notifications.NotificationOutcome
import network.orion.core.policy.notifications.*
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.skills.api.SkillContext
import network.orion.skills.notifications.*

/**
 * Bounded external runner: claim commits before invoking Android; neither a Room
 * transaction nor a durable-ingress consumer may call this entry. An uncertain
 * attempt never returns to CONFIRMED and is never invoked again automatically.
 * The barrier monitor encloses only the local claim, never Android Binder/proof.
 * Owned terminal metadata may finish independently after a later gate closure.
 */
internal class OrionNotificationRunner(private val db:OrionDatabase,private val actions:OrionActionStore,
    private val clock:OrionClock,private val barrier:StartupBarrier,private val poster:OrionNotificationPoster) {
    fun execute(admission:OrionPostNotificationAdmission,actionId:UUID):NotificationOutcome {
        if(db.inTransaction()||Thread.holdsLock(barrier))return NotificationOutcome.BLOCKED
        var outcome=NotificationOutcome.BLOCKED
        val engine=object:ActionEngine {
            fun executeLocal(id:UUID):ActionExecutionResult {
                if(id!=actionId)return ActionExecutionResult.UNAVAILABLE
                outcome=executeOpen(admission,id)
                return if(outcome==NotificationOutcome.POSTED)ActionExecutionResult.SUCCEEDED else ActionExecutionResult.BLOCKED
            }
            override suspend fun execute(actionId:UUID)=executeLocal(actionId)
        }
        engine.executeLocal(actionId)
        return outcome
    }
    private fun executeOpen(admission:OrionPostNotificationAdmission,id:UUID):NotificationOutcome {
        check(!db.inTransaction()&&!Thread.holdsLock(barrier))
        if(!barrier.isOpen())return NotificationOutcome.BLOCKED
        val input=admission.notification
        fun freshPolicy()=admission.evaluate() is PostNotificationPolicyDecision.NeedsConfirmation
        val stored=(actions.read(id) as? ActionReadResult.Ready)?.action?:return NotificationOutcome.UNAVAILABLE
        val expected=OrionPostNotificationActionStore.normalized(id,input,OrionPostNotificationPolicy.POLICY_VERSION)
        val row=db.actions().find(id.toString())?:return NotificationOutcome.UNAVAILABLE
        if(stored.request!=expected||!stored.requiresConfirmation||row.idempotencyScope!="notification.post"||
            row.idempotencyKey!=input.idempotencyKey.toString())return NotificationOutcome.REJECTED
        when(stored.status) {
            ActionStatus.SUCCEEDED->return NotificationOutcome.POSTED
            ActionStatus.UNKNOWN,ActionStatus.MANUAL_REVIEW,ActionStatus.RUNNING->return NotificationOutcome.UNKNOWN
            ActionStatus.CONFIRMED->Unit
            else->return NotificationOutcome.REJECTED
        }
        if(db.events().count()>=10_000)return NotificationOutcome.BLOCKED
        val authority=object:ActionExecutionAuthority {
            override fun startupBarrierOpen()=barrier.isOpen()
            override fun descriptorFor(skill:String)=OrionPostNotificationDescriptor.value.takeIf { it.name==skill }
            override fun currentPolicyVersion()=OrionPostNotificationPolicy.POLICY_VERSION
            override fun policyAllows(request:NormalizedActionRequest)=request==expected&&freshPolicy()
            override fun capabilityAvailable(capability:Capability)=capability==OrionPostNotificationDescriptor.WRITE_CAPABILITY&&
                poster.availability()==network.orion.core.orchestrator.notifications.NotificationAvailability.AVAILABLE&&freshPolicy()
            override fun permissionGranted(permission:String)=permission==OrionPostNotificationDescriptor.POST_PERMISSION&&poster.permissionGranted()
            override fun userPresent()=freshPolicy()
        }
        val execution=OrionActionExecutionStore(db,actions,clock,authority)
        // Only the bounded local claim transaction holds the admission monitor.
        // Gate closure must never wait for Android Binder or registration proof.
        val claim=barrier.whileOpen {
            execution.claim(id,stored.version,"orion_notification_main",Duration.ofSeconds(30))
        } as? ActionClaimResult.Claimed?:return NotificationOutcome.BLOCKED
        check(!db.inTransaction()&&!Thread.holdsLock(barrier))
        val originalReceipt=stored.confirmation?:return NotificationOutcome.BLOCKED
        fun invocationAllowed(notification:network.orion.core.commands.notifications.ValidatedPostNotification,owner:SkillContext):Boolean {
            if(db.inTransaction()||Thread.holdsLock(barrier))return false
            val current=db.actions().find(id.toString())?:return false
            if(owner.actionId!=id||owner.executionId!=claim.lease.executionId||current.status!=ActionStatus.RUNNING||
                current.version!=owner.actionVersion||current.executionId!=owner.executionId.toString()||
                current.claimedBy!=claim.lease.claimedBy||current.leaseBootSessionId!=claim.lease.bootSessionId.bootCount||
                current.leaseStartedElapsedRealtime!=claim.lease.startedElapsedRealtime||current.leaseExpiresElapsedRealtime!=claim.lease.expiresElapsedRealtime)
                return false
            val bound=(actions.read(id) as? ActionReadResult.Ready)?.action?:return false
            if(bound.request!=expected||bound.confirmation!=originalReceipt||notification.canonicalNormalizedArguments!=input.canonicalNormalizedArguments||
                ActionExecutionValidation.denyReason(bound.request,bound.requiresConfirmation,authority)!=null)return false
            // These platform/policy reads may be slow. Never validate expiry before them.
            if(!barrier.isOpen()||!poster.permissionGranted()||!freshPolicy())return false
            if(ActionConfirmationBinding(clock).validate(expected,originalReceipt)!=ConfirmationBindingResult.VALID_BINDING)return false
            if(claim.lease.statusAt(clock.monotonicNow())!=ActionLeaseStatus.ACTIVE)return false
            return barrier.isOpen()&&!db.inTransaction()&&!Thread.holdsLock(barrier)
        }
        val skill=OrionPostNotificationSkill(OrionOwnedNotificationPoster { notification,owner->
            if(!invocationAllowed(notification,owner))return@OrionOwnedNotificationPoster NotificationPostResult.NOT_INVOKED
            // The platform invokes this same bound gate after all slow preparation,
            // immediately before notify. There is no mutable global callback/permit.
            poster.post(id,notification,OrionNotificationInvocationPermit { invocationAllowed(notification,owner) })
        })
        val intent=OrionIntent("notification.post",1.0,input,"MAIN_UI",false,input.correlationId)
        val outcome=try { skill.executeOwned(intent,SkillContext(id,claim.lease.executionId,claim.version)) }
            catch(cancelled:java.util.concurrent.CancellationException) { markUnknown(execution,id,claim);throw cancelled }
            catch(interrupted:InterruptedException) { markUnknown(execution,id,claim);Thread.currentThread().interrupt();throw interrupted }
            catch(_:Exception) { NotificationPostResult.UNKNOWN }
        return when(outcome) {
            NotificationPostResult.POSTED-> {
                try {
                    db.runInTransaction {
                        check(execution.finish(id,claim.version,claim.lease.executionId,ActionStatus.SUCCEEDED)==ActionMutation.APPLIED)
                        val finished=checkNotNull(db.actions().find(id.toString()))
                        db.events().append(DomainEventOutboxEntity(UUID.randomUUID().toString(),id.toString(),finished.version,
                            "NOTIFICATION_POSTED",1,Sensitivity.SENSITIVE,finished.updatedAt,input.correlationId.toString()))
                    }
                    NotificationOutcome.POSTED
                } catch(_:Exception) { markUnknown(execution,id,claim);NotificationOutcome.UNKNOWN }
            }
            NotificationPostResult.UNKNOWN->{markUnknown(execution,id,claim);NotificationOutcome.UNKNOWN}
            NotificationPostResult.NOT_INVOKED->{
                execution.finish(id,claim.version,claim.lease.executionId,ActionStatus.FAILED)
                NotificationOutcome.BLOCKED
            }
            NotificationPostResult.NOT_OWNED->NotificationOutcome.BLOCKED
        }
    }
    private fun markUnknown(execution:OrionActionExecutionStore,id:UUID,claim:ActionClaimResult.Claimed) {
        execution.finish(id,claim.version,claim.lease.executionId,ActionStatus.UNKNOWN)
    }
}
