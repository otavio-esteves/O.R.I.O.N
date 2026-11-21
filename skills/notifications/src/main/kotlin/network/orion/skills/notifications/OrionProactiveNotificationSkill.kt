package network.orion.skills.notifications

import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.model.OrionIntent
import network.orion.core.skills.metadata.notifications.OrionProactiveNotificationDescriptor
import network.orion.skills.api.OrionSkill
import network.orion.skills.api.SkillContext
import network.orion.skills.api.SkillFailure
import network.orion.skills.api.SkillResult

class OrionProactiveNotificationSkill(private val poster: OrionOwnedNotificationPoster) : OrionSkill {
    override val descriptor get() = OrionProactiveNotificationDescriptor.value

    override fun canHandle(intent: OrionIntent<*>): Boolean {
        val notification = intent.arguments as? ValidatedPostNotification ?: return false
        return intent.intentType == descriptor.name && intent.source == OrionProactiveNotificationDescriptor.SOURCE &&
            notification.title == OrionProactiveNotificationDescriptor.TITLE &&
            notification.body == OrionProactiveNotificationDescriptor.BODY
    }

    fun executeOwned(intent: OrionIntent<*>, context: SkillContext): NotificationPostResult {
        if (!canHandle(intent)) return NotificationPostResult.NOT_OWNED
        val notification = intent.arguments as ValidatedPostNotification
        if (context.actionId != notification.requestId || context.actionId != notification.idempotencyKey)
            return NotificationPostResult.NOT_OWNED
        return poster.post(notification, context)
    }

    override suspend fun execute(intent: OrionIntent<*>, context: SkillContext): SkillResult =
        when (executeOwned(intent, context)) {
            NotificationPostResult.POSTED -> SkillResult.Completed(context.actionId)
            NotificationPostResult.NOT_OWNED -> SkillResult.Rejected(SkillFailure.EXECUTION_NOT_OWNED)
            NotificationPostResult.NOT_INVOKED, NotificationPostResult.UNKNOWN ->
                SkillResult.Rejected(SkillFailure.STORAGE_UNAVAILABLE)
        }
}
