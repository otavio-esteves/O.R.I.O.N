package network.orion.app.device

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import network.orion.core.actions.*
import network.orion.core.model.*
import network.orion.core.queries.activity.*
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.data.events.OrionActivityQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionActivityQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val at=Instant.ofEpochMilli(1000)
    private val privateText="orion-private-history-fixture-98C"
    private fun fixture(body:(OrionDatabase,OrionActivityQuery)->Unit) {
        val name="orion_activity_${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
        try { body(db,OrionActivityQuery(db)) } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun event(db:OrionDatabase,id:UUID,whenAt:Long=1000,code:String="TASK_CREATED") {
        db.events().append(DomainEventOutboxEntity(id.toString(),UUID.randomUUID().toString(),0,code,1,
            Sensitivity.SECRET,whenAt,UUID.randomUUID().toString()))
    }
    private fun action(db:OrionDatabase,id:UUID) {
        val key=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 42 },"AES"))
        val crypto=OrionFieldEncryption(object:FieldKeyProvider {
            override fun currentKey()=key; override fun keyForVersion(version:Int)=key
        })
        val store=OrionActionStore(db,crypto,WallClock { at })
        val request=NormalizedActionRequest(id,"task.create",privateText,privateText,AuthorizationLevel.SAFE_WRITE,
            1,SideEffectClass.LOCAL_MUTATION,RecoveryStrategy.MANUAL_REVIEW,IdempotencyMode.KEYED,"1")
        assertEquals(ActionStoreOutcome.REGISTERED,store.register(request,
            IdempotencyKeyPolicy("CORE","task.create",Duration.ofDays(90),1,OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT),
            id.toString(),UUID.randomUUID(),Sensitivity.SECRET,true).outcome)
    }
    @Test fun stableMixedPaginationAcrossEqualTimesIsReadOnlyAndDoesNotDecryptSecrets() = fixture { db,query ->
        val ids=(1..4).map { UUID(0,it.toLong()) }
        action(db,ids[3]); ids.take(3).forEach { event(db,it) }
        val before=state(db)
        val first=query.get(GetActivity(limit=2))
        assertEquals(listOf(ActivityKind.ACTION,ActivityKind.DOMAIN_EVENT),first.items.map { it.kind })
        assertEquals("E:${ids[0]}",first.next!!.key)
        val second=query.get(GetActivity(first.next,2))
        assertEquals(2,second.items.size); assertNull(second.next)
        assertEquals(4,first.items.size+second.items.size)
        for (item in first.items+second.items) {
            assertEquals(at,item.occurredAt)
            assertFalse(item.toString().contains(privateText)); assertFalse(item.code.contains(privateText))
        }
        assertEquals(before,state(db))
        assertEquals(0,count(db,"orion_event_consumer_receipts"))
    }
    @Test fun newerRecordsDoNotShiftTheCursorAndUnknownCodesAreMinimized() = fixture { db,query ->
        event(db,UUID(0,1),1000); event(db,UUID(0,2),900,"PRIVATE_MARKER")
        val first=query.get(GetActivity(limit=1))
        event(db,UUID(0,3),1100)
        val rest=query.get(GetActivity(first.next,1))
        assertEquals("CORE_EVENT",rest.items.single().code); assertEquals(Instant.ofEpochMilli(900),rest.items.single().occurredAt)
        assertNull(rest.next)
    }
    @Test fun unavailableStorageReturnsUnavailableInsteadOfReadingOrCreatingDatabase() = runBlocking {
        var calls=0
        val port=OrionActivityUiGateway { calls++; null }
        assertNull(port.get(GetActivity()))
        assertEquals(1,calls)
    }
    private fun count(db:OrionDatabase,table:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun state(db:OrionDatabase)=listOf("orion_action_requests","orion_domain_event_outbox","orion_action_audit",
        "orion_event_consumer_receipts","orion_ingress_envelopes").map { count(db,it) } +
        db.openHelper.readableDatabase.query("SELECT coalesce(sum(version),0) FROM orion_action_requests").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        } + db.openHelper.readableDatabase.query("SELECT coalesce(sum(version),0) FROM orion_domain_event_outbox").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
}
