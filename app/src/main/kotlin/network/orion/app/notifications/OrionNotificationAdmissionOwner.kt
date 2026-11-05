package network.orion.app.notifications

import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.core.commands.notifications.*
import network.orion.core.common.process.OrionProcessRole
import network.orion.core.ingress.StartupBarrier
import network.orion.core.policy.notifications.*
import network.orion.core.policy.tasks.OrionPolicyEvidence
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor

internal sealed interface OrionNotificationAdmissionResult {
    class Admitted(val request:OrionPostNotificationAdmission):OrionNotificationAdmissionResult
    data class Invalid(val reason:PostNotificationRejection):OrionNotificationAdmissionResult
    data object Unavailable:OrionNotificationAdmissionResult
}
internal class OrionPostNotificationAdmission(val notification:ValidatedPostNotification,val source:PostNotificationPolicyContextSource) {
    fun evaluate()=OrionPostNotificationPolicy(source).evaluate(notification)
    override fun toString()="OrionPostNotificationAdmission(<redacted>)"
}

/** MAIN's actual focused UI boundary, never a client-origin flag. Each source is bound to one request. */
internal class OrionNotificationAdmissionOwner(private val role:OrionProcessRole,private val barrier:StartupBarrier,
    private val writeCapability:()->OrionPolicyEvidence,private val devicePresence:()->OrionPolicyEvidence,
    private val permission:()->OrionPolicyEvidence) {
    private var current:UiChannel?=null
    private var revision=0L
    private var generation=0L
    private var last:Evidence?=null
    @Synchronized fun openUiChannel():UiChannel { advance();return UiChannel().also { current=it } }
    @Synchronized fun invalidateAdmissions() { advance() }
    inner class UiChannel internal constructor() {
        private var resumed=false;private var focused=false;private var closed=false
        fun onResumed(value:Boolean)=synchronized(this@OrionNotificationAdmissionOwner) { if(!closed&&resumed!=value) { resumed=value;advance() } }
        fun onWindowFocus(value:Boolean)=synchronized(this@OrionNotificationAdmissionOwner) { if(!closed&&focused!=value) { focused=value;advance() } }
        fun close()=synchronized(this@OrionNotificationAdmissionOwner) { if(!closed) { closed=true;if(current===this)current=null;advance() } }
        internal fun active()=!closed&&current===this&&resumed&&focused
        fun captureFromUser(command:PostNotification):OrionNotificationAdmissionResult=synchronized(this@OrionNotificationAdmissionOwner) {
            if(!active()||role!=OrionProcessRole.MAIN)return@synchronized OrionNotificationAdmissionResult.Unavailable
            val presence=try { devicePresence() } catch(c:CancellationException) { throw c }
                catch(i:InterruptedException) { Thread.currentThread().interrupt();throw i }
                catch(_:Exception) { return@synchronized OrionNotificationAdmissionResult.Unavailable }
            if(presence!=OrionPolicyEvidence.SATISFIED)return@synchronized OrionNotificationAdmissionResult.Unavailable
            when(val input=OrionPostNotificationPreparer.prepare(command)) {
                is PostNotificationPreparation.Rejected->OrionNotificationAdmissionResult.Invalid(input.reason)
                is PostNotificationPreparation.Ready->OrionNotificationAdmissionResult.Admitted(admission(input.notification,this))
            }
        }
        fun captureDismissFromUser()=synchronized(this@OrionNotificationAdmissionOwner) {
            active()&&role==OrionProcessRole.MAIN&&devicePresence()==OrionPolicyEvidence.SATISFIED
        }
    }
    @Synchronized fun replay(origin:OrionVerifiedNotificationIngress):OrionPostNotificationAdmission? {
        val channel=current?:return null
        if(!channel.active()||origin.wire.operation !in setOf("POST","EXECUTE"))return null
        val input=OrionPostNotificationPreparer.prepare(origin.wire.command()) as? PostNotificationPreparation.Ready?:return null
        return admission(input.notification,channel)
    }
    @Synchronized fun mayDismiss(origin:OrionVerifiedNotificationIngress)=origin.wire.operation=="DISMISS"&&
        role==OrionProcessRole.MAIN&&barrier.isOpen()&&current?.active()==true&&devicePresence()==OrionPolicyEvidence.SATISFIED
    private fun admission(input:ValidatedPostNotification,channel:UiChannel):OrionPostNotificationAdmission {
        val binding=PostNotificationPolicyInputBinding.from(input);val id=UUID.randomUUID();val admitted=generation
        return OrionPostNotificationAdmission(input,PostNotificationPolicyContextSource {
            synchronized(this) {
                if(current!==channel||!channel.active()||generation!=admitted)PostNotificationPolicyContextRead.Unavailable
                else {
                    val evidence=Evidence(barrier.isOpen(),writeCapability(),devicePresence(),permission())
                    if(evidence!=last) { revision=Math.addExact(revision,1);last=evidence }
                    PostNotificationPolicyContextRead.Available(PostNotificationPolicyContext(id,binding,revision,role,
                        PostNotificationAdmissionOrigin.EXPLICIT_USER_REQUEST,
                        if(evidence.open)OrionPolicyEvidence.SATISFIED else OrionPolicyEvidence.NOT_SATISFIED,
                        evidence.presence,mapOf(OrionPostNotificationDescriptor.WRITE_CAPABILITY to evidence.capability),
                        mapOf(OrionPostNotificationDescriptor.POST_PERMISSION to evidence.permission)))
                }
            }
        })
    }
    private fun advance() { generation=Math.addExact(generation,1);revision=Math.addExact(revision,1) }
    private data class Evidence(val open:Boolean,val capability:OrionPolicyEvidence,val presence:OrionPolicyEvidence,val permission:OrionPolicyEvidence)
}
