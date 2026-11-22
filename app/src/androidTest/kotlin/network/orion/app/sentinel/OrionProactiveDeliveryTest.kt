package network.orion.app.sentinel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import javax.crypto.spec.SecretKeySpec
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.core.actions.ActionConfirmationBinding
import network.orion.core.actions.ActionStatus
import network.orion.core.actions.ConfirmationBindingResult
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.notifications.OrionProactiveNotificationDescriptor
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.OrionDatabase
import network.orion.skills.notifications.NotificationPostResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionProactiveDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: OrionDatabase
    private lateinit var delivery: OrionProactiveDelivery
    private val barrier = StartupBarrier()
    private var now = Instant.parse("2026-11-21T12:00:00Z")
    private val clock = object : OrionClock {
        override fun wallNow() = now
        override fun monotonicNow() = MonotonicInstant(BootSessionId(7), 100)
    }
    private val encryption = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })
    private var permitted = true
    private var beforeInvoke: () -> Unit = {}
    private var invocations = 0
    private var platformResult = NotificationPostResult.POSTED

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build()
        barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
        val poster = object : OrionNotificationPoster {
            override fun availability(): NotificationAvailability {
                assertFalse(Thread.holdsLock(barrier))
                assertFalse(db.inTransaction())
                return NotificationAvailability.AVAILABLE
            }
            override fun permissionGranted(): Boolean {
                assertFalse(Thread.holdsLock(barrier))
                assertFalse(db.inTransaction())
                return true
            }
            override fun post(actionId: UUID, notification: ValidatedPostNotification,
                permit: OrionNotificationInvocationPermit): NotificationPostResult {
                assertFalse(db.inTransaction())
                assertFalse(Thread.holdsLock(barrier))
                beforeInvoke()
                if (!permit.allows()) return NotificationPostResult.NOT_INVOKED
                assertEquals(ActionStatus.RUNNING, db.actions().find(actionId.toString())?.status)
                assertEquals(OrionProactiveNotificationDescriptor.BODY, notification.body)
                assertEquals(actionId, notification.requestId)
                invocations++
                return platformResult
            }
        }
        delivery = OrionProactiveDelivery(db, encryption, clock, barrier, poster)
    }

    @After fun close() = db.close()

    @Test fun exactPolicyBindingCommitsOnePostAndOneFact() {
        val id = UUID.randomUUID()
        assertTrue(prepare(id))
        val action = (OrionActionStore(db, encryption, clock).read(id) as ActionReadResult.Ready).action
        assertFalse(action.requiresConfirmation)
        assertEquals(ActionStatus.CONFIRMED, action.status)
        assertEquals(ConfirmationBindingResult.VALID_BINDING,
            ActionConfirmationBinding(clock).validate(action.request, checkNotNull(action.confirmation)))
        var shown = 0
        val recordShown = {
            assertTrue(db.inTransaction())
            assertEquals(ActionStatus.SUCCEEDED, db.actions().find(id.toString())?.status)
            assertEquals(1, db.events().count())
            shown++
            Unit
        }
        val matchingConsent: (Long) -> Boolean = { revision ->
            assertFalse(Thread.holdsLock(barrier))
            assertFalse(db.inTransaction())
            permitted && revision == 7L
        }
        assertEquals(OrionProactiveDeliveryOutcome.POSTED, delivery.deliver(id, matchingConsent, recordShown))
        assertEquals(OrionProactiveDeliveryOutcome.POSTED, delivery.deliver(id, { permitted }, recordShown))
        assertEquals(1, invocations)
        assertEquals(1, shown)
        assertEquals(1, db.events().count())
    }

    @Test fun missingOrReplacedConsentCannotAuthorizeAnEffect() {
        val id = UUID.randomUUID()
        assertFalse(barrier.whileOpen {
            db.runInTransaction(Callable { delivery.prepare(id, null) })
        } == true)
        assertTrue(prepare(id))
        assertEquals(OrionProactiveDeliveryOutcome.BLOCKED, delivery.deliver(id, { it == 8L }) {})
        assertTrue(delivery.discard(id))
        assertEquals(ActionStatus.CANCELLED, db.actions().find(id.toString())?.status)
        assertEquals(0, invocations)
    }

    @Test fun optOutImmediatelyBeforePostingLeavesNoNotification() {
        val id = UUID.randomUUID()
        assertTrue(prepare(id))
        beforeInvoke = { permitted = false }
        assertEquals(OrionProactiveDeliveryOutcome.BLOCKED, delivery.deliver(id, { permitted }) {
            error("Blocked delivery cannot record shown")
        })
        assertEquals(0, invocations)
        assertEquals(ActionStatus.FAILED, db.actions().find(id.toString())?.status)
        permitted = true
        assertEquals(OrionProactiveDeliveryOutcome.BLOCKED, delivery.deliver(id, { permitted }) {})
        assertEquals(0, invocations)
    }

    @Test fun ambiguousEffectIsNotRetriedAndCannotPublishShown() {
        val id = UUID.randomUUID()
        assertTrue(prepare(id))
        platformResult = NotificationPostResult.UNKNOWN
        repeat(2) {
            assertEquals(OrionProactiveDeliveryOutcome.UNKNOWN, delivery.deliver(id, { permitted }) {
                error("Unknown delivery cannot record shown")
            })
        }
        assertEquals(1, invocations)
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(id.toString())?.status)
        assertEquals(0, db.events().count())
    }

    @Test fun failureToRecordShownRollsBackSuccessAndFinishesUnknown() {
        val id = UUID.randomUUID()
        assertTrue(prepare(id))
        assertEquals(OrionProactiveDeliveryOutcome.UNKNOWN, delivery.deliver(id, { permitted }) {
            error("Synthetic feedback persistence failure")
        })
        assertEquals(1, invocations)
        assertEquals(0, db.events().count())
        assertEquals(ActionStatus.UNKNOWN, db.actions().find(id.toString())?.status)
    }

    @Test fun expiredUnclaimedDraftIsCancelledWithoutPosting() {
        val id = UUID.randomUUID()
        assertTrue(prepare(id))
        now = now.plusSeconds(301)
        val recovery = barrier.beginRecovery()
        assertEquals(0, delivery.discardExpired())
        assertEquals(ActionStatus.CONFIRMED, db.actions().find(id.toString())?.status)
        barrier.releaseAfterRecovery(recovery, CoreReadiness.DEGRADED_SAFE)
        assertEquals(1, delivery.discardExpired())
        assertEquals(0, delivery.discardExpired())
        assertEquals(OrionProactiveDeliveryOutcome.BLOCKED, delivery.deliver(id, { permitted }) {})
        assertEquals(ActionStatus.CANCELLED, db.actions().find(id.toString())?.status)
        assertEquals(0, invocations)
    }

    private fun prepare(id: UUID) = barrier.whileOpen {
        db.runInTransaction(Callable { delivery.prepare(id, 7) })
    } == true
}
