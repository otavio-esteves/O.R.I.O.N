package network.orion.core.actions

import java.util.UUID

/** Execute only a persisted ActionRequest ID through policy, recovery and confirmation gates. */
fun interface ActionEngine {
    suspend fun execute(actionId: UUID): ActionExecutionResult
}

/** Local bound engines report actual execution. Unbound ID-only entry remains unavailable. */
enum class ActionExecutionResult { SUCCEEDED, BLOCKED, UNAVAILABLE }

class UnavailableActionEngine : ActionEngine {
    override suspend fun execute(actionId: UUID): ActionExecutionResult = ActionExecutionResult.UNAVAILABLE
}
