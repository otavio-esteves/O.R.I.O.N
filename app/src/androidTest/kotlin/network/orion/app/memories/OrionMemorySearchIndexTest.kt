package network.orion.app.memories

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.commands.memories.*
import network.orion.core.model.OrionMemoryLifecycle
import network.orion.core.model.Sensitivity
import network.orion.core.queries.memories.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.memory.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionMemorySearchIndexTest {
    @get:Rule val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), OrionDatabase::class.java,
    )
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "orion_memory_index_${UUID.randomUUID()}.db"
    private var at = Instant.ofEpochMilli(1_000)
    private val clock = WallClock { at }
    private var key: FieldKeyResult = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 21 }, "AES"))
    private val crypto = OrionFieldEncryption(object : FieldKeyProvider {
        override fun currentKey() = key
        override fun keyForVersion(version: Int) = key
    })

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun protectedBytesNeverEnterFtsAndRemovalFollowsSupersessionAndDeletion() {
        withRoom { db ->
            val store = OrionMemoryStore(db, crypto, clock)
            val prior = UUID.randomUUID()
            val next = UUID.randomUUID()
            val trace = UUID.randomUUID()
            for (level in listOf(Sensitivity.PERSONAL, Sensitivity.SENSITIVE, Sensitivity.SECRET)) {
                val id = if (level == Sensitivity.PERSONAL) prior else UUID.randomUUID()
                assertTrue(store.create(id, canonical(id, "marcador${level.name.lowercase()}"), level, MemoryOrigin.USER_DECLARED,
                    1.0, emptyList(), trace, userConfirmed = true) is MemoryWriteResult.Stored)
            }
            assertEquals(listOf("marcadorpersonal"), bodies(db))
            at = at.plusMillis(1)
            assertTrue(store.supersede(prior, 0, next, canonical(next, "marcadorcorrigido"), Sensitivity.PERSONAL,
                MemoryOrigin.USER_DECLARED, 1.0, emptyList(), trace, userConfirmed = true) is MemoryWriteResult.Stored)
            assertEquals(listOf("marcadorcorrigido"), bodies(db))
            assertEquals(ActionMutation.APPLIED, store.delete(next, 0, trace))
            assertTrue(bodies(db).isEmpty())
        }
    }

    @Test fun upgradeResumeAndKeyFailureKeepIndexUnavailableUntilItsCursorCompletes() {
        val ids = List(2) { UUID.randomUUID() }.sortedBy(UUID::toString)
        migrations.createDatabase(name, 8).use { db ->
            ids.forEachIndexed { number, id ->
                val body = canonical(id, "marcadorlegado$number")
                val encrypted = crypto.encrypt(body, FieldEncryptionContext("memory/$id", "content", 1))
                    as FieldEncryptionResult.Encrypted
                db.execSQL("""INSERT INTO orion_memories (memoryId,contentEnvelope,sensitivity,origin,confidence,
                    userConfirmed,createdAt,updatedAt,status,version) VALUES (?,?,'PERSONAL','USER_DECLARED',1,1,1000,1000,'ACTIVE',0)""",
                    arrayOf(id.toString(), FieldEnvelopeCodec.encode(encrypted.envelope)))
            }
        }
        migrations.runMigrationsAndValidate(name, 9, true, *OrionDatabaseMigrations.ALL).close()
        withRoom { db ->
            assertEquals(OrionMemoryIndexProgress.BUILDING, OrionMemorySearchIndex(db, crypto, clock).backfillBatch(1))
            assertEquals(ids.first().toString(), db.memoryIndex().state()?.cursor)
            assertEquals(listOf("marcadorlegado0"), bodies(db))
        }
        withRoom { db ->
            val originalKey = key
            val index = OrionMemorySearchIndex(db, crypto, clock)
            key = FieldKeyResult.Unavailable
            assertEquals(OrionMemoryIndexProgress.BLOCKED, index.backfillBatch(1))
            assertEquals(ids.first().toString(), db.memoryIndex().state()?.cursor)
            assertFalse(checkNotNull(db.memoryIndex().state()).ready)
            key = originalKey
            assertEquals(OrionMemoryIndexProgress.READY, index.backfillBatch(1))
            assertEquals(OrionMemoryIndexProgress.READY, index.backfillBatch(1))
            assertEquals(listOf("marcadorlegado0", "marcadorlegado1"), bodies(db))
            db.runInTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE orion_memories SET sensitivity = 'SENSITIVE' WHERE memoryId = ?",
                    arrayOf(ids.first().toString()))
                assertEquals(listOf("marcadorlegado1"), bodies(db))
            }
        }
    }

    @Test fun searchUsesAuthoritativeValidityAndStableRankingAcrossPages() {
        withRoom { db ->
            val store = OrionMemoryStore(db, crypto, clock)
            val search = OrionMemoryQuery(db, crypto, clock)
            val trace = UUID.randomUUID()
            val ids = List(3) { UUID.randomUUID() }.sortedBy(UUID::toString)
            for (id in ids) {
                val restricted = id == ids.last()
                assertTrue(store.create(id, canonical(id, "Café forte"), Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED,
                    1.0, emptyList(), trace, userConfirmed = true,
                    lifecycle = OrionMemoryLifecycle(expiresAt = if (restricted) at.plusMillis(10) else null)) is MemoryWriteResult.Stored)
            }
            assertEquals(MemorySearchState.BUILDING, search.search(SearchMemories("Café")).state)
            assertEquals(OrionMemoryIndexProgress.READY, OrionMemorySearchIndex(db, crypto, clock).backfillBatch())
            val first = search.search(SearchMemories("cafe forte", limit = 1))
            assertEquals(ids.first(), first.hits.single().memory.memoryId)
            at = at.plusMillis(10)
            val second = search.search(SearchMemories("café forte", limit = 1, after = first.next))
            assertEquals(ids[1], second.hits.single().memory.memoryId)
            assertNull(second.next)

            key = FieldKeyResult.Unavailable
            val blocked = search.search(SearchMemories("café"))
            assertEquals(MemorySearchState.KEY_UNAVAILABLE, blocked.state)
            assertTrue(blocked.hits.isEmpty())
        }
    }

    @Test fun selectedContextCannotUseAMemoryDeletedAfterTheSearch() {
        withRoom { db ->
            val id = UUID.randomUUID()
            val trace = UUID.randomUUID()
            val store = OrionMemoryStore(db, crypto, clock)
            val query = OrionMemoryQuery(db, crypto, clock)
            assertTrue(store.create(id, canonical(id, "Prefiro café forte"), Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED,
                1.0, emptyList(), trace, userConfirmed = true) is MemoryWriteResult.Stored)
            assertEquals(OrionMemoryIndexProgress.READY, OrionMemorySearchIndex(db, crypto, clock).backfillBatch())
            val selected = query.search(checkNotNull(SearchMemories.forChat("Qual meu café e viagem?"))).hits
            assertEquals(id, query.currentContextMemories(selected).single().memoryId)
            assertEquals(ActionMutation.APPLIED, store.delete(id, 0, trace))
            assertTrue(query.currentContextMemories(selected).isEmpty())
        }
    }

    private fun withRoom(block: (OrionDatabase) -> Unit) {
        val database = Room.databaseBuilder(context, OrionDatabase::class.java, name)
            .addMigrations(*OrionDatabaseMigrations.ALL)
            .build()
        try { block(database) } finally { database.close() }
    }

    private fun canonical(id: UUID, content: String): ByteArray = (OrionCreateMemoryPreparer.prepare(
        CreateMemory(id, content, id, id)) as CreateMemoryPreparation.Ready).memory.canonicalNormalizedArguments.toByteArray()

    private fun bodies(db: OrionDatabase): List<String> = db.openHelper.readableDatabase
        .query("SELECT body FROM orion_memory_fts ORDER BY body").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
}
