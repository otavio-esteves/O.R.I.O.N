package network.orion.app.memories

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.orchestrator.memories.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.queries.memories.GetMemories
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.actions.ActionReadResult
import network.orion.data.database.OrionDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic key/content only. Host prepare/verify modes prove actual SIGKILL/new-PID rollback. */
@RunWith(AndroidJUnit4::class)
class OrionMemoryTransactionProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun interruptedOwnedForgetRestoresEveryCopyThenExplicitReplayErasesWithoutResurrection() {
        val arguments=InstrumentationRegistry.getArguments()
        val mode=arguments.getString("orion_transaction_mode") ?: "rollback"
        require(mode in setOf("rollback","prepare","verify"))
        val fixture=arguments.getString("orion_transaction_fixture")?.let {
            require(UUID.fromString(it).toString()==it); UUID.fromString(it)
        } ?: run { require(mode=="rollback"); UUID.randomUUID() }
        fun id(part:String)=UUID.nameUUIDFromBytes("$fixture/$part".toByteArray())
        val name="orion_memory_transaction_death_$fixture.db"
        val checkpoint=File(context.filesDir,"orion_transaction_$fixture.ready")
        val baseline=File(context.filesDir,"orion_memory_baseline_$fixture.sha256")
        if (mode=="verify") { require(context.getDatabasePath(name).isFile); require(baseline.isFile) }
        else require(!context.getDatabasePath(name).exists())
        val armed=AtomicBoolean(false); val probing=AtomicBoolean(false); val observed=AtomicBoolean(false)
        val reference=AtomicReference<OrionDatabase>()
        val db=Room.databaseBuilder(context,OrionDatabase::class.java,name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql,_ ->
                if (armed.get() && sql.startsWith("SELECT",ignoreCase=true) && probing.compareAndSet(false,true)) {
                    try {
                        val database=reference.get()
                        if (database.inTransaction() && database.actions().findByKey("memory.delete",id("forget-key").toString())?.status==ActionStatus.SUCCEEDED &&
                            database.memories().find(id("memory").toString())==null && database.memories().forgotten(id("memory").toString())==1) {
                            armed.set(false); observed.set(true)
                            assertTrue(database.memories().creationActions().all { it.argumentsEnvelope.isEmpty() && it.targetEnvelope.isEmpty() })
                            assertEquals(0,count(database,"orion_memory_claims"))
                            if (mode=="prepare") {
                                FileOutputStream(checkpoint).use { stream ->
                                    stream.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII)); stream.fd.sync()
                                }
                                CountDownLatch(1).await(45,TimeUnit.SECONDS)
                                throw AssertionError("Host did not kill the forget writer at its checkpoint.")
                            }
                        }
                    } finally { probing.set(false) }
                }
            },Executor { it.run() }).build()
        reference.set(db)
        val clock=object:OrionClock {
            override fun wallNow()=Instant.ofEpochMilli(1000)
            override fun monotonicNow()=MonotonicInstant(BootSessionId(7),100)
        }
        val encryption=OrionFieldEncryption(object:FieldKeyProvider {
            override fun currentKey()=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 13 },"AES"))
            override fun keyForVersion(version:Int)=currentKey()
        })
        val barrier=StartupBarrier().also { it.releaseAfterRecovery(it.beginRecovery(),CoreReadiness.READY) }
        val owner=OrionMemoryAdmissionOwner(OrionProcessRole.MAIN,barrier,
            { OrionPolicyEvidence.SATISFIED },{ OrionPolicyEvidence.SATISFIED })
        val channel=owner.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
        val kernel=OrionMemoryKernel(db,encryption,clock,barrier,owner,{ CoreReadiness.READY },{ true })
        val create=OrionMemoryIngressWire("CREATE",id("memory"),id("create-key"),fixture,"Synthetic private memory $fixture")
        fun consent(pending:OrionMemoryPending):OrionMemoryIngressWire {
            val stored=(kernel.actions.read(pending.display.actionId) as ActionReadResult.Ready).action
            return pending.wire(ActionConfirmationBinding(clock).recordConfirmation(stored.request,clock.wallNow().plusSeconds(60)))
        }
        try {
            if (mode!="verify") {
                assertEquals(MemoryOperationOutcome.QUEUED,kernel.submit(create,id("create-key")))
                assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(kernel.pending().single()),id("create-consent")))
                val claim=id("claim")
                val envelope=(encryption.encrypt("Synthetic claim".toByteArray(),FieldEncryptionContext("memory/${id("memory")}/claim/$claim","statement",1))
                    as FieldEncryptionResult.Encrypted).envelope
                db.openHelper.writableDatabase.execSQL("""INSERT INTO orion_memory_claims
                    (claimId,memoryId,statementEnvelope,sensitivity,origin,confidence,userConfirmed,status,validFrom,validUntil,version)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(claim.toString(),id("memory").toString(),FieldEnvelopeCodec.encode(envelope),
                    "PERSONAL","USER_DECLARED",1.0,1,"ACTIVE",1000,null,0))
                assertEquals(MemoryOperationOutcome.QUEUED,kernel.submit(OrionMemoryIngressWire("DELETE",id("memory"),id("forget-key"),fixture),id("forget-key")))
                FileOutputStream(baseline).use { it.write(snapshot(db).toByteArray(Charsets.US_ASCII)); it.fd.sync() }
                val receipt=consent(kernel.pending().single())
                armed.set(true)
                try {
                    barrier.whileOpen {
                        db.runInTransaction {
                            assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(receipt,id("forget-consent")))
                            assertTrue("Fixture must observe the staged forget before commit",observed.get())
                            throw Rollback()
                        }
                    }
                } catch (_:Rollback) { /* Default suite run proves the same transaction boundary. */ }
            }
            assertEquals(baseline.readText(Charsets.US_ASCII),snapshot(db))
            assertEquals(create.content,kernel.query.get(GetMemories()).items.single().content)
            assertEquals(1,count(db,"orion_memory_claims"))
            assertTrue(db.memories().creationActions().all { it.argumentsEnvelope.isNotEmpty() && it.targetEnvelope.isNotEmpty() })
            assertEquals(0,db.memories().forgotten(id("memory").toString()))
            if (mode=="verify") {
                assertTrue(checkpoint.isFile)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                    putString("orion_transaction_verified_pid",Process.myPid().toString())
                })
            }
            val originalIngress=db.memories().memoryIngress().filter { it.idempotencyKey in setOf(id("create-key").toString(),id("create-consent").toString()) }.map { it.ingressId }
            assertEquals(MemoryOperationOutcome.APPLIED,kernel.submit(consent(kernel.pending().single()),id("forget-consent")))
            assertEquals(0,count(db,"orion_memories")); assertEquals(0,count(db,"orion_memory_claims"))
            assertTrue(db.memories().creationActions().all { it.argumentsEnvelope.isEmpty() && it.targetEnvelope.isEmpty() })
            for (key in originalIngress) assertTrue(db.ingress().find(key)!!.payloadEnvelope.isEmpty())
            val ingressCount=db.ingress().count()
            assertEquals(MemoryOperationOutcome.FORGOTTEN,kernel.submit(create,id("fresh-replay")))
            assertEquals(ingressCount,db.ingress().count()); assertEquals(2,count(db,"orion_domain_event_outbox"))
        } finally {
            channel.close(); db.close()
            if (mode!="prepare") { context.deleteDatabase(name); checkpoint.delete(); baseline.delete() }
        }
    }
    private fun count(db:OrionDatabase,table:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { assertTrue(it.moveToFirst()); it.getInt(0) }
    private fun snapshot(db:OrionDatabase):String {
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            for (table in listOf("orion_memories","orion_memory_claims","orion_action_requests","orion_action_audit","orion_ingress_envelopes","orion_domain_event_outbox","orion_audit")) {
                out.writeUTF(table)
                db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { rows ->
                    while (rows.moveToNext()) for (column in 0 until rows.columnCount) {
                        out.writeInt(rows.getType(column))
                        when (rows.getType(column)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> Unit
                            android.database.Cursor.FIELD_TYPE_BLOB -> { val bytes=rows.getBlob(column); out.writeInt(bytes.size); out.write(bytes) }
                            else -> { val bytes=rows.getString(column).toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
                        }
                    }
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private class Rollback:RuntimeException()
}
