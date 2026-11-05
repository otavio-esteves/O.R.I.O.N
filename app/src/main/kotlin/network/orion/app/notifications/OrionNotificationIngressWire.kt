package network.orion.app.notifications

import java.io.*
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import network.orion.core.commands.notifications.*
import network.orion.core.model.Sensitivity
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressPayload
import network.orion.data.actions.*

/** Schema v1: encrypted initial material; confirmations retain only references and original receipt. */
internal class OrionNotificationIngressWire(val operation:String,val requestId:UUID,val key:UUID,val trace:UUID,
    val title:String="",val body:String="",val actionId:UUID?=null,val actionVersion:Long=0,
    val fingerprint:String="",val policyVersion:String="",val receipt:ActionConfirmationSnapshot?=null) {
    fun command()=PostNotification(requestId,title,body,key,trace)
    fun encode():ByteArray=ByteArrayOutputStream().also { bytes->DataOutputStream(bytes).use { out->
        out.writeInt(1)
        listOf(operation,requestId.toString(),key.toString(),title,body,actionId?.toString()?:"").forEach(out::writeUTF)
        out.writeLong(actionVersion);out.writeUTF(fingerprint);out.writeUTF(policyVersion);out.writeBoolean(receipt!=null)
        receipt?.let { r->
            listOf(r.actionId.toString(),r.normalizedPayloadHash,r.confirmedPayloadHash,r.confirmationFingerprint,r.policyVersionAtConfirmation).forEach(out::writeUTF)
            out.writeLong(r.confirmedAt.toEpochMilli());out.writeLong(checkNotNull(r.confirmationExpiresAt).toEpochMilli());out.writeLong(r.payloadLockedAt.toEpochMilli())
        }
    } }.toByteArray()
    override fun toString()="OrionNotificationIngressWire(<redacted>)"
    companion object {
        const val SCOPE="orion_ui_notification_v1"
        const val SOURCE="MAIN_UI_NOTIFICATION"
        const val TYPE="NotificationRequest"
        fun decode(bytes:ByteArray):OrionNotificationIngressWire {
            require(bytes.size in 1..16_384)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input->
                require(input.readInt()==1)
                val op=input.readUTF();require(op=="POST"||op=="EXECUTE"||op=="DISMISS")
                val id=UUID.fromString(input.readUTF());val key=UUID.fromString(input.readUTF());val trace=UUID(0,0)
                val title=input.readUTF();val body=input.readUTF();val action=input.readUTF().takeIf { it.isNotEmpty() }?.let(UUID::fromString)
                val version=input.readLong();val fp=input.readUTF();val policy=input.readUTF()
                val receipt=if(!input.readBoolean())null else ActionConfirmationSnapshot(UUID.fromString(input.readUTF()),
                    input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),Instant.ofEpochMilli(input.readLong()),Instant.ofEpochMilli(input.readLong()),Instant.ofEpochMilli(input.readLong()))
                require(input.available()==0&&version>=0)
                OrionNotificationIngressWire(op,id,key,trace,title,body,action,version,fp,policy,receipt).also { wire->
                    require(wire.encode().contentEquals(bytes))
                    if(action==null) {
                        require(op=="POST"&&version==0L&&fp.isEmpty()&&policy.isEmpty()&&receipt==null)
                        val ready=OrionPostNotificationPreparer.prepare(wire.command()) as? PostNotificationPreparation.Ready
                        require(ready!=null&&ready.notification.title==title&&ready.notification.body==body)
                    } else {
                        require(title.isEmpty()&&body.isEmpty())
                        require(if(op=="DISMISS")receipt==null else receipt!=null&&receipt.actionId==action)
                    }
                }
            }
        }
    }
}

internal class OrionVerifiedNotificationIngress private constructor(val wire:OrionNotificationIngressWire) {
    companion object {
        fun from(payload:LocalIngressPayload,db:OrionDatabase,actions:OrionActionStore):OrionVerifiedNotificationIngress {
            check(db.inTransaction())
            val row=checkNotNull(db.ingress().find(payload.ingressId.toString()))
            require(row.status==DurableIngressStatus.PROCESSING&&row.source==OrionNotificationIngressWire.SOURCE&&
                payload.scope==OrionNotificationIngressWire.SCOPE&&payload.commandType==OrionNotificationIngressWire.TYPE&&
                payload.schemaVersion==1&&payload.sensitivity==Sensitivity.SENSITIVE&&payload.referenceId==null)
            val bytes=checkNotNull(payload.copyContent())
            try {
                val decoded=OrionNotificationIngressWire.decode(bytes)
                var title=decoded.title;var body=decoded.body
                if(decoded.operation in setOf("POST","EXECUTE")&&decoded.actionId!=null) {
                    val stored=when(val read=actions.read(decoded.actionId)) {
                        is ActionReadResult.Ready->read.action
                        is ActionReadResult.Blocked->throw OrionNotificationTransactionBlocked.Action(
                            if(read.reason in setOf(ActionStoreOutcome.KEY_UNAVAILABLE,ActionStoreOutcome.KEY_INVALIDATED))OrionNotificationActionFailure.KEY_UNAVAILABLE else OrionNotificationActionFailure.UNREADABLE)
                    }
                    val json=org.json.JSONObject(stored.request.canonicalNormalizedArguments)
                    require(json.getString("requestId")==decoded.requestId.toString())
                    title=json.getString("title");body=json.getString("body")
                }
                return OrionVerifiedNotificationIngress(OrionNotificationIngressWire(decoded.operation,decoded.requestId,decoded.key,
                    UUID.fromString(row.correlationId),title,body,decoded.actionId,decoded.actionVersion,decoded.fingerprint,decoded.policyVersion,decoded.receipt))
            } finally { bytes.fill(0) }
        }
    }
}
