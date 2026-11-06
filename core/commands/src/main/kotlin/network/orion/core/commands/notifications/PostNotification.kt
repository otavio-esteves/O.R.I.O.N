package network.orion.core.commands.notifications

import java.util.UUID
import java.text.Normalizer
import network.orion.core.commands.Command

/** Explicit manual post. IDs are replay coordinates, never authority or consent. */
data class PostNotification(val requestId: UUID, val title: String, val body: String,
    val idempotencyKey: UUID, val correlationId: UUID) : Command<PostNotificationResult> {
    override fun toString() = "PostNotification(<redacted>)"
}
enum class PostNotificationResult { QUEUED, REJECTED, UNAVAILABLE }
enum class PostNotificationRejection { BLANK_TITLE, BLANK_BODY, TOO_LARGE, INVALID_UNICODE, INVALID_CHARACTER }
sealed interface PostNotificationPreparation {
    class Ready internal constructor(val notification: ValidatedPostNotification) : PostNotificationPreparation {
        override fun toString() = "PostNotificationPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: PostNotificationRejection) : PostNotificationPreparation
}
class ValidatedPostNotification internal constructor(val requestId:UUID, val title:String, val body:String,
    val idempotencyKey:UUID, val correlationId:UUID, val canonicalNormalizedArguments:String) {
    val argumentSchemaVersion get()=1
    val normalizedTarget get()="notification/$requestId"
    override fun toString()="ValidatedPostNotification(<redacted>)"
}

/** Bounded title/body scan before normalization or JSON; no clock, persistence, permission or policy access. */
object OrionPostNotificationPreparer {
    const val MAX_TITLE_BYTES=256
    const val MAX_BODY_BYTES=4096
    fun prepare(command:PostNotification):PostNotificationPreparation {
        failure(command.title,MAX_TITLE_BYTES,false)?.let { return PostNotificationPreparation.Rejected(it) }
        failure(command.body,MAX_BODY_BYTES,true)?.let { return PostNotificationPreparation.Rejected(it) }
        val title=Normalizer.normalize(command.title.trim(),Normalizer.Form.NFC)
        val body=Normalizer.normalize(command.body.trim(),Normalizer.Form.NFC)
        if(title.isEmpty())return PostNotificationPreparation.Rejected(PostNotificationRejection.BLANK_TITLE)
        if(body.isEmpty())return PostNotificationPreparation.Rejected(PostNotificationRejection.BLANK_BODY)
        failure(title,MAX_TITLE_BYTES,false)?.let { return PostNotificationPreparation.Rejected(it) }
        failure(body,MAX_BODY_BYTES,true)?.let { return PostNotificationPreparation.Rejected(it) }
        val canonical="{\"requestId\":\"${command.requestId}\",\"title\":\"${escape(title)}\",\"body\":\"${escape(body)}\"}"
        return PostNotificationPreparation.Ready(ValidatedPostNotification(command.requestId,title,body,
            command.idempotencyKey,command.correlationId,canonical))
    }
    private fun escape(value:String)=buildString {
        for(c in value) when(c) { '"'->append("\\\"");'\\'->append("\\\\");'\n'->append("\\n");else->append(c) }
    }
    private fun failure(value:String,max:Int,multiline:Boolean):PostNotificationRejection? {
        if(value.length>max)return PostNotificationRejection.TOO_LARGE
        var index=0;var bytes=0
        while(index<value.length) {
            val c=value[index]
            when {
                c.isHighSurrogate()-> {
                    if(index+1==value.length||!value[index+1].isLowSurrogate())return PostNotificationRejection.INVALID_UNICODE
                    bytes+=4;index+=2
                }
                c.isLowSurrogate()->return PostNotificationRejection.INVALID_UNICODE
                (c.isISOControl()&&!(multiline&&c=='\n'))||c=='\u2028'||c=='\u2029'->return PostNotificationRejection.INVALID_CHARACTER
                else->{bytes+=when { c.code<=127->1;c.code<=2047->2;else->3 };index++}
            }
            if(bytes>max)return PostNotificationRejection.TOO_LARGE
        }
        return null
    }
}
