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
                assertTrue(store.create(id, canonical(id, "marcador${level.name.lowercase()}", level == Sensitivity.PERSONAL), level, MemoryOrigin.USER_DECLARED,
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

    @Test fun upgradeRemovesLegacyPlaintextWhilePreservingReadablePrivateMemory() {
        val id = UUID.randomUUID()
        val payload = canonical(id, "marcadorlegado", false)
        val encrypted = crypto.encrypt(payload, FieldEncryptionContext("memory/$id", "content", 1))
            as FieldEncryptionResult.Encrypted
        migrations.createDatabase(name, 10).use { db ->
            db.execSQL("""INSERT INTO orion_memories (memoryId,contentEnvelope,sensitivity,origin,confidence,
                userConfirmed,createdAt,updatedAt,status,version,accessCount)
                VALUES (?,?,'PERSONAL','USER_DECLARED',1,1,1000,1000,'ACTIVE',0,0)""",
                arrayOf(id.toString(), FieldEnvelopeCodec.encode(encrypted.envelope)))
            db.execSQL("INSERT INTO orion_memory_fts (memoryId, body) VALUES (?, 'marcadorlegado')", arrayOf(id.toString()))
        }
        migrations.runMigrationsAndValidate(name, 13, true, *OrionDatabaseMigrations.ALL).close()
        withRoom { db ->
            assertTrue(bodies(db).isEmpty())
            assertEquals(OrionMemoryIndexProgress.READY, OrionMemorySearchIndex(db, crypto, clock).backfillBatch())
            assertTrue(bodies(db).isEmpty())
            val memory = checkNotNull(OrionMemoryQuery(db, crypto, clock).find(id))
            assertEquals("marcadorlegado", memory.content)
            assertEquals(Sensitivity.SENSITIVE, memory.provenance.sensitivity)
        }
    }

    @Test fun deletingIndexedMemoryRemovesTermsFromPersistedFtsSegments() {
        val id = UUID.randomUUID()
        val trace = UUID.randomUUID()
        val marker = "orionsyntheticdeletedmemory"
        withRoom { db ->
            val store = OrionMemoryStore(db, crypto, clock)
            for ((memory, text) in listOf(id to marker, UUID.randomUUID() to "outra memória")) {
                assertTrue(store.create(memory, canonical(memory, text), Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED,
                    1.0, emptyList(), trace, userConfirmed = true) is MemoryWriteResult.Stored)
            }
            assertTrue(segmentOccurrences(db, marker) > 0)
            assertEquals(ActionMutation.APPLIED, store.delete(id, 0, trace))
        }
        withRoom { db ->
            assertEquals(listOf("outra memória"), bodies(db))
            assertEquals(0, segmentOccurrences(db, marker))
        }
    }

    @Test fun upgradeFromElevenRebuildsOnlyAuthorizedIndexContent() {
        val marker = "orionsyntheticretainedsegment"
        val retained = UUID.randomUUID()
        val payload = canonical(retained, "memória autorizada")
        val encrypted = crypto.encrypt(payload, FieldEncryptionContext("memory/$retained", "content", 1))
            as FieldEncryptionResult.Encrypted
        migrations.createDatabase(name, 11).use { db ->
            db.execSQL("""INSERT INTO orion_memories (memoryId,contentEnvelope,sensitivity,origin,confidence,
                userConfirmed,createdAt,updatedAt,status,version,accessCount)
                VALUES (?,?,'PERSONAL','USER_DECLARED',1,1,1000,1000,'ACTIVE',0,0)""",
                arrayOf(retained.toString(), FieldEnvelopeCodec.encode(encrypted.envelope)))
            db.execSQL("INSERT INTO orion_memory_fts (memoryId, body) VALUES ('old', ?)", arrayOf(marker))
            db.execSQL("INSERT INTO orion_memory_fts (memoryId, body) VALUES (?, 'memória autorizada')", arrayOf(retained.toString()))
            db.execSQL("DELETE FROM orion_memory_fts WHERE memoryId = 'old'")
        }
        migrations.runMigrationsAndValidate(name, 13, true, *OrionDatabaseMigrations.ALL).close()
        withRoom { db ->
            assertTrue(bodies(db).isEmpty())
            assertEquals(OrionMemoryIndexProgress.READY, OrionMemorySearchIndex(db, crypto, clock).backfillBatch())
            assertEquals(listOf("memória autorizada"), bodies(db))
            assertEquals(0, segmentOccurrences(db, marker))
        }
    }

    @Test fun backfillResumesAfterKeyFailureAndReclassificationRemovesPosting() {
        migrations.createDatabase(name, 10).close()
        migrations.runMigrationsAndValidate(name, 13, true, *OrionDatabaseMigrations.ALL).close()
        val ids = List(2) { UUID.randomUUID() }.sortedBy(UUID::toString)
        withRoom { db ->
            ids.forEachIndexed { number, id ->
                val encrypted = crypto.encrypt(canonical(id, "marcadorlegado$number"),
                    FieldEncryptionContext("memory/$id", "content", 1)) as FieldEncryptionResult.Encrypted
                db.openHelper.writableDatabase.execSQL("""INSERT INTO orion_memories
                    (memoryId,contentEnvelope,sensitivity,origin,confidence,userConfirmed,createdAt,updatedAt,status,version,accessCount)
                    VALUES (?,?,'PERSONAL','USER_DECLARED',1,1,1000,1000,'ACTIVE',0,0)""",
                    arrayOf(id.toString(), FieldEnvelopeCodec.encode(encrypted.envelope)))
            }
        }
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

    @Test fun stalePostingWithoutSearchConsentCannotEnterResultsOrChatContext() {
        withRoom { db ->
            val store = OrionMemoryStore(db, crypto, clock)
            val query = OrionMemoryQuery(db, crypto, clock)
            val id = UUID.randomUUID()
            val content = "café privado"
            assertTrue(store.create(id, canonical(id, content, allowSearchIndex = false),
                Sensitivity.PERSONAL, MemoryOrigin.USER_DECLARED, 1.0, emptyList(), id,
                userConfirmed = true) is MemoryWriteResult.Stored)
            assertEquals(OrionMemoryIndexProgress.READY, OrionMemorySearchIndex(db, crypto, clock).backfillBatch())
            db.memoryIndex().insert(OrionMemoryIndexRow(id.toString(), content))

            val result = query.search(SearchMemories("café"))
            assertEquals(MemorySearchState.READY, result.state)
            assertTrue(result.hits.isEmpty())
            val privateMemory = checkNotNull(query.find(id))
            assertEquals(content, privateMemory.content)
            assertTrue(query.currentContextMemories(listOf(MemorySearchHit(privateMemory, 500))).isEmpty())
        }
    }

    private fun canonical(id: UUID, content: String, allowSearchIndex: Boolean = true): ByteArray = (OrionCreateMemoryPreparer.prepare(
        CreateMemory(id, content, id, id, allowSearchIndex)) as CreateMemoryPreparation.Ready).memory.canonicalNormalizedArguments.toByteArray()

    private fun bodies(db: OrionDatabase): List<String> = db.openHelper.readableDatabase
        .query("SELECT body FROM orion_memory_fts ORDER BY body").use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }

    private fun segmentOccurrences(db: OrionDatabase, marker: String): Int = db.openHelper.readableDatabase.query(
        """SELECT (SELECT count(*) FROM orion_memory_fts_segdir WHERE instr(root, CAST(? AS blob)) > 0) +
            (SELECT count(*) FROM orion_memory_fts_segments WHERE instr(block, CAST(? AS blob)) > 0)""",
        arrayOf(marker, marker),
    ).use { rows -> check(rows.moveToFirst()); rows.getInt(0) }
}
