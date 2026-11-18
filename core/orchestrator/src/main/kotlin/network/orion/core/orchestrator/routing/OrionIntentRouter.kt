package network.orion.core.orchestrator.routing

import java.util.Locale
import java.util.UUID
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.commands.tasks.CreateTaskPreparation
import network.orion.core.commands.tasks.CreateTaskRejection
import network.orion.core.commands.tasks.OrionCreateTaskPreparer
import network.orion.core.commands.tasks.ValidatedCreateTask
import network.orion.core.model.OrionIntent
import network.orion.core.queries.device.GetDeviceInformation
import network.orion.core.queries.tasks.GetTasks
import network.orion.core.skills.metadata.device.OrionDeviceDescriptors
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor

/** Producer metadata only. A source label is never user presence, policy or execution authority. */
enum class OrionRoutingSource { UI_TEXT, EXTERNAL_CONTENT }

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
        if (input.source != OrionRoutingSource.UI_TEXT) return rejected(OrionRoutingRejection.UNTRUSTED_SOURCE)
        if (!input.isFinal) return OrionIntentRoute.AwaitingFinalInput(input.correlationId)
        inputFailure(input.text)?.let { return rejected(it) }
        val text = input.text.trim()
        val command = text.lowercase(Locale.ROOT)
        if (text.isEmpty()) return OrionIntentRoute.Clarification(OrionRoutingClarification.EMPTY_INPUT, input.correlationId)
        if (command == "listar tarefas" || command == "ver tarefas" || command == "minhas tarefas") {
            return OrionIntentRoute.TaskList(OrionIntent("task.list", 1.0, GetTasks(),
                input.source.name, false, input.correlationId))
        }
        if (command == "bateria" || command == "consultar bateria") {
            return OrionIntentRoute.BatteryQuery(OrionIntent(OrionDeviceDescriptors.battery.name, 1.0,
                GetDeviceInformation, input.source.name, false, input.correlationId))
        }
        if (command == "criar tarefa") {
            return OrionIntentRoute.Clarification(OrionRoutingClarification.MISSING_TASK_TITLE, input.correlationId)
        }
        val colon = input.text.indexOf(':')
        if (colon >= 0 && input.text.substring(0, colon).trim().lowercase(Locale.ROOT) == "criar tarefa") {
            // Domain identity and idempotency key come from this same retained producer request.
            // This is not an ActionRequest identity, an inference ID or an authorization token.
            val prepared = OrionCreateTaskPreparer.prepare(CreateTask(input.inputId,
                input.text.substring(colon + 1), input.inputId, input.correlationId))
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

    /** Guard raw UTF-16 length and scan bounded Unicode scalars before trim/case-folding/allocation. */
    private fun inputFailure(text: String): OrionRoutingRejection? {
        if (text.length > MAX_INPUT_UTF8_BYTES) return OrionRoutingRejection.INPUT_TOO_LARGE
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == text.length || !text[index + 1].isLowSurrogate())
                        return OrionRoutingRejection.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return OrionRoutingRejection.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return OrionRoutingRejection.INVALID_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_INPUT_UTF8_BYTES) return OrionRoutingRejection.INPUT_TOO_LARGE
        }
        return null
    }

    companion object { const val MAX_INPUT_UTF8_BYTES = 4096 }
}
