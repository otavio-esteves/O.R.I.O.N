package network.orion.app.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.tasks.OrionCreateTaskActionStore
import network.orion.core.actions.IdempotencyKeyPolicy
import network.orion.core.commands.tasks.OrionCreateTaskPreparer
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.ipc.*
import network.orion.core.model.Sensitivity
import network.orion.core.policy.tasks.OrionCreateTaskPolicy
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.ActionStoreOutcome
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.DurableIngressStatus
import network.orion.data.database.OrionDatabase
import network.orion.voice.ipc.OrionVoiceAdmission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionVoiceIngressTest {
    @Test fun liveCognitiveRequestStaysInMemoryAndCancellationPreventsAReply() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val at = Instant.parse("2026-11-21T12:00:00Z")
        val clock = WallClock { at }
        val barrier = StartupBarrier()
        val ready = CoreReadiness.DEGRADED_SAFE
        check(barrier.releaseAfterRecovery(barrier.beginRecovery(), ready))
        val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        val crypto = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = key
            override fun keyForVersion(version: Int) = key
        })
        val db = Room.inMemoryDatabaseBuilder(app, OrionDatabase::class.java).build()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<String>()
        val id = UUID.randomUUID()
        val trace = UUID.randomUUID()
        val elapsed = TestTimeSource()
        val ingress = OrionVoiceIngress(db, crypto, clock, barrier, { ready }, { true }, { 50 }, { 0 },
            { error("A question must not prepare a task.") }, conversationScope = this,
            answerQuestion = { _, correlation ->
                check(correlation == trace)
                check(db.ingress().count() == 0)
                started.complete(Unit)
                finish.await()
            }, timeSource = elapsed)
        try {
            val request = VoiceUtteranceRequest(IpcSchemaVersion(1), id, UUID.randomUUID(), "Tudo bem?", true,
                "pt-BR", at, OrionVoiceSource.PUSH_TO_TALK, 0, trace)
            assertEquals(OrionVoiceAdmission.ACCEPTED, ingress.submit(request))
            assertFalse(started.isCompleted)
            ingress.drain()
            withTimeout(5_000) { started.await() }
            elapsed += 16.seconds
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, ingress.submit(request))
            assertEquals(OrionVoiceResponseKind.STATUS, ingress.responses(id).single().kind)
            assertTrue(ingress.cancel(id))
            finish.complete("Resposta tardia")
            assertEquals(OrionVoiceTerminalState.CANCELLED, ingress.responses(id).single().terminalState)
        } finally {
            ingress.closeConversation()
            db.close()
        }
    }

    @Test fun readOnlyVoiceWorkWaitsInMemoryAndExpiresWithoutReplayingAfterRecovery() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val at = Instant.parse("2026-11-21T12:00:00Z")
        val elapsed = TestTimeSource()
        val barrier = StartupBarrier()
        val ticket = barrier.beginRecovery()
        var readiness = CoreReadiness.RECOVERING
        var queries = 0
        val crypto = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = error("An ephemeral read must not request a key.")
            override fun keyForVersion(version: Int) = error("An ephemeral read must not decrypt anything.")
        })
        val db = Room.inMemoryDatabaseBuilder(app, OrionDatabase::class.java).build()
        try {
            fun ingress() = OrionVoiceIngress(db, crypto, WallClock { at }, barrier, { readiness }, { true },
                { ++queries; 50 }, { 2 }, { error("A query must not prepare an action.") }, timeSource = elapsed)
            val live = ingress()
            fun request(text: String = "bateria", id: UUID = UUID.randomUUID()) = VoiceUtteranceRequest(
                IpcSchemaVersion(1), id, UUID.randomUUID(), text, true, "pt-BR", at,
                OrionVoiceSource.PUSH_TO_TALK, 0, UUID.randomUUID(),
            )
            val expired = request()
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, live.submit(expired))
            live.drain()
            assertEquals(0, queries)
            assertEquals(OrionVoiceResponseKind.STATUS, live.responses(expired.requestId).single().kind)
            elapsed += 91.seconds
            assertEquals(OrionVoiceTerminalState.TIMED_OUT, live.responses(expired.requestId).single().terminalState)

            val accepted = request()
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, live.submit(accepted))
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, live.submit(accepted))
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, live.submit(request("listar tarefas", accepted.requestId)))
            readiness = CoreReadiness.DEGRADED_SAFE
            assertTrue(barrier.releaseAfterRecovery(ticket, readiness))
            repeat(2) { live.drain() }
            assertEquals(1, queries)
            assertEquals("Bateria em 50 por cento.", live.responses(accepted.requestId).first().textDelta)
            assertEquals(OrionVoiceTerminalState.SUCCEEDED, live.responses(accepted.requestId).last().terminalState)
            assertEquals(0, db.ingress().count())
            assertTrue(ingress().responses(accepted.requestId).isEmpty())

            repeat(62) { assertEquals(OrionVoiceAdmission.ACCEPTED, live.submit(request())) }
            assertEquals(OrionVoiceAdmission.CORE_UNAVAILABLE, live.submit(request()))
            assertEquals(0, db.ingress().count())
            elapsed += 221.seconds
            assertEquals(OrionVoiceAdmission.ACCEPTED, live.submit(request()))
        } finally { db.close() }
    }

    @Test fun partialNeverEnqueuesAndRecoveryProcessesFinalOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var at = Instant.parse("2026-11-21T12:00:00Z")
        val clock = WallClock { at }
        val barrier = StartupBarrier()
        val ticket = barrier.beginRecovery()
        var readiness = CoreReadiness.RECOVERING
        var proposals = 0
        val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        val crypto = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = key
            override fun keyForVersion(version: Int) = key
        })
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        try {
            val actions = OrionActionStore(db, crypto, clock)
            val policy = IdempotencyKeyPolicy("MAIN_UI", "task.create", java.time.Duration.ofDays(90), 1,
                OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
            fun createIngress() = OrionVoiceIngress(db, crypto, clock, barrier, { readiness }, { true },
                { 70 }, { 0 }, { task ->
                    proposals++
                    val action = OrionCreateTaskActionStore.normalized(UUID.randomUUID(), task, OrionCreateTaskPolicy.POLICY_VERSION)
                    val registered = actions.register(action, policy, task.idempotencyKey.toString(), task.correlationId,
                        Sensitivity.SENSITIVE, true)
                    check(registered.outcome == ActionStoreOutcome.REGISTERED)
                    actions.requireConfirmation(action.actionId, 0).outcome == ActionStoreOutcome.APPLIED
                })
            val ingress = createIngress()
            val id = UUID.randomUUID()
            val session = UUID.randomUUID()
            val trace = UUID.randomUUID()
            fun utterance(final: Boolean, text: String = "criar tarefa estudar") =
                VoiceUtteranceRequest(IpcSchemaVersion(1), id, session, text, final, "pt-BR", at,
                    OrionVoiceSource.PUSH_TO_TALK, 0, trace)
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, ingress.submit(utterance(false)))
            assertEquals(0, db.ingress().count())
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, ingress.submit(utterance(true)))
            val encrypted = checkNotNull(db.ingress().find(id.toString())).payloadEnvelope
            assertTrue(encrypted.isNotEmpty())
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, ingress.submit(utterance(true)))
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, ingress.submit(utterance(true, "criar tarefa: alterar")))
            val cancelledId = UUID.randomUUID()
            val cancelled = VoiceUtteranceRequest(IpcSchemaVersion(1), cancelledId, session,
                "criar tarefa cancelar", true, "pt-BR", at, OrionVoiceSource.PUSH_TO_TALK, 0, trace)
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, ingress.submit(cancelled))
            assertTrue(ingress.cancel(cancelledId))
            assertTrue(checkNotNull(db.ingress().find(cancelledId.toString())).payloadEnvelope.isEmpty())
            assertEquals(OrionVoiceTerminalState.CANCELLED, ingress.responses(cancelledId).single().terminalState)
            ingress.drain()
            assertEquals(0, proposals)
            assertEquals(OrionVoiceResponseKind.STATUS, ingress.responses(id).single().kind)
            readiness = CoreReadiness.DEGRADED_SAFE
            assertTrue(barrier.releaseAfterRecovery(ticket, readiness))
            repeat(2) { ingress.drain() }
            assertEquals(1, proposals)
            val responses = ingress.responses(id)
            assertEquals(listOf(1L, 2L), responses.map { it.sequence })
            assertTrue(responses.all { it.requiresConfirmation })
            assertEquals(OrionVoiceTerminalState.SUCCEEDED, responses.last().terminalState)
            assertFalse(ingress.cancel(id))
            assertTrue(checkNotNull(db.ingress().find(id.toString())).payloadEnvelope.isEmpty())

            val restarted = createIngress()
            val recovered = restarted.responses(id)
            assertEquals(responses.first().textDelta, recovered.first().textDelta)
            assertEquals(listOf(1L, 2L), recovered.map { it.sequence })
            assertTrue(recovered.all { it.requiresConfirmation })
            assertEquals(OrionVoiceTerminalState.SUCCEEDED, recovered.last().terminalState)
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, restarted.submit(utterance(true)))
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, restarted.submit(utterance(true, "criar tarefa: alterar")))
            restarted.drain()
            assertEquals(1, proposals)

            // Simulate a terminal voice row written before this change. Retention clears only its content.
            db.openHelper.writableDatabase.execSQL("UPDATE orion_ingress_envelopes SET payloadEnvelope = ? WHERE ingressId = ?",
                arrayOf(encrypted, id.toString()))
            val epoch = at.toEpochMilli()
            val pruned = db.retention().prune(epoch, 0, 0, 0, 0, 0, 100, 1)
            assertEquals(1, pruned.voicePayloadsErased)
            assertTrue(pruned.needsContinuation(1))
            assertTrue(checkNotNull(db.ingress().find(id.toString())).payloadEnvelope.isEmpty())
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, restarted.submit(utterance(true)))

            fun submitText(text: String): UUID {
                val requestId = UUID.randomUUID()
                assertEquals(OrionVoiceAdmission.ACCEPTED, restarted.submit(VoiceUtteranceRequest(
                    IpcSchemaVersion(1), requestId, session, text, true, "pt-BR", at,
                    OrionVoiceSource.PUSH_TO_TALK, 0, trace)))
                return requestId
            }
            val rejectedId = UUID.randomUUID()
            val tooLong = "criar tarefa: " + "x".repeat(OrionCreateTaskPreparer.MAX_TITLE_UTF8_BYTES + 1)
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, restarted.submit(VoiceUtteranceRequest(
                IpcSchemaVersion(1), rejectedId, session, tooLong, true, "pt-BR", at,
                OrionVoiceSource.PUSH_TO_TALK, 0, trace)))
            assertNull(db.ingress().find(rejectedId.toString()))
            val expiredId = submitText("criar tarefa expirar")
            at = at.plusSeconds(91)
            restarted.drain()
            assertEquals(OrionVoiceTerminalState.TIMED_OUT, restarted.responses(expiredId).single().terminalState)
            assertTrue(checkNotNull(db.ingress().find(expiredId.toString())).payloadEnvelope.isEmpty())
            val queryId = submitText("bateria")
            restarted.drain()
            assertTrue(createIngress().responses(queryId).isEmpty())
            assertEquals(1, proposals)
        } finally { db.close() }
    }
}
