package network.orion.core.orchestrator.routing

import java.util.Locale
import java.util.UUID
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.commands.tasks.CreateTaskPreparation
import network.orion.core.commands.tasks.CreateTaskRejection
import network.orion.core.commands.tasks.OrionCreateTaskPreparer
import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.model.OrionIntent
import network.orion.core.orchestrator.chat.OrionChatInputPolicy
import network.orion.core.orchestrator.chat.OrionChatInputError
import network.orion.core.queries.device.GetDeviceInformation
import network.orion.core.queries.tasks.GetTasks
import network.orion.core.skills.metadata.device.OrionDeviceDescriptors
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor

/** Producer metadata only. A source label is never user presence, policy or execution authority. */
enum class OrionRoutingSource { UI_TEXT, EXTERNAL_CONTENT, VOICE_FINAL }

/** Retain inputId across replay; a new explicit request gets a new inputId. Never persist raw text here. */
class OrionRoutingInput(
    val text: String,
    val inputId: UUID,
    val correlationId: UUID,
    val source: OrionRoutingSource,
    val isFinal: Boolean,
) {
    override fun toString() = "OrionRoutingInput(<redacted>)"
}

enum class OrionRoutingClarification { EMPTY_INPUT, MISSING_TASK_TITLE }
enum class OrionRoutingRejection {
    UNTRUSTED_SOURCE, INPUT_TOO_LARGE, INVALID_UNICODE, INVALID_CHARACTER, INVALID_TASK_ARGUMENTS,
}

/** In-memory interpretation only. No result dispatches a query/command, authorizes a Skill or starts Cortex. */
sealed interface OrionIntentRoute {
    class TaskList(val intent: OrionIntent<GetTasks>) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.TaskList(<redacted>)"
    }
    class TaskCreation(val intent: OrionIntent<ValidatedCreateTask>) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.TaskCreation(<redacted>)"
    }
    class BatteryQuery(val intent: OrionIntent<GetDeviceInformation>) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.BatteryQuery(<redacted>)"
    }
    class CognitiveCandidate(val intent: OrionIntent<Unit>) : OrionIntentRoute {
        val correlationId: UUID get() = intent.correlationId
        override fun toString() = "OrionIntentRoute.CognitiveCandidate(<redacted>)"
    }
    class AwaitingFinalInput(val correlationId: UUID) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.AwaitingFinalInput(<redacted>)"
    }
    class Clarification(val reason: OrionRoutingClarification, val correlationId: UUID) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.Clarification(reason=$reason)"
    }
    class Rejected(val reason: OrionRoutingRejection, val correlationId: UUID,
        val taskRejection: CreateTaskRejection? = null) : OrionIntentRoute {
        override fun toString() = "OrionIntentRoute.Rejected(reason=$reason)"
    }
}

/**
 * Closed, bounded task/battery grammar. Unmatched trusted final text is a cognitive intent.
 * All text after `criar tarefa:` is one literal title. Schema validation is reused
 * from core:commands; no authorization/confirmation field is accepted from text.
 *
 * The trusted chat adapter submits through the existing gateway/ingress,
 * obtains policy/capability/permission decisions and binds human confirmation.
 * CognitiveCandidate means no deterministic match, not ResourceGovernor approval
 * or permission to bind/load/infer. Partial and external input cannot select it.
 */
class OrionIntentRouter {
    fun route(input: OrionRoutingInput): OrionIntentRoute {
        fun rejected(reason: OrionRoutingRejection) = OrionIntentRoute.Rejected(reason, input.correlationId)
        if (input.source == OrionRoutingSource.EXTERNAL_CONTENT) return rejected(OrionRoutingRejection.UNTRUSTED_SOURCE)
        if (!input.isFinal) return OrionIntentRoute.AwaitingFinalInput(input.correlationId)
        OrionChatInputPolicy.validate(input.text)?.let { failure ->
            return when (failure) {
                OrionChatInputError.EMPTY_INPUT -> OrionIntentRoute.Clarification(OrionRoutingClarification.EMPTY_INPUT, input.correlationId)
                OrionChatInputError.INPUT_TOO_LARGE -> rejected(OrionRoutingRejection.INPUT_TOO_LARGE)
                OrionChatInputError.INVALID_UNICODE -> rejected(OrionRoutingRejection.INVALID_UNICODE)
                OrionChatInputError.INVALID_CHARACTER -> rejected(OrionRoutingRejection.INVALID_CHARACTER)
                else -> error("Raw input policy returned a domain rejection.")
            }
        }
        val text = input.text.trim()
        val command = text.lowercase(Locale.ROOT)
        if (text.isEmpty()) return OrionIntentRoute.Clarification(OrionRoutingClarification.EMPTY_INPUT, input.correlationId)
        val query = queryExpression(command)
        if (query in TASK_LIST_ALIASES) {
            return OrionIntentRoute.TaskList(OrionIntent("task.list", 1.0, GetTasks(),
                input.source.name, false, input.correlationId))
        }
        if (query in BATTERY_ALIASES) {
            return OrionIntentRoute.BatteryQuery(OrionIntent(OrionDeviceDescriptors.battery.name, 1.0,
                GetDeviceInformation, input.source.name, false, input.correlationId))
        }
        if (command == "criar tarefa") {
            return OrionIntentRoute.Clarification(OrionRoutingClarification.MISSING_TASK_TITLE, input.correlationId)
        }
        val colon = input.text.indexOf(':')
        val title = when {
            colon >= 0 && input.text.substring(0, colon).trim().lowercase(Locale.ROOT) == "criar tarefa" ->
                input.text.substring(colon + 1)
            input.source == OrionRoutingSource.VOICE_FINAL && command.startsWith("criar tarefa ") ->
                input.text.trimStart().substring("criar tarefa ".length)
            else -> null
        }
        if (title != null) {
            // Domain identity and idempotency key come from this same retained producer request.
            // This is not an ActionRequest identity, an inference ID or an authorization token.
            val prepared = OrionCreateTaskPreparer.prepare(CreateTask(input.inputId,
                title, input.inputId, input.correlationId))
            return when (prepared) {
                is CreateTaskPreparation.Ready -> OrionIntentRoute.TaskCreation(OrionIntent(
                    OrionCreateTaskDescriptor.value.name, 1.0, prepared.task, input.source.name, false, input.correlationId))
                is CreateTaskPreparation.Rejected -> if (prepared.reason == CreateTaskRejection.BLANK_TITLE)
                    OrionIntentRoute.Clarification(OrionRoutingClarification.MISSING_TASK_TITLE, input.correlationId)
                else OrionIntentRoute.Rejected(OrionRoutingRejection.INVALID_TASK_ARGUMENTS, input.correlationId, prepared.reason)
            }
        }
        return OrionIntentRoute.CognitiveCandidate(OrionIntent("cognitive.request", 0.0, Unit,
            input.source.name, true, input.correlationId))
    }

    /** Queries only: whole expression, Locale.ROOT case, runs of ASCII spaces and
     * at most one terminal '?' or '.', optionally preceded by ASCII spaces.
     * Never remove quotes, words, negation, filters or punctuation from task arguments.
     */
    private fun queryExpression(command: String): String {
        val expression = if (command.lastOrNull() in setOf('?', '.')) command.dropLast(1).trimEnd(' ') else command
        return ASCII_SPACES.replace(expression, " ")
    }
    companion object {
        const val MAX_INPUT_UTF8_BYTES = OrionChatInputPolicy.MAX_UTF8_BYTES
        private val ASCII_SPACES = Regex(" +")
        private val TASK_LIST_ALIASES = setOf("listar tarefas", "ver tarefas", "minhas tarefas", "quais são minhas tarefas")
        private val BATTERY_ALIASES = setOf("bateria", "consultar bateria", "como está a bateria")
    }
}
