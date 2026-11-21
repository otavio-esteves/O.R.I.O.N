package network.orion.app.sentinel

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.app.resources.OrionAndroidResourceProfileSnapshot
import network.orion.core.actions.ActionExecutionAuthority
import network.orion.core.actions.ActionStatus
import network.orion.core.actions.NormalizedActionRequest
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.health.CoreReadiness
import network.orion.core.health.OrionHealthStatus
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.resources.OrionMemoryPressure
import network.orion.core.resources.OrionResourceProfiles
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.skills.metadata.notifications.OrionProactiveNotificationDescriptor
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.core.time.WallClock
import network.orion.data.actions.OrionActionExecutionStore
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.OrionDatabase
import network.orion.data.tasks.OrionTaskStore
import network.orion.data.tasks.TaskCreationResult
import network.orion.skills.notifications.NotificationPostResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The synthetic marker records invocation independently of Room. It never posts an Android notification. */
@RunWith(AndroidJUnit4::class)
class OrionSentinelProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun committedSentinelClaimRecoversWithoutReposting() {
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("orion_transaction_mode") ?: "simulate"
        require(mode in setOf("simulate", "prepare", "verify"))
        val fixture = arguments.getString("orion_transaction_fixture")?.let { raw ->
            UUID.fromString(raw).also { require(it.toString() == raw) }
        } ?: run {
            require(mode == "simulate")
            UUID.randomUUID()
        }
        val databaseName = "orion_sentinel_death_$fixture.db"
        val checkpoint = File(context.filesDir, "orion_transaction_$fixture.ready")
        val marker = File(context.filesDir, "orion_sentinel_$fixture.marker")
        if (mode == "verify") {
            require(context.getDatabasePath(databaseName).isFile && checkpoint.isFile && marker.isFile)
        } else {
            require(!context.getDatabasePath(databaseName).exists() && !checkpoint.exists() && !marker.exists())
        }

        val now = Instant.parse("2026-11-21T12:00:00Z")
        var elapsed = 100L
        val clock = object : OrionClock {
            override fun wallNow() = now
            override fun monotonicNow() = MonotonicInstant(BootSessionId(7), elapsed)
        }
        val encryption = OrionFieldEncryption(object : FieldKeyProvider {
            override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 47 }, "AES"))
            override fun keyForVersion(version: Int) =
                if (version == 1) currentKey() else FieldKeyResult.Unavailable
        })
        val db = Room.databaseBuilder(context, OrionDatabase::class.java, databaseName).build()
        val barrier = StartupBarrier()
        val taskId = UUID.nameUUIDFromBytes("$fixture/task".toByteArray(Charsets.US_ASCII))
        var invocations = 0
        val poster = object : OrionNotificationPoster {
            override fun availability() = NotificationAvailability.AVAILABLE
            override fun permissionGranted() = true

            override fun post(
                actionId: UUID,
                notification: ValidatedPostNotification,
                permit: OrionNotificationInvocationPermit,
            ): NotificationPostResult {
                assertFalse(db.inTransaction())
                assertFalse(Thread.holdsLock(barrier))
                assertTrue(permit.allows())
                Room.databaseBuilder(context, OrionDatabase::class.java, databaseName).build().use { observer ->
                    assertEquals(ActionStatus.RUNNING, observer.actions().find(actionId.toString())?.status)
                    assertEquals(taskId.toString(), observer.sentinel().suggestion(actionId.toString())?.taskId)
                }
                invocations++
                val count = if (marker.isFile) marker.readText().trim().toInt() else 0
                durableWrite(marker, (count + 1).toString())
                if (mode == "prepare") {
                    durableWrite(checkpoint, Process.myPid().toString())
                    CountDownLatch(1).await(45, TimeUnit.SECONDS)
                    error("The host did not terminate the checkpoint process.")
                }
                throw SimulatedProcessDeath()
            }
        }
        val delivery = OrionProactiveDelivery(db, encryption, clock, barrier, poster)
        val runtime = OrionSentinelRuntime(
            context = context,
            db = db,
            encryption = encryption,
            clock = clock,
            barrier = barrier,
            readiness = { if (barrier.isOpen()) CoreReadiness.DEGRADED_SAFE else CoreReadiness.RECOVERING },
            storageAvailable = { true },
            health = { OrionHealthStatus.DEGRADED },
            zone = { ZoneOffset.UTC },
            observeResources = ::normalResources,
            delivery = delivery,
        )

        try {
            if (mode != "verify") {
                assertTrue(barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE))
                val taskStore = OrionTaskStore(db, encryption, WallClock { now.minus(Duration.ofDays(2)) })
                assertEquals(
                    TaskCreationResult.Created(taskId),
                    taskStore.create(taskId, "Synthetic open task".toByteArray(), Sensitivity.PERSONAL, fixture),
                )
                assertTrue(runtime.setEnabled(true))
                try {
                    runtime.maintain(evaluate = true, lastInteraction = null)
                    error("The synthetic external boundary was not reached.")
                } catch (_: SimulatedProcessDeath) {
                    require(mode == "simulate")
                }
            }

            val persisted = db.actions().runningActions(10, "").single()
            val actionId = UUID.fromString(persisted.actionId)
            assertEquals(OrionProactiveNotificationDescriptor.value.name, persisted.skill)
            assertEquals(ActionStatus.RUNNING, persisted.status)
            assertEquals("1", marker.readText().trim())
            assertNotNull(db.sentinel().suggestion(persisted.actionId))
            assertTrue(db.sentinel().feedback(persisted.actionId).isEmpty())
            assertEquals(2, db.events().count())

            val ticket = barrier.beginRecovery()
            elapsed = 31_100
            val recovery = OrionActionExecutionStore(db, OrionActionStore(db, encryption, clock), clock, denyExecution)
            assertEquals(1, recovery.recoverAbandoned().recovered)
            assertEquals(ActionStatus.UNKNOWN, db.actions().find(persisted.actionId)?.status)
            assertTrue(barrier.releaseAfterRecovery(ticket, CoreReadiness.DEGRADED_SAFE))
            assertEquals(OrionProactiveDeliveryOutcome.UNKNOWN, delivery.deliver(actionId, { true }) {
                error("Uncertain delivery cannot record shown.")
            })
            repeat(3) { runtime.maintain(evaluate = true, lastInteraction = null) }
            assertEquals("1", marker.readText().trim())
            assertEquals(if (mode == "verify") 0 else 1, invocations)
            assertEquals(ActionStatus.UNKNOWN, db.actions().find(persisted.actionId)?.status)
            assertTrue(db.sentinel().feedback(persisted.actionId).isEmpty())
            assertEquals(2, db.events().count())
            if (mode == "verify") {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("orion_transaction_verified_pid", Process.myPid().toString())
                })
            }
        } finally {
            db.close()
            if (mode != "prepare") {
                context.deleteDatabase(databaseName)
                checkpoint.delete()
                marker.delete()
            }
        }
    }

    private fun normalResources(): OrionAndroidResourceProfileSnapshot {
        val policy = OrionResourceProfiles.select(80, 0, false, OrionMemoryPressure.NONE, false)
        return OrionAndroidResourceProfileSnapshot(80, false, 0, false, true, false, policy)
    }

    private fun durableWrite(file: File, contents: String) {
        FileOutputStream(file).use { output ->
            output.write(contents.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
    }

    private object denyExecution : ActionExecutionAuthority {
        override fun startupBarrierOpen() = false
        override fun descriptorFor(skill: String): SkillDescriptor? = null
        override fun currentPolicyVersion() = "orion_sentinel_death_fixture"
        override fun policyAllows(request: NormalizedActionRequest) = false
        override fun capabilityAvailable(capability: Capability) = false
        override fun permissionGranted(permission: String) = false
        override fun userPresent() = false
    }

    private class SimulatedProcessDeath : Error("Synthetic Sentinel fatal boundary")
}
