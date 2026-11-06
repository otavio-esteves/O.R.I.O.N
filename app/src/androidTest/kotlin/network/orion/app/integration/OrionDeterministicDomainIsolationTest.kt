package network.orion.app.integration

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import network.orion.app.tasks.*
import network.orion.app.reminders.*
import network.orion.app.timers.*
import network.orion.app.memories.*
import network.orion.app.notifications.*
import network.orion.core.actions.*
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.orchestrator.tasks.TaskOperationOutcome
import network.orion.core.orchestrator.reminders.ReminderOperationOutcome
import network.orion.core.orchestrator.timers.TimerOperationOutcome
import network.orion.core.orchestrator.memories.MemoryOperationOutcome
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.notifications.NotificationOutcome
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.policy.reminders.OrionPolicyEvidence as ReminderEvidence
import network.orion.core.queries.activity.GetActivity
import network.orion.core.queries.tasks.GetTasks
import network.orion.core.queries.reminders.GetReminders
import network.orion.core.queries.timers.GetTimers
import network.orion.core.queries.timers.GetTimerStatus
import network.orion.core.queries.memories.GetMemories
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.*
import network.orion.data.events.OrionActivityQuery
import network.orion.skills.notifications.NotificationPostResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real shared Core kernels; synthetic focused owners, keys and a never-invoked platform boundary. */
@RunWith(AndroidJUnit4::class)
class OrionDeterministicDomainIsolationTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val name="orion_domain_isolation_${UUID.randomUUID()}.db"
    // Deliberately identical target UUID, producer key and command key in every domain.
    private val shared=UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val at=Instant.parse("2026-11-05T12:00:00Z")
    private val clock=object:OrionClock {
        override fun wallNow()=at
        override fun monotonicNow()=MonotonicInstant(BootSessionId(7),1000)
    }
    private val encryption=OrionFieldEncryption(object:FieldKeyProvider {
        override fun currentKey()=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 63 },"AES"))
        override fun keyForVersion(version:Int)=currentKey()
    })
    private val barrier=StartupBarrier()
    private val yes={ OrionPolicyEvidence.SATISFIED }
    private val taskOwner=OrionTaskAdmissionOwner(OrionProcessRole.MAIN,barrier,yes,yes)
    private val reminderOwner=OrionReminderAdmissionOwner(OrionProcessRole.MAIN,barrier,
        { ReminderEvidence.SATISFIED },{ ReminderEvidence.SATISFIED })
    private val timerOwner=OrionTimerAdmissionOwner(OrionProcessRole.MAIN,barrier,yes,yes)
    private val memoryOwner=OrionMemoryAdmissionOwner(OrionProcessRole.MAIN,barrier,yes,yes)
    private val notificationOwner=OrionNotificationAdmissionOwner(OrionProcessRole.MAIN,barrier,yes,yes,yes)
    private val taskChannel=taskOwner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val reminderChannel=reminderOwner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val timerChannel=timerOwner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val memoryChannel=memoryOwner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val notificationChannel=notificationOwner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
    private val posts=AtomicInteger()
    private val poster=object:OrionNotificationPoster {
        override fun availability()=NotificationAvailability.AVAILABLE
        override fun permissionGranted()=true
        override fun post(actionId:UUID,notification:ValidatedPostNotification,permit:OrionNotificationInvocationPermit):NotificationPostResult {
            posts.incrementAndGet()
            throw AssertionError("A durable domain queue must never invoke the external poster.")
        }
    }
    private lateinit var db:OrionDatabase
    private lateinit var tasks:OrionTaskKernel
    private lateinit var reminders:OrionReminderKernel
    private lateinit var timers:OrionTimerKernel
    private lateinit var memories:OrionMemoryKernel
    private lateinit var notifications:OrionNotificationKernel
    private val scopes=listOf(OrionTaskIngressWire.SCOPE,OrionReminderIngressWire.SCOPE,OrionTimerIngressWire.SCOPE,
        OrionMemoryIngressWire.SCOPE,OrionNotificationIngressWire.SCOPE)
    private fun taskWire()=OrionTaskIngressWire("CREATE",shared,shared,shared,"Tarefa do domínio")
    private fun reminderWire()=OrionReminderIngressWire("CREATE",shared,shared,shared,"Lembrete do domínio",
        scheduledAtMillis=at.plusSeconds(600).toEpochMilli(),zoneId="UTC")
    private fun timerWire()=OrionTimerIngressWire("CREATE",shared,shared,shared,"Timer do domínio",durationMillis=60_000)
    private fun memoryWire()=OrionMemoryIngressWire("CREATE",shared,shared,shared,"Informação pessoal do domínio")
    private fun notificationWire()=OrionNotificationIngressWire("POST",shared,shared,shared,"Notificação do domínio","Mensagem sintética")

    @Before fun open() {
        db=Room.databaseBuilder(context,OrionDatabase::class.java,name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val readiness={ CoreReadiness.DEGRADED_SAFE };val storage={ true }
        tasks=OrionTaskKernel(db,encryption,clock,barrier,taskOwner,readiness,storage)
        reminders=OrionReminderKernel(db,encryption,clock,barrier,reminderOwner,readiness,storage)
        timers=OrionTimerKernel(db,encryption,clock,barrier,timerOwner,readiness,storage)
        memories=OrionMemoryKernel(db,encryption,clock,barrier,memoryOwner,readiness,storage)
        notifications=OrionNotificationKernel(db,encryption,clock,barrier,notificationOwner,readiness,storage,poster)
    }
    @After fun close() {
        taskChannel.close();reminderChannel.close();timerChannel.close();memoryChannel.close();notificationChannel.close()
        db.close();context.deleteDatabase(name)
    }
    private fun enqueueInterleaved() {
        assertEquals(TimerOperationOutcome.QUEUED,timers.submit(timerWire(),shared))
        assertEquals(MemoryOperationOutcome.QUEUED,memories.submit(memoryWire(),shared))
        assertEquals(NotificationOutcome.QUEUED,notifications.submit(notificationWire(),shared))
        assertEquals(ReminderOperationOutcome.QUEUED,reminders.submit(reminderWire(),shared))
        assertEquals(TaskOperationOutcome.QUEUED,tasks.submit(taskWire(),shared))
    }
    private fun release() {
        assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(),CoreReadiness.DEGRADED_SAFE))
    }
    private fun queue(scope:String)=checkNotNull(db.ingress().findByKey(scope,shared.toString()))
    private fun assertQueuedExcept(consumed:Set<String> = emptySet()) {
        for (scope in scopes.filterNot { it in consumed }) {
            val row=queue(scope)
            assertEquals(scope,DurableIngressStatus.QUEUED,row.status)
            assertEquals(scope,0,row.attemptCount);assertEquals(scope,0L,row.version)
        }
    }
    private fun count(table:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { assertTrue(it.moveToFirst());it.getInt(0) }
    private fun assertNoEffects() {
        for (table in listOf("orion_tasks","orion_reminders","orion_timers","orion_memories","orion_domain_event_outbox")) assertEquals(table,0,count(table))
        assertEquals(0,posts.get())
    }
    private fun consent(actions:OrionActionStore,id:UUID):ActionConfirmationSnapshot {
        val request=(actions.read(id) as ActionReadResult.Ready).action.request
        return ActionConfirmationBinding(clock).recordConfirmation(request,at.plusSeconds(60))
    }

    @Test fun gatedInterleavedDomainsWithSameIdsAndKeysRemainIndependentDuringScopedPreparation() {
        enqueueInterleaved()
        assertEquals(5,db.ingress().count());assertQueuedExcept();assertNoEffects()
        assertEquals(0,count("orion_action_requests"))
        val foreign=scopes.filter { it!=OrionTaskIngressWire.SCOPE }.associateWith { queue(it).payloadEnvelope.copyOf() }
        release()
        assertEquals(1,tasks.ingress.drain(25))
        assertEquals(1,count("orion_action_requests"));assertEquals(1,tasks.pending().size)
        assertQueuedExcept(setOf(OrionTaskIngressWire.SCOPE));assertNoEffects()
        for ((scope,bytes) in foreign) assertArrayEquals(bytes,queue(scope).payloadEnvelope)
        assertEquals(0,tasks.ingress.drain(25))
        assertEquals(1,notifications.ingress.drain(25));assertEquals(1,reminders.ingress.drain(25))
        assertEquals(1,memories.ingress.drain(25));assertEquals(1,timers.ingress.drain(25))
        assertEquals(5,count("orion_action_requests"));assertNoEffects()
        val actionIds=listOf("task.create","reminder.create","timer.create","memory.create","notification.post").map { scope ->
            val row=checkNotNull(db.actions().findByKey(scope,shared.toString()))
            assertEquals(ActionStatus.NEEDS_CONFIRMATION,row.status);assertNull(row.confirmedAt);assertEquals(1L,row.version)
            row.actionId
        }
        assertEquals(5,actionIds.toSet().size)
        // Replaying the same producer key is independent in each scope, not a cross-domain conflict.
        enqueueInterleaved()
        assertEquals(5,db.ingress().count());assertEquals(5,count("orion_action_requests"));assertNoEffects()
    }

    @Test fun eachLocalEffectNeedsItsOwnExactConsentAndNotificationQueueNeverInvokesExternally() {
        enqueueInterleaved();release();assertEquals(1,tasks.ingress.drain(25))
        val task=tasks.pending().single()
        assertEquals(TaskOperationOutcome.APPLIED,tasks.submit(task.wire(consent(tasks.actions,task.display.actionId)),UUID.randomUUID()))
        assertEquals(1,count("orion_tasks"));assertEquals(0,count("orion_reminders"));assertEquals(0,count("orion_timers"));assertEquals(0,count("orion_memories"))
        assertQueuedExcept(setOf(OrionTaskIngressWire.SCOPE));assertEquals(0,posts.get())
        assertEquals(1,reminders.ingress.drain(25));assertEquals(1,timers.ingress.drain(25))
        assertEquals(1,memories.ingress.drain(25));assertEquals(1,notifications.ingress.drain(25))
        assertEquals(1,count("orion_domain_event_outbox"))
        val reminder=reminders.pending().single()
        assertEquals(ReminderOperationOutcome.APPLIED,reminders.submit(reminder.wire(consent(reminders.actions,reminder.display.actionId)),UUID.randomUUID()))
        assertEquals(1,count("orion_reminders"));assertEquals(ReminderPrecision.UNSCHEDULED,db.records().reminder(shared.toString())!!.precision)
        assertEquals(0,count("orion_timers"));assertEquals(0,count("orion_memories"))
        val timer=timers.pending().single()
        assertEquals(TimerOperationOutcome.APPLIED,timers.submit(timer.wire(consent(timers.actions,timer.display.actionId)),UUID.randomUUID()))
        assertEquals(1,count("orion_timers"));assertEquals(0,count("orion_memories"))
        val memory=memories.pending().single()
        assertEquals(MemoryOperationOutcome.APPLIED,memories.submit(memory.wire(consent(memories.actions,memory.display.actionId)),UUID.randomUUID()))
        val persisted=db.records().memory(shared.toString())!!
        assertEquals(MemoryOrigin.USER_DECLARED,persisted.origin);assertTrue(persisted.userConfirmed)
        val notification=notifications.pending().single()
        val preview=checkNotNull(notification.preview)
        val wire=OrionNotificationIngressWire("POST",shared,shared,shared,actionId=preview.actionId,
            actionVersion=preview.version,fingerprint=preview.confirmationFingerprint,policyVersion=preview.policyVersion,
            receipt=consent(notifications.actions,preview.actionId))
        assertEquals(NotificationOutcome.APPLIED,notifications.submit(wire,UUID.randomUUID()))
        assertEquals(ActionStatus.CONFIRMED,db.actions().find(preview.actionId.toString())!!.status)
        assertEquals(0,notifications.ingress.drain(25));assertEquals(0,posts.get())
        assertEquals(4,count("orion_domain_event_outbox"))
        for (scope in listOf("task.create","reminder.create","timer.create","memory.create"))
            assertEquals(ActionStatus.SUCCEEDED,db.actions().findByKey(scope,shared.toString())!!.status)
    }

    @Test fun safeQueriesCannotDrainOrChangeAnyDomainEvenAfterSharedBarrierRelease() {
        enqueueInterleaved()
        val before=state()
        fun read() {
            assertTrue(tasks.query.get(GetTasks()).items.isEmpty())
            assertTrue(reminders.query.get(GetReminders()).items.isEmpty())
            assertTrue(timers.query.get(GetTimers()).items.isEmpty());assertNull(timers.query.get(GetTimerStatus(shared)))
            assertTrue(memories.query.get(GetMemories()).items.isEmpty())
            assertTrue(OrionActivityQuery(db).get(GetActivity()).items.isEmpty())
            assertTrue(notifications.pending().isEmpty())
        }
        read();assertEquals(before,state());release();read();assertEquals(before,state())
        assertQueuedExcept();assertNoEffects();assertEquals(0,count("orion_action_requests"))
    }
    private fun state():List<String> {
        val state=scopes.map { scope ->
            val row=queue(scope)
            val digest=MessageDigest.getInstance("SHA-256").digest(row.payloadEnvelope).joinToString("") { "%02x".format(it) }
            "$scope/${row.status}/${row.version}/${row.attemptCount}/$digest"
        }.toMutableList()
        for (table in listOf("orion_action_requests","orion_action_audit","orion_domain_event_outbox","orion_tasks","orion_reminders","orion_timers","orion_memories"))
            state += "$table/${count(table)}"
        return state
    }
}
