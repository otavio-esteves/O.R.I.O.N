package network.orion.app.voice

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.service.voice.VoiceInteractionService
import network.orion.core.health.OrionAssistantRoleState
import network.orion.core.model.Capability
import network.orion.voice.service.OrionVoiceInteractionService

internal object OrionAssistantRole {
    val capability = Capability("orion_system_assistant")

    fun observe(context: Context): OrionAssistantRoleState = try {
        val roles = context.getSystemService(RoleManager::class.java)
        val role = RoleManager.ROLE_ASSISTANT
        when {
            roles == null || !roles.isRoleAvailable(role) -> OrionAssistantRoleState.UNAVAILABLE
            !roles.isRoleHeld(role) -> OrionAssistantRoleState.NOT_SELECTED
            VoiceInteractionService.isActiveService(context, ComponentName(context, OrionVoiceInteractionService::class.java)) ->
                OrionAssistantRoleState.ACTIVE
            else -> OrionAssistantRoleState.SELECTED_INACTIVE
        }
    } catch (_: SecurityException) {
        OrionAssistantRoleState.UNAVAILABLE
    } catch (_: IllegalStateException) {
        OrionAssistantRoleState.UNAVAILABLE
    }
}
