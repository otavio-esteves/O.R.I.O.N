package network.orion.core.actions

import java.util.UUID

/** Execute only a persisted ActionRequest ID through policy, recovery and confirmation gates. */
fun interface ActionEngine {
    suspend fun execute(actionId: UUID): ActionExecutionResult
}

/** No execution status is invented while the transactional engine is absent. */
enum class ActionExecutionResult { UNAVAILABLE }

class UnavailableActionEngine : ActionEngine {
    override suspend fun execute(actionId: UUID): ActionExecutionResult = ActionExecutionResult.UNAVAILABLE
}
