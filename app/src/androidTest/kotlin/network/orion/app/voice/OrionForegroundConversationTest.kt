package network.orion.app.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.ipc.IpcSchemaVersion
import network.orion.core.ipc.OrionVoiceSource
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase
import network.orion.voice.ipc.IOrionPushToTalk
import network.orion.voice.ipc.IOrionPushToTalkCallback
import network.orion.voice.ipc.OrionVoiceAdmission
import network.orion.voice.service.OrionVoiceSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionForegroundConversationTest {
    @Test fun startsAnotherTurnAfterTheReplyAndStopsOnSilence() = withVoice {
        main { controller.begin(conversing = true) }
        val first = voice.turns.single()
        emit(first, OrionVoiceSessionState.SPEAKING)
        assertEquals(1, voice.turns.size)

        emit(first, OrionVoiceSessionState.SUCCEEDED)
        val second = voice.turns.last()
        assertEquals(2, voice.turns.size)
        assertNotEquals(first.id, second.id)
        assertTrue(controller.conversation.value)

        emit(second, OrionVoiceSessionState.NO_SPEECH)
        assertFalse(controller.conversation.value)
        assertEquals(2, voice.turns.size)
    }

    @Test fun neverRearmsAfterFocusLossOrAStoppedTurn() = withVoice {
        main { controller.begin(conversing = true) }
        val original = voice.turns.single()
        main { focused = false }
        emit(original, OrionVoiceSessionState.SUCCEEDED)
        assertFalse(controller.conversation.value)
        assertEquals(1, voice.turns.size)

        main {
            focused = true
            controller.begin(conversing = true)
        }
        val stopped = voice.turns.last()
        main { controller.close() }
        emit(stopped, OrionVoiceSessionState.SUCCEEDED)
        assertEquals(2, voice.turns.size)
        assertFalse(controller.conversation.value)
        assertTrue(stopped.id in voice.cancelled)
    }

    @Test fun singleTurnAndReminderReviewDoNotRearm() = withVoice {
        main { controller.begin() }
        emit(voice.turns.single(), OrionVoiceSessionState.SUCCEEDED)
        assertEquals(1, voice.turns.size)

        main { controller.begin(conversing = true) }
        val review = voice.turns.last()
        val reminder = checkNotNull(OrionSpokenReminder.parse("Me lembre de ler em dez minutos."))
        assertTrue(preview.publishReminder(UUID.fromString(review.id), reminder))
        emit(review, OrionVoiceSessionState.SUCCEEDED)
        assertEquals(reminder, controller.reminder.value)
        assertEquals(2, voice.turns.size)
        assertFalse(controller.conversation.value)
    }

    @Test fun finalRequestPublishesAReminderForReviewAfterTheBarrierOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        try {
            val preview = OrionVoiceTranscriptPreview()
            val output = MutableStateFlow<OrionSpokenReminder?>(null)
            val session = UUID.randomUUID()
            preview.watch(session, MutableStateFlow(null), output)
            val barrier = StartupBarrier()
            val ticket = barrier.beginRecovery()
            var readiness = CoreReadiness.RECOVERING
            val time = Instant.parse("2026-11-24T12:00:00Z")
            val key = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 1 }, "AES"))
            val encryption = OrionFieldEncryption(object : FieldKeyProvider {
                override fun currentKey() = key
                override fun keyForVersion(version: Int) = key
            })
            val ingress = OrionVoiceIngress(db, encryption, WallClock { time }, barrier, { readiness }, { true },
                { 50 }, { 0 }, { error("The spoken reminder must only prepare a review.") }, transcriptPreview = preview)
            fun request(id: UUID, final: Boolean) = VoiceUtteranceRequest(IpcSchemaVersion(1), id, session,
                "Me lembre de ler em dez minutos.", final, "pt-BR", time, OrionVoiceSource.PUSH_TO_TALK, 0, id)

            val id = UUID.randomUUID()
            assertEquals(OrionVoiceAdmission.REJECTED_INVALID, ingress.submit(request(id, false)))
            assertEquals(OrionVoiceAdmission.QUEUED_DURING_RECOVERY, ingress.submit(request(id, true)))
            ingress.drain()
            assertNull(output.value)
            readiness = CoreReadiness.DEGRADED_SAFE
            assertTrue(barrier.releaseAfterRecovery(ticket, readiness))
            ingress.drain()
            assertEquals("ler", output.value?.title)
            assertEquals(10L, output.value?.afterMinutes)
            assertNull(db.records().reminder(id.toString()))
            assertNull(db.actions().findByKey("reminder.create", id.toString()))

            preview.stop(session)
            output.value = null
            assertEquals(OrionVoiceAdmission.ACCEPTED, ingress.submit(request(UUID.randomUUID(), true)))
            ingress.drain()
            assertNull(output.value)
        } finally { db.close() }
    }

    private fun withVoice(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.main { fixture.controller.close() } }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val voice = VoiceBinding(instrumentation.targetContext)
        val preview = OrionVoiceTranscriptPreview()
        var focused = true
        val controller = OrionPushToTalkController(voice, preview, { focused }) { _, _, _ -> }

        fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

        fun emit(turn: VoiceBinding.Turn, state: OrionVoiceSessionState) {
            turn.callback.onState(turn.id, state.name, null, false)
            instrumentation.waitForIdleSync()
        }
    }

    private class VoiceBinding(context: Context) : ContextWrapper(context) {
        class Turn(val id: String, val callback: IOrionPushToTalkCallback)
        val turns = mutableListOf<Turn>()
        val cancelled = mutableSetOf<String>()
        private val endpoint = object : IOrionPushToTalk.Stub() {
            override fun begin(protocolMajor: Int, sessionId: String, callback: IOrionPushToTalkCallback) {
                turns += Turn(sessionId, callback)
            }

            override fun finishCapture(protocolMajor: Int, sessionId: String) = Unit

            override fun cancel(protocolMajor: Int, sessionId: String) {
                cancelled += sessionId
            }
        }

        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(checkNotNull(service.component), endpoint)
            return true
        }

        override fun unbindService(connection: ServiceConnection) = Unit
    }
}
