package network.orion.app.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.notifications.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.orchestrator.notifications.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.skills.notifications.NotificationPostResult
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fake platform only. Separate Android smoke test observes the real permission and NotificationManager. */
@RunWith(AndroidJUnit4::class)
class OrionNotificationRuntimeTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val name="orion_notification_${UUID.randomUUID()}.db"
    private lateinit var db:OrionDatabase
    private lateinit var kernel:OrionNotificationKernel
    private var wall=Instant.parse("2026-11-05T04:00:00Z")
    private var elapsed=100L
    private var boot=7
    private var permitted=true
    private var capable=true
    private var present=true
    private var invokes=0
    private var beforeInvocationGate:(()->Unit)?=null
    private var runningPermissionReads=0
    private var expireOnSecondRunningPermissionRead=false
    private var postResult=NotificationPostResult.POSTED
    private var postFailure:Throwable?=null
    private var beforeAvailability:(()->Unit)?=null
    private val clock=object:OrionClock {
        override fun wallNow()=wall
        override fun monotonicNow()=MonotonicInstant(BootSessionId(boot),elapsed)
    }
    private val crypto=OrionFieldEncryption(object:FieldKeyProvider {
        override fun currentKey()=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 51 },"AES"))
        override fun keyForVersion(version:Int)=currentKey()
    })
    private val barrier=StartupBarrier()
    private val owner=OrionNotificationAdmissionOwner(OrionProcessRole.MAIN,barrier,
        { if(capable)OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED },
        { if(present)OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED },
        { if(permitted)OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED })
    private val channel=owner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val request=PostNotification(UUID.randomUUID(),"Notificação confidencial","Mensagem exata\nSegunda linha",UUID.randomUUID(),UUID.randomUUID())
    private val poster=object:OrionNotificationPoster {
        override fun permissionGranted():Boolean {
            if(expireOnSecondRunningPermissionRead&&db.actions().findByKey("notification.post",request.idempotencyKey.toString())?.status==ActionStatus.RUNNING) {
                runningPermissionReads++
                if(runningPermissionReads==2)wall=wall.plusSeconds(61)
            }
            return permitted
        }
        override fun availability():NotificationAvailability {
            beforeAvailability?.invoke()
            return if(!permitted)NotificationAvailability.PERMISSION_DENIED else if(!capable)NotificationAvailability.CHANNEL_DISABLED else NotificationAvailability.AVAILABLE
        }
        override fun post(actionId:UUID,notification:ValidatedPostNotification,permit:OrionNotificationInvocationPermit):NotificationPostResult {
            assertFalse("External invocation must never be inside Room",db.inTransaction())
            val row=db.actions().find(actionId.toString())!!
            assertEquals(ActionStatus.RUNNING,row.status);assertNotNull(row.executionId)
            // Independent SQLite connection proves RUNNING committed before the external boundary.
            val observer=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
            try { assertEquals(ActionStatus.RUNNING,observer.actions().find(actionId.toString())!!.status) }
            finally { observer.close() }
            assertEquals(request.title,notification.title);assertEquals(request.body,notification.body)
            beforeInvocationGate?.invoke()
            if(!permit.allows())return NotificationPostResult.NOT_INVOKED
            invokes++
            postFailure?.let { throw it }
            return postResult
        }
    }
    @Before fun open() {
        db=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
        kernel=OrionNotificationKernel(db,crypto,clock,barrier,owner,{CoreReadiness.DEGRADED_SAFE},{true},poster)
        release()
    }
    @After fun close() { channel.close();db.close();context.deleteDatabase(name) }
    private fun release() { barrier.releaseAfterRecovery(barrier.beginRecovery(),CoreReadiness.DEGRADED_SAFE) }
    private fun initial()=OrionNotificationIngressWire("POST",request.requestId,request.idempotencyKey,request.correlationId,request.title,request.body)
    private fun prepare():OrionNotificationPending {
        assertEquals(NotificationOutcome.QUEUED,kernel.submit(initial(),request.idempotencyKey))
        assertEquals(0,invokes)
        return kernel.pending().single()
    }
    private fun consent(pending:OrionNotificationPending,expiry:Instant=wall.plusSeconds(60)):OrionNotificationIngressWire {
        val p=pending.preview!!
        val normalized=OrionPostNotificationActionStore.normalized(p.actionId,p.notification,p.policyVersion)
        val receipt=ActionConfirmationBinding(clock).recordConfirmation(normalized,expiry)
        return OrionNotificationIngressWire("POST",request.requestId,request.idempotencyKey,request.correlationId,
            actionId=p.actionId,actionVersion=p.version,fingerprint=p.confirmationFingerprint,policyVersion=p.policyVersion,receipt=receipt)
    }
    private fun confirmed():UUID {
        val pending=prepare()
        assertEquals(NotificationOutcome.APPLIED,kernel.submit(consent(pending),UUID.randomUUID()))
        assertEquals(0,invokes)
        assertEquals(ActionStatus.CONFIRMED,db.actions().find(pending.display.actionId.toString())!!.status)
        return pending.display.actionId
    }
    private fun admission()=(channel.captureFromUser(request) as OrionNotificationAdmissionResult.Admitted).request
    private fun count(table:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {it.moveToFirst();it.getInt(0)}
    @Test fun queueConsumerPreparesAndConfirmsButCannotInvokeExternally() {
        val id=confirmed()
        assertEquals(0,invokes);assertEquals(0,count("orion_domain_event_outbox"))
        assertEquals(NotificationOutcome.POSTED,kernel.executeFromUser(admission(),id))
        assertEquals(1,invokes);assertEquals(ActionStatus.SUCCEEDED,db.actions().find(id.toString())!!.status)
        assertEquals(1,count("orion_domain_event_outbox"))
        assertEquals(NotificationOutcome.POSTED,kernel.executeFromUser(admission(),id));assertEquals(1,invokes)
    }
    @Test fun ambiguousPostPersistsUnknownAndNeverRetries() {
        val id=confirmed();postResult=NotificationPostResult.UNKNOWN
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id))
        assertEquals(ActionStatus.UNKNOWN,db.actions().find(id.toString())!!.status)
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id))
        assertEquals(1,invokes);assertEquals(0,count("orion_domain_event_outbox"))
    }
    @Test fun exceptionDuringExternalInvokeIsUnknownWithoutSensitiveExceptionTextOrRetry() {
        val id=confirmed();postFailure=IllegalStateException("sensitive-platform-exception")
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id))
        assertEquals(ActionStatus.UNKNOWN,db.actions().find(id.toString())!!.status)
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id));assertEquals(1,invokes)
    }
    @Test fun permissionRevocationBeforeClaimLeavesConfirmedAndInvokesNothing() {
        val id=confirmed();permitted=false
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id))
        assertEquals(ActionStatus.CONFIRMED,db.actions().find(id.toString())!!.status);assertEquals(0,invokes)
    }
    @Test fun channelDenialAndBarrierGateNeverInvokeExternalApi() {
        val id=confirmed();capable=false
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id));assertEquals(0,invokes)
        capable=true;barrier.beginRecovery()
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id));assertEquals(0,invokes)
    }
    @Test fun permissionLostAfterDurableClaimIsKnownNonInvocationAndEndsFailed() {
        val id=confirmed()
        beforeAvailability={if(db.actions().find(id.toString())?.status==ActionStatus.RUNNING)permitted=false}
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id))
        assertEquals(0,invokes);assertEquals(ActionStatus.FAILED,db.actions().find(id.toString())!!.status)
    }
    @Test fun staleConsentAndMaterialTamperNeverReachThePoster() {
        val p=prepare();val wire=consent(p)
        val tampered=OrionNotificationIngressWire("POST",request.requestId,request.idempotencyKey,request.correlationId,
            actionId=wire.actionId,actionVersion=wire.actionVersion,fingerprint="altered",policyVersion=wire.policyVersion,receipt=wire.receipt)
        assertEquals(NotificationOutcome.REJECTED,kernel.submit(tampered,UUID.randomUUID()))
        wall=wall.plusSeconds(61)
        assertEquals(NotificationOutcome.REJECTED,kernel.submit(wire,UUID.randomUUID()))
        assertEquals(ActionStatus.NEEDS_CONFIRMATION,db.actions().find(p.display.actionId.toString())!!.status)
        assertEquals(0,invokes)
    }
    @Test fun expiryDuringCapabilityObservationBlocksBeforeExternalInvocation() {
        val id=confirmed();val deadline=wall.plusSeconds(61)
        beforeAvailability={if(db.actions().find(id.toString())?.status==ActionStatus.RUNNING)wall=deadline}
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id));assertEquals(0,invokes)
    }
    @Test fun callerInsideRoomTransactionIsRejectedBeforeAnyClaimOrInvoke() {
        val id=confirmed()
        assertThrows(IllegalStateException::class.java) { db.runInTransaction { kernel.executeFromUser(admission(),id) } }
        assertEquals(ActionStatus.CONFIRMED,db.actions().find(id.toString())!!.status);assertEquals(0,invokes)
    }
    @Test fun successEventFailureCannotAuthorizeASecondExternalAttempt() {
        val id=confirmed()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER orion_fail_notification_event BEFORE INSERT ON orion_domain_event_outbox BEGIN SELECT RAISE(ABORT,'orion_injected_failure'); END")
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id))
        assertEquals(ActionStatus.UNKNOWN,db.actions().find(id.toString())!!.status)
        assertEquals(0,count("orion_domain_event_outbox"));assertEquals(1,invokes)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER orion_fail_notification_event")
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id));assertEquals(1,invokes)
    }
    @Test fun fatalDeathBoundaryRetainsRunningAndRecoveryUsesManualReviewWithoutReplay() {
        val id=confirmed();postFailure=AssertionError("synthetic-process-death-boundary")
        assertThrows(AssertionError::class.java) { kernel.executeFromUser(admission(),id) }
        assertEquals(ActionStatus.RUNNING,db.actions().find(id.toString())!!.status);assertEquals(1,invokes)
        boot=8;elapsed=0
        val deny=object:ActionExecutionAuthority {
            override fun startupBarrierOpen()=false
            override fun descriptorFor(skill:String):SkillDescriptor?=null
            override fun currentPolicyVersion()="orion_test"
            override fun policyAllows(request:NormalizedActionRequest)=false
            override fun capabilityAvailable(capability:Capability)=false
            override fun permissionGranted(permission:String)=false
            override fun userPresent()=false
        }
        OrionActionExecutionStore(db,kernel.actions,clock,deny).recoverAbandoned()
        assertEquals(ActionStatus.MANUAL_REVIEW,db.actions().find(id.toString())!!.status)
        assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),id));assertEquals(1,invokes)
    }
    @Test fun confirmedQueueReplayNeverRenewsReceiptOrDispatchesExternalWork() {
        val p=prepare();val receipt=consent(p);val at=wall
        channel.onWindowFocus(false)
        assertEquals(NotificationOutcome.QUEUED,kernel.submit(receipt,UUID.randomUUID()))
        channel.onWindowFocus(true);wall=wall.plusSeconds(5)
        assertEquals(1,kernel.ingress.drain(25))
        val row=db.actions().find(p.display.actionId.toString())!!
        assertEquals(ActionStatus.CONFIRMED,row.status);assertEquals(at.toEpochMilli(),row.confirmedAt);assertEquals(0,invokes)
    }
    @Test fun alteredReplayBodyConflictsWithoutCreatingAnotherActionOrEffect() {
        prepare()
        val changed=OrionNotificationIngressWire("POST",request.requestId,request.idempotencyKey,request.correlationId,request.title,"Changed")
        assertEquals(NotificationOutcome.REJECTED,kernel.submit(changed,request.idempotencyKey))
        assertEquals(1,count("orion_action_requests"));assertEquals(0,invokes)
    }
    @Test fun finalPermissionReadCannotExpireConsentAndStillInvoke() {
        val id=confirmed();expireOnSecondRunningPermissionRead=true
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id))
        assertEquals(2,runningPermissionReads);assertEquals(0,invokes)
        assertEquals(ActionStatus.FAILED,db.actions().find(id.toString())!!.status)
    }
    @Test fun slowPlatformPreparationRevalidatesOriginalConsentAtActualInvocationGate() {
        val id=confirmed();beforeInvocationGate={wall=wall.plusSeconds(61)}
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id))
        assertEquals(0,invokes);assertEquals(ActionStatus.FAILED,db.actions().find(id.toString())!!.status)
    }
    @Test fun slowPlatformPreparationCannotUseAnExpiredExecutionLease() {
        val id=confirmed();beforeInvocationGate={elapsed=31_100}
        assertEquals(NotificationOutcome.BLOCKED,kernel.executeFromUser(admission(),id))
        assertEquals(0,invokes);assertEquals(ActionStatus.RUNNING,db.actions().find(id.toString())!!.status)
    }
}
