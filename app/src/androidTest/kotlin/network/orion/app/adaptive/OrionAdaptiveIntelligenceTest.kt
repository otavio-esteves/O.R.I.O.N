package network.orion.app.adaptive

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.app.MainActivity
import network.orion.app.notifications.OrionAndroidNotificationPoster
import network.orion.app.resources.OrionAndroidResourceProfileSnapshot
import network.orion.app.sentinel.OrionSentinelRuntime
import network.orion.core.commands.memories.CreateMemory
import network.orion.core.commands.memories.CreateMemoryPreparation
import network.orion.core.commands.memories.OrionCreateMemoryPreparer
import network.orion.core.health.CoreReadiness
import network.orion.core.health.OrionHealthStatus
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.queries.memories.MemorySearchState
import network.orion.core.queries.memories.SearchMemories
import network.orion.core.resources.OrionMemoryPressure
import network.orion.core.resources.OrionResourceProfiles
import network.orion.core.security.FieldKeyProvider
import network.orion.core.security.FieldKeyResult
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import network.orion.core.time.OrionClock
import network.orion.data.database.DomainEventOutboxEntity
import network.orion.data.database.MemoryOrigin
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseMigrations
import network.orion.data.database.OrionSentinelFeedback
import network.orion.data.database.OrionSentinelSuggestion
import network.orion.data.database.TaskEntity
import network.orion.data.memory.MemoryWriteResult
import network.orion.data.memory.OrionMemoryQuery
import network.orion.data.memory.OrionMemorySearchIndex
import network.orion.data.memory.OrionMemoryStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionAdaptiveIntelligenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "orion_adaptive_${UUID.randomUUID()}.db"
    private val midday = Instant.parse("2026-11-22T13:00:00Z")
    private var now = midday
    private val clock = object : OrionClock {
        override fun wallNow() = now
        override fun monotonicNow() = MonotonicInstant(BootSessionId(1), android.os.SystemClock.elapsedRealtime())
    }
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32), "AES"))
        override fun keyForVersion(version: Int) = currentKey()
    })

    @After fun deleteFixture() { context.deleteDatabase(dbName) }

    @Test fun roomTwelveRetainsProactivityConsentAndDefaultsPersonalizationOff() {
        migration.createDatabase(dbName, 12).use {
            it.execSQL("INSERT INTO orion_sentinel_consent (id, enabled, version, evaluationFloor) VALUES (1, 1, 7, 100)")
        }
        migration.runMigrationsAndValidate(dbName, 13, true, OrionDatabaseMigrations.MIGRATION_12_13).close()
        Room.databaseBuilder(context, OrionDatabase::class.java, dbName).build().use { db ->
            val consent = checkNotNull(db.sentinel().consent())
            assertTrue(consent.enabled)
            assertEquals(7L, consent.version)
            assertEquals(100L, consent.evaluationFloor)
            assertNull(consent.personalizationSince)
        }
    }

    @Test fun consentIsGatedAndResetsItsFeedbackWindowAcrossClockRollback() = withFixture { db, runtime, barrier ->
        assertFalse(runtime.personalized())
        val generation = barrier.beginRecovery()
        assertFalse(runtime.setPersonalized(true))
        assertFalse(runtime.personalized())
        barrier.releaseAfterRecovery(generation, CoreReadiness.DEGRADED_SAFE)
        runtime.maintain(false, null)
        val firstOptIn = checkNotNull(db.sentinel().consent()).personalizationSince
        assertEquals(midday.toEpochMilli(), firstOptIn)
        assertFalse(runtime.enabled())
        assertTrue(runtime.setEnabled(true))
        assertEquals(firstOptIn, db.sentinel().consent()?.personalizationSince)
        val authorityVersion = checkNotNull(db.sentinel().consent()).version

        now = midday.plus(Duration.ofDays(1))
        sample(db, now, "OPENED", "COMPLETED")
        sample(db, midday, "DISMISSED")
        assertEquals(listOf("COMPLETED"), db.sentinel().feedbackSamples(
            checkNotNull(firstOptIn), midday.toEpochMilli(), now.plusSeconds(5).toEpochMilli()).map { it.outcome })
        assertTrue(runtime.setPersonalized(false))
        assertEquals(authorityVersion, db.sentinel().consent()?.version)
        now = midday.minusSeconds(3600)
        assertTrue(runtime.setPersonalized(true))
        val newFloor = checkNotNull(db.sentinel().consent()?.personalizationSince)
        assertEquals(midday.plus(Duration.ofDays(1)).toEpochMilli(), newFloor)
        assertTrue(db.sentinel().feedbackSamples(newFloor, now.toEpochMilli(),
            midday.plus(Duration.ofDays(2)).toEpochMilli()).isEmpty())
    }

    @Test fun learnedDeferralDoesNotReserveTaskAndRevocationRestoresBaseline() = withFixture { db, runtime, _ ->
        now = midday.minus(Duration.ofDays(5))
        assertTrue(runtime.setEnabled(true))
        assertTrue(runtime.setPersonalized(true))
        for (days in 2L..4L) sample(db, midday.minus(Duration.ofDays(days)), "IGNORED")
        now = midday
        val id = UUID.randomUUID().toString()
        val createdAt = now.minus(Duration.ofDays(2)).toEpochMilli()
        db.tasks().createWithEvent(TaskEntity(id, byteArrayOf(1), Sensitivity.PERSONAL, createdAt, createdAt),
            DomainEventOutboxEntity(id, id, 0, "TASK_CREATED", 1, Sensitivity.PERSONAL, createdAt, id))
        assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
        assertEquals(3, db.sentinel().reservationCount())
        assertTrue(runtime.setPersonalized(false))

        val notifications = context.getSystemService(NotificationManager::class.java)
        try {
            assertEquals(OrionHealthStatus.HEALTHY, runtime.maintain(true, null))
            val posted = db.sentinel().recent(0).first()
            assertEquals(id, posted.taskId)
            assertEquals("SHOWN", db.sentinel().feedback(posted.suggestionId).single().kind)
        } finally {
            db.sentinel().recent(0).filter { it.taskId == id }.forEach { notifications.cancel(it.suggestionId, 0) }
        }
    }

    @Test fun rankingPreferenceReordersEligibleMemoriesAndInvalidatesOldCursor() = withFixture { db, runtime, _ ->
        val store = OrionMemoryStore(db, crypto, clock)
        fun memory(text: String, index: Boolean = true): UUID {
            val id = UUID.randomUUID()
            val command = CreateMemory(id, text, id, id, index)
            val prepared = OrionCreateMemoryPreparer.prepare(command) as CreateMemoryPreparation.Ready
            val content = prepared.memory.canonicalNormalizedArguments.toByteArray()
            assertTrue(store.create(id, content, Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED,
                1.0, emptyList(), id, userConfirmed = true) is MemoryWriteResult.Stored)
            return id
        }
        now = midday.minus(Duration.ofDays(30))
        val older = memory("Prefiro café forte")
        now = midday
        val newer = memory("Quero café bem forte")
        memory("Prefiro café forte", index = false)
        OrionMemorySearchIndex(db, crypto, clock).backfillBatch()
        val query = OrionMemoryQuery(db, crypto, clock)
        fun firstPage() = query.search(SearchMemories("café forte", limit = 1))
        assertEquals(older, firstPage().hits.single().memory.memoryId)
        assertTrue(runtime.setPersonalized(true))
        val first = firstPage()
        assertEquals(newer, first.hits.single().memory.memoryId)
        val next = checkNotNull(first.next)
        val continuation = SearchMemories("café forte", limit = 1, after = next)
        assertEquals(older, query.search(continuation).hits.single().memory.memoryId)
        assertNull(query.search(continuation).next)
        assertTrue(runtime.setPersonalized(false))
        assertEquals(MemorySearchState.PREFERENCES_CHANGED, query.search(continuation).state)
        assertEquals(older, firstPage().hits.single().memory.memoryId)
    }

    @Test fun settingsExposeAnIndependentReversibleOptIn() {
        val label = "Personalização adaptativa"
        val enabled = hasText(label) and isEnabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Ajustes", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(enabled).fetchSemanticsNodes().size == 1 }
            compose.onNode(enabled).performScrollTo().assertIsOff().performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(enabled and isOn()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Diagnóstico", useUnmergedTree = true).performClick()
            compose.onNodeWithText("$label: ativada").performScrollTo()
            compose.onNodeWithText("Ajustes", useUnmergedTree = true).performClick()
            compose.onNode(enabled).performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(enabled and isOn()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(enabled).fetchSemanticsNodes().size == 1 }
            compose.onNode(enabled).assertIsOff()
        }
    }

    private fun sample(db: OrionDatabase, shownAt: Instant, vararg outcomes: String) {
        val id = UUID.randomUUID().toString()
        db.sentinel().reserve(OrionSentinelSuggestion(id, UUID.randomUUID().toString(), shownAt.toEpochMilli()))
        db.sentinel().record(OrionSentinelFeedback(id, "SHOWN", shownAt.toEpochMilli(), null))
        outcomes.forEach { db.sentinel().record(OrionSentinelFeedback(id, it, shownAt.plusSeconds(1).toEpochMilli(), null)) }
    }

    private fun withFixture(block: (OrionDatabase, OrionSentinelRuntime, StartupBarrier) -> Unit) {
        Room.inMemoryDatabaseBuilder(context, OrionDatabase::class.java).build().use { db ->
            val barrier = StartupBarrier()
            barrier.releaseAfterRecovery(barrier.beginRecovery(), CoreReadiness.DEGRADED_SAFE)
            OrionAndroidNotificationPoster(context, clock).initializeChannels()
            val runtime = OrionSentinelRuntime(context, db, crypto, clock, barrier, { CoreReadiness.DEGRADED_SAFE },
                { true }, { OrionHealthStatus.DEGRADED }, zone = { ZoneOffset.UTC }, observeResources = {
                    val profile = OrionResourceProfiles.select(75, 0, false, OrionMemoryPressure.NONE, false)
                    OrionAndroidResourceProfileSnapshot(75, false, 0, false, true, false, profile)
                })
            block(db, runtime, barrier)
        }
    }
}
