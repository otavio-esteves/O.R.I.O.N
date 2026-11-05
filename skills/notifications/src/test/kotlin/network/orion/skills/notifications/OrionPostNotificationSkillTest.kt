package network.orion.skills.notifications
import java.util.UUID
import network.orion.core.commands.notifications.*
import network.orion.core.model.OrionIntent
import network.orion.skills.api.SkillContext
import org.junit.Assert.*
import org.junit.Test
class OrionPostNotificationSkillTest {
    @Test fun freeFormOrWrongSkillIntentCannotInvokeOwnedPoster() {
        var calls=0
        val skill=OrionPostNotificationSkill(OrionOwnedNotificationPoster { _,_->calls++;NotificationPostResult.POSTED })
        val id=UUID.randomUUID();val context=SkillContext(id,id,3)
        val valid=(OrionPostNotificationPreparer.prepare(PostNotification(id,"Title","Body",id,id)) as PostNotificationPreparation.Ready).notification
        assertEquals(NotificationPostResult.NOT_OWNED,skill.executeOwned(OrionIntent("device.erase",1.0,valid,"MAIN_UI",false,id),context))
        assertEquals(NotificationPostResult.NOT_OWNED,skill.executeOwned(OrionIntent("notification.post",1.0,"publish now","LLM",false,id),context))
        assertEquals(0,calls)
    }
    @Test fun modelConfidenceAndSourceCannotOverrideRealOwnershipRejection() {
        val skill=OrionPostNotificationSkill(OrionOwnedNotificationPoster { _,_->NotificationPostResult.NOT_OWNED })
        val id=UUID.randomUUID();val valid=(OrionPostNotificationPreparer.prepare(PostNotification(id,"Title","Body",id,id)) as PostNotificationPreparation.Ready).notification
        assertEquals(NotificationPostResult.NOT_OWNED,skill.executeOwned(OrionIntent("notification.post",1.0,valid,"approved-by-model",false,id),SkillContext(id,id,3)))
    }
}
