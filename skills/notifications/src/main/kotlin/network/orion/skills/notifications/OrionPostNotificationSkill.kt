package network.orion.skills.notifications

import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor
import network.orion.skills.api.*

/** POSTED requires independent platform registration proof; UNKNOWN is never retryable by this Skill. */
enum class NotificationPostResult { POSTED, NOT_INVOKED, NOT_OWNED, UNKNOWN }
fun interface OrionOwnedNotificationPoster {
    fun post(notification:ValidatedPostNotification,owner:SkillContext):NotificationPostResult
}
class OrionPostNotificationSkill(private val poster:OrionOwnedNotificationPoster):OrionSkill {
    override val descriptor get()=OrionPostNotificationDescriptor.value
    override fun canHandle(intent:OrionIntent<*>)=intent.intentType==descriptor.name&&intent.arguments is ValidatedPostNotification
    fun executeOwned(intent:OrionIntent<*>,context:SkillContext):NotificationPostResult =
        if(!canHandle(intent))NotificationPostResult.NOT_OWNED else poster.post(intent.arguments as ValidatedPostNotification,context)
    override suspend fun execute(intent:OrionIntent<*>,context:SkillContext):SkillResult=when(executeOwned(intent,context)) {
        NotificationPostResult.POSTED->SkillResult.Completed(context.actionId)
        NotificationPostResult.NOT_OWNED->SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
        NotificationPostResult.NOT_INVOKED,NotificationPostResult.UNKNOWN->SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
    }
}
