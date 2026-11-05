package network.orion.app.notifications

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.notifications.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.orchestrator.notifications.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.skills.metadata.SkillDescriptor
import network.orion.core.time.*
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.skills.notifications.NotificationPostResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host SIGKILL protocol. The external effect is an explicitly SYNTHETIC durable
 * marker, not NotificationManager evidence. A real Android posting smoke test is
 * separate. Default suite mode simulates the fatal boundary without killing its runner.
 */
@RunWith(AndroidJUnit4::class)
class OrionNotificationTransactionProcessDeathTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    @Test fun committedExternalClaimSurvivesDeathWithoutBlindReplay() {
        val arguments=InstrumentationRegistry.getArguments()
        val mode=arguments.getString("orion_transaction_mode")?:"rollback"
        require(mode in setOf("rollback","prepare","verify"))
        val fixture=arguments.getString("orion_transaction_fixture")?.let {
            require(UUID.fromString(it).toString()==it);UUID.fromString(it)
        }?:run { require(mode=="rollback");UUID.randomUUID() }
        val name="orion_notification_transaction_death_$fixture.db"
        val checkpoint=File(context.filesDir,"orion_transaction_$fixture.ready")
        val marker=File(context.filesDir,"orion_notification_$fixture.marker")
        if(mode=="verify")require(context.getDatabasePath(name).isFile&&checkpoint.isFile&&marker.isFile)
        else require(!context.getDatabasePath(name).exists()&&!marker.exists()&&!checkpoint.exists())
        var elapsed=100L
        val clock=object:OrionClock {
            override fun wallNow()=Instant.ofEpochMilli(1000)
            override fun monotonicNow()=MonotonicInstant(BootSessionId(7),elapsed)
        }
        val encryption=OrionFieldEncryption(object:FieldKeyProvider {
            override fun currentKey()=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 61 },"AES"))
            override fun keyForVersion(version:Int)=currentKey()
        })
        val db=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
        val barrier=StartupBarrier();barrier.releaseAfterRecovery(barrier.beginRecovery(),CoreReadiness.DEGRADED_SAFE)
        val owner=OrionNotificationAdmissionOwner(OrionProcessRole.MAIN,barrier,
            {OrionPolicyEvidence.SATISFIED},{OrionPolicyEvidence.SATISFIED},{OrionPolicyEvidence.SATISFIED})
        val channel=owner.openUiChannel().apply {onResumed(true);onWindowFocus(true)}
        val key=UUID.nameUUIDFromBytes("$fixture/key".toByteArray())
        val requestId=UUID.nameUUIDFromBytes("$fixture/request".toByteArray())
        val command=PostNotification(requestId,"Synthetic external marker","No Android notification is posted by this fixture",key,fixture)
        var invokes=0
        val poster=object:OrionNotificationPoster {
            override fun permissionGranted()=true
            override fun availability()=NotificationAvailability.AVAILABLE
            override fun post(actionId:UUID,notification:ValidatedPostNotification,permit:OrionNotificationInvocationPermit):NotificationPostResult {
                assertFalse(db.inTransaction())
                val observer=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
                try { assertEquals(ActionStatus.RUNNING,observer.actions().find(actionId.toString())!!.status) }
                finally {observer.close()}
                if(!permit.allows())return NotificationPostResult.NOT_INVOKED
                invokes++
                // This marker is OUTSIDE SQLite and models an independently committed external effect.
                val previous=if(marker.exists())marker.readText().trim().toInt() else 0
                durableWrite(marker,"${previous+1}\n")
                if(mode=="prepare") {
                    durableWrite(checkpoint,Process.myPid().toString())
                    CountDownLatch(1).await(45,TimeUnit.SECONDS)
                    throw AssertionError("Host did not kill after the committed external claim and synthetic external marker.")
                }
                throw SimulatedExternalDeath()
            }
        }
        val kernel=OrionNotificationKernel(db,encryption,clock,barrier,owner,{CoreReadiness.DEGRADED_SAFE},{true},poster)
        fun admission()=(channel.captureFromUser(command) as OrionNotificationAdmissionResult.Admitted).request
        try {
            if(mode!="verify") {
                assertEquals(NotificationOutcome.QUEUED,kernel.submit(OrionNotificationIngressWire("POST",requestId,key,fixture,command.title,command.body),key))
                val p=kernel.pending().single().preview!!
                val normalized=OrionPostNotificationActionStore.normalized(p.actionId,p.notification,p.policyVersion)
                val receipt=ActionConfirmationBinding(clock).recordConfirmation(normalized,clock.wallNow().plusSeconds(60))
                assertEquals(NotificationOutcome.APPLIED,kernel.submit(OrionNotificationIngressWire("POST",requestId,key,fixture,
                    actionId=p.actionId,actionVersion=p.version,fingerprint=p.confirmationFingerprint,policyVersion=p.policyVersion,receipt=receipt),
                    UUID.nameUUIDFromBytes("$fixture/confirmation".toByteArray())))
                try {kernel.executeFromUser(admission(),p.actionId);fail("Synthetic fatal boundary was not reached")}
                catch(_:SimulatedExternalDeath) {require(mode=="rollback")}
                assertEquals(1,invokes)
            }
            val row=checkNotNull(db.actions().findByKey("notification.post",key.toString()))
            assertEquals(ActionStatus.RUNNING,row.status)
            assertEquals(3L,row.version)
            assertEquals("1",marker.readText().trim())
            assertEquals(0,db.events().count())
            elapsed=31_100 // The old same-boot lease has expired; expiry never proves nonexecution.
            val deny=object:ActionExecutionAuthority {
                override fun startupBarrierOpen()=false
                override fun descriptorFor(skill:String):SkillDescriptor?=null
                override fun currentPolicyVersion()="orion_notification_recovery_fixture"
                override fun policyAllows(request:NormalizedActionRequest)=false
                override fun capabilityAvailable(capability:Capability)=false
                override fun permissionGranted(permission:String)=false
                override fun userPresent()=false
            }
            assertEquals(1,OrionActionExecutionStore(db,kernel.actions,clock,deny).recoverAbandoned().recovered)
            assertEquals(ActionStatus.MANUAL_REVIEW,db.actions().find(row.actionId)!!.status)
            assertEquals(NotificationOutcome.UNKNOWN,kernel.executeFromUser(admission(),UUID.fromString(row.actionId)))
            assertEquals("1",marker.readText().trim());assertEquals(0,db.events().count())
            assertEquals(if(mode=="verify")0 else 1,invokes)
            if(mode=="verify")InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("orion_transaction_verified_pid",Process.myPid().toString())
            })
        } finally {
            channel.close();db.close()
            if(mode!="prepare") {context.deleteDatabase(name);checkpoint.delete();marker.delete()}
        }
    }
    private fun durableWrite(file:File,value:String) {
        FileOutputStream(file).use {out->out.write(value.toByteArray(Charsets.US_ASCII));out.fd.sync()}
    }
    private class SimulatedExternalDeath:Error("Synthetic external process-death boundary")
}
