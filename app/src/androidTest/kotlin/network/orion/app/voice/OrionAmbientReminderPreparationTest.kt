package network.orion.app.voice

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.ActionStatus
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.reminders.OrionAmbientReminderDescriptor
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.OrionDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionAmbientReminderPreparationTest {
    @Test fun attributedObservationPreparesOneEncryptedAppointmentAndOneBoundAction() {
        val clock = object : OrionClock {
            override fun wallNow() = Instant.parse("2026-11-27T01:00:00Z")
            override fun monotonicNow() = MonotonicInstant(BootSessionId(7), 100)
        }
        val encryption = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32), "AES"))
            override fun keyForVersion(version: Int) = currentKey()
        })
        val barrier = StartupBarrier().apply {
            releaseAfterRecovery(beginRecovery(), CoreReadiness.READY)
        }
        val commitment = checkNotNull(OrionAmbientCommitment.parse("Putz, tenho uma reunião amanhã às 19h.",
            clock.wallNow(), ZoneId.of("America/Sao_Paulo")))
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrionDatabase::class.java).build()
        try {
            val preparation = OrionAmbientReminderPreparation(db, encryption, clock, barrier)
            val prepared = barrier.whileOpen {
                db.runInTransaction(java.util.concurrent.Callable {
                    val id = preparation.prepare(commitment, 1, UUID.randomUUID())
                    assertEquals(id, preparation.prepare(commitment, 1, UUID.randomUUID()))
                    assertNull(preparation.prepare(commitment, 0, UUID.randomUUID()))
                    assertNull(preparation.prepare(commitment, 2, UUID.randomUUID()))
                    id
                })
            }
            val id = checkNotNull(prepared)
            val row = checkNotNull(db.reminders().find(id.toString()))
            val stored = (OrionActionStore(db, encryption, clock).read(id) as ActionReadResult.Ready).action
            assertEquals(id.toString(), row.deliveryActionId)
            assertEquals(1L, row.fireGeneration)
            assertEquals(commitment.noticeAt.toEpochMilli(), row.scheduledAt)
            assertEquals(OrionAmbientReminderDescriptor.NAME, stored.request.skill)
            assertEquals(ActionStatus.CONFIRMED, stored.status)
            assertFalse(stored.requiresConfirmation)
            assertEquals(commitment.startsAt, stored.confirmation?.confirmationExpiresAt)
            assertFalse(row.contentEnvelope.decodeToString().contains("Compromisso inferido"))
            assertEquals(1, db.reminderSchedules().schedulingPage().size)
        } finally {
            db.close()
        }
    }
}
