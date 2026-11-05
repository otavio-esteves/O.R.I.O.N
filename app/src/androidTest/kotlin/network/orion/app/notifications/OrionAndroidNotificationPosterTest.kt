package network.orion.app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import network.orion.core.actions.*
import network.orion.core.commands.notifications.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.StartupBarrier
import network.orion.core.orchestrator.notifications.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.security.*
import network.orion.core.time.*
import network.orion.data.database.OrionDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a dedicated target with explicit POST_NOTIFICATIONS grant; denied environments are SKIPPED. */
@RunWith(AndroidJUnit4::class)
class OrionAndroidNotificationPosterTest {
    @Test fun actualOwnedNotificationHasVerifiedRegistrationAndGenericPrivateLockscreenContent() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val clock=AndroidOrionClock(context.contentResolver)
        val poster=OrionAndroidNotificationPoster(context,clock)
        poster.initializeChannels()
        assumeTrue("Explicit notification permission and enabled user channel are required",poster.availability()==NotificationAvailability.AVAILABLE)
        assumeTrue("Boot session is required for persistent execution ownership",clock.monotonicNow().bootSessionId!=null)
        val name="orion_notification_android_${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,OrionDatabase::class.java,name).build()
        val crypto=OrionFieldEncryption(object:FieldKeyProvider {
            override fun currentKey()=FieldKeyResult.Available(1,SecretKeySpec(ByteArray(32) { 53 },"AES"))
            override fun keyForVersion(version:Int)=currentKey()
        })
        val barrier=StartupBarrier();barrier.releaseAfterRecovery(barrier.beginRecovery(),CoreReadiness.DEGRADED_SAFE)
        val owner=OrionNotificationAdmissionOwner(OrionProcessRole.MAIN,barrier,
            { if(poster.availability()==NotificationAvailability.AVAILABLE)OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED },
            { OrionPolicyEvidence.SATISFIED },{ if(poster.permissionGranted())OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED })
        val channel=owner.openUiChannel().apply { onResumed(true);onWindowFocus(true) }
        val kernel=OrionNotificationKernel(db,crypto,clock,barrier,owner,{CoreReadiness.DEGRADED_SAFE},{true},poster)
        val command=PostNotification(UUID.randomUUID(),"O.R.I.O.N. teste sintético","Conteúdo sintético privado",UUID.randomUUID(),UUID.randomUUID())
        var action:UUID?=null
        try {
            assertEquals(NotificationOutcome.QUEUED,kernel.submit(OrionNotificationIngressWire("POST",command.requestId,command.idempotencyKey,command.correlationId,command.title,command.body),command.idempotencyKey))
            val p=kernel.pending().single().preview!!;action=p.actionId
            val normalized=OrionPostNotificationActionStore.normalized(p.actionId,p.notification,p.policyVersion)
            val receipt=ActionConfirmationBinding(clock).recordConfirmation(normalized,clock.wallNow().plusSeconds(60))
            assertEquals(NotificationOutcome.APPLIED,kernel.submit(OrionNotificationIngressWire("POST",command.requestId,command.idempotencyKey,command.correlationId,
                actionId=p.actionId,actionVersion=p.version,fingerprint=p.confirmationFingerprint,policyVersion=p.policyVersion,receipt=receipt),UUID.randomUUID()))
            val admission=(channel.captureFromUser(command) as OrionNotificationAdmissionResult.Admitted).request
            assertEquals(NotificationOutcome.POSTED,kernel.executeFromUser(admission,p.actionId))
            val manager=context.getSystemService(NotificationManager::class.java)
            val actual=manager.activeNotifications.single { it.tag==p.actionId.toString()&&it.id==0 }.notification
            assertEquals(Notification.VISIBILITY_PRIVATE,actual.visibility)
            assertEquals("O.R.I.O.N.",actual.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertFalse(actual.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(command.body))
            assertTrue(actual.flags and Notification.FLAG_LOCAL_ONLY!=0)
            assertEquals(ActionStatus.SUCCEEDED,db.actions().find(p.actionId.toString())!!.status)
        } finally {
            action?.let { context.getSystemService(NotificationManager::class.java).cancel(it.toString(),0) }
            channel.close();db.close();context.deleteDatabase(name)
        }
    }
}
