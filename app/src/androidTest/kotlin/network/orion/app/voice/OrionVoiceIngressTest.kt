package network.orion.app.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.ipc.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.voice.ipc.OrionVoiceAdmission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionVoiceIngressTest {
    @Test fun partialNeverEnqueuesAndRecoveryProcessesFinalOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val at = Instant.parse("2026-11-21T12:00:00Z")
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
            val ingress = OrionVoiceIngress(db, crypto, WallClock { at }, barrier, { readiness }, { true },
                { 70 }, { 0 }, { proposals++; true })
            val id = UUID.randomUUID()
            val session = UUID.randomUUID()
            val trace = UUID.randomUUID()
            fun utterance(final: Boolean, text: String = "criar tarefa estudar") =
                VoiceUtteranceRequest(IpcSchemaVersion(1), id, session, text, final, "pt-BR", at,
                    OrionVoiceSource.PUSH_TO_TALK, 0, trace)
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, ingress.submit(utterance(false)))
            assertEquals(0, db.ingress().count())
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, ingress.submit(utterance(true)))
            assertEquals(OrionVoiceAdmission.DUPLICATE_REQUEST, ingress.submit(utterance(true)))
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, ingress.submit(utterance(true, "criar tarefa: alterar")))
            val cancelledId = UUID.randomUUID()
            val cancelled = VoiceUtteranceRequest(IpcSchemaVersion(1), cancelledId, session,
                "criar tarefa cancelar", true, "pt-BR", at, OrionVoiceSource.PUSH_TO_TALK, 0, trace)
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, ingress.submit(cancelled))
            assertTrue(ingress.cancel(cancelledId))
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
        } finally { db.close() }
    }
}
