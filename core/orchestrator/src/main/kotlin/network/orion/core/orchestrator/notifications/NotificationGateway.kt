package network.orion.core.orchestrator.notifications

import java.util.UUID
import network.orion.core.commands.notifications.*

enum class NotificationAvailability { AVAILABLE, PERMISSION_DENIED, APP_DISABLED, CHANNEL_DISABLED, UNAVAILABLE }
enum class NotificationOutcome { APPLIED, QUEUED, POSTED, REJECTED, BLOCKED, UNKNOWN, UNAVAILABLE }
class NotificationConfirmation(val actionId:UUID,val version:Long,val title:String?,val body:String?,val alreadyConfirmed:Boolean=false) {
    override fun toString()="NotificationConfirmation(<redacted>)"
}
interface NotificationGateway {
    suspend fun prepare(command:PostNotification):PostNotificationResult
    suspend fun pending():List<NotificationConfirmation>
    suspend fun confirm(actionId:UUID,displayedVersion:Long):NotificationOutcome
    suspend fun dismiss(actionId:UUID,displayedVersion:Long):NotificationOutcome
    suspend fun availability():NotificationAvailability
    suspend fun processPending():Int
}
