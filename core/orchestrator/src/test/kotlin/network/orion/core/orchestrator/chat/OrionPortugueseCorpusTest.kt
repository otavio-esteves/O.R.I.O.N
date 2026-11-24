package network.orion.core.orchestrator.chat

import java.util.UUID
import kotlinx.coroutines.runBlocking
import network.orion.core.commands.tasks.*
import network.orion.core.orchestrator.routing.*
import network.orion.core.orchestrator.tasks.*
import network.orion.core.queries.device.DeviceInformationGateway
import network.orion.core.queries.tasks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Model-free routing and call-boundary evaluation. No semantic model-quality claim. */
@RunWith(Parameterized::class)
class OrionPortugueseCorpusTest(private val case: Case) {
    class Case(val id: String, val category: String, val input: String, val route: String, val title: String?,
        val clarification: String?, val forbidden: Set<String>, val confirmation: String, val realModelRequired: Boolean) {
        override fun toString() = id
    }
    private val requestId = UUID.nameUUIDFromBytes(case.id.toByteArray(Charsets.UTF_8))
    @Test fun routeArgumentsAndClarificationMatchTheVersionedCorpus() {
        val route = OrionIntentRouter().route(OrionRoutingInput(case.input, requestId, requestId, OrionRoutingSource.UI_TEXT, true))
        val actual = when (route) {
            is OrionIntentRoute.BatteryQuery -> "BATTERY"
            is OrionIntentRoute.TaskList -> "TASK_LIST"
            is OrionIntentRoute.TaskCreation -> "TASK_CREATE"
            is OrionIntentRoute.CognitiveCandidate -> "COGNITIVE"
            is OrionIntentRoute.Clarification -> "CLARIFY"
            is OrionIntentRoute.Rejected -> "REJECT"
            else -> error("Unexpected partial input route")
        }
        assertEquals(case.id, case.route, actual)
        assertEquals(case.id, case.title, (route as? OrionIntentRoute.TaskCreation)?.intent?.arguments?.title)
        assertEquals(case.id, case.clarification, when (route) {
            is OrionIntentRoute.Clarification -> route.reason.name
            is OrionIntentRoute.Rejected -> route.reason.name
            else -> null
        })
        if (route is OrionIntentRoute.TaskCreation) {
            assertEquals(requestId, route.intent.arguments.taskId)
            assertEquals(requestId, route.intent.arguments.idempotencyKey)
            assertEquals(requestId, route.intent.correlationId)
            assertFalse(route.intent.requiresCortex)
        }
        assertFalse(case.realModelRequired)
        assertEquals(if (actual == "TASK_CREATE") "true" else if (actual == "COGNITIVE") "if_proposed" else "false", case.confirmation)
    }
    @Test fun controllerRespectsProhibitedOperationsWithAnUnavailableModel() = runBlocking {
        var creates = 0; var confirmations = 0; var executions = 0
        var lists = 0; var battery = 0; var configurations = 0; var generations = 0
        var title: String? = null
        val tasks = object : TaskGateway {
            override suspend fun create(command: CreateTask): CreateTaskResult { creates++; title = command.title; return CreateTaskResult.QUEUED }
            override suspend fun list(request: GetTasks): TaskPage { lists++; return TaskPage(emptyList(), null) }
            override suspend fun pending() = emptyList<TaskConfirmation>()
            override suspend fun change(command: TaskStatusCommand) = error("Unsupported write grammar")
            override suspend fun confirm(actionId: UUID, displayedVersion: Long): TaskOperationOutcome {
                confirmations++; executions++; return TaskOperationOutcome.APPLIED
            }
            override suspend fun dismiss(actionId: UUID, displayedVersion: Long) = error("No dismissal gesture")
            override suspend fun processPending() = 0
        }
        val cortex = object : OrionChatCortex {
            override suspend fun configuration(): OrionChatModel? { configurations++; return null }
            override suspend fun generate(model: OrionChatModel, prompt: String, correlationId: String, onDelta: (String) -> Unit): OrionChatGeneration {
                generations++; return OrionChatGeneration(OrionChatTerminal.UNAVAILABLE)
            }
        }
        val chat = OrionChatController(this, tasks, DeviceInformationGateway { battery++; null }, cortex)
        val admission = chat.send(case.input)
        if (case.route in setOf("CLARIFY", "REJECT")) {
            assertTrue(case.id, admission is OrionChatAdmission.Rejected)
            assertTrue(chat.state.value.turns.isEmpty())
        } else {
            assertEquals(OrionChatAdmission.Accepted, admission)
            assertEquals(1, chat.state.value.turns.count { it.user })
        }
        assertEquals(if (case.route == "TASK_CREATE") 1 else 0, creates)
        assertEquals(case.title, title)
        assertEquals(if (case.route == "TASK_LIST") 1 else 0, lists)
        assertEquals(if (case.route == "BATTERY") 1 else 0, battery)
        assertEquals(if (case.route == "COGNITIVE") 1 else 0, configurations)
        assertEquals(0, generations)
        val counts = mapOf("creation" to creates, "confirmation" to confirmations, "execution" to executions,
            "cortex" to configurations + generations)
        for (operation in case.forbidden) assertEquals(case.id + ": " + operation, 0, counts.getValue(operation))
        chat.close()
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Case>> {
            val resource = checkNotNull(OrionPortugueseCorpusTest::class.java.getResourceAsStream("/portuguese-chat-v1.tsv"))
            val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }.map { line ->
                    val f = line.split('\t'); require(f.size == 9)
                    Case(f[0], f[1], unescape(f[2]), f[3], f[4].takeUnless { it == "-" }?.let(::unescape),
                        f[5].takeUnless { it == "-" }, f[6].split(',').toSet(), f[7], f[8].toBooleanStrict())
                }
            require(rows.size >= 40 && rows.map { it.id }.distinct().size == rows.size)
            require(rows.map { it.category }.toSet().containsAll(setOf("query", "creation", "title_missing", "negation",
                "quotation", "ambiguous", "compound", "typo", "authorization", "correction", "invalid")))
            return rows.map { arrayOf(it) }
        }
        /** Fixture codec only: UTF-8 literal text plus explicit control/surrogate escapes. */
        private fun unescape(value: String): String = buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\') append(c) else {
                    require(i < value.length)
                    append(when (value[i++]) {
                        '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '0' -> '\u0000'
                        'u' -> { require(i + 4 <= value.length); value.substring(i, i + 4).toInt(16).toChar().also { i += 4 } }
                        else -> error("Unsupported fixture escape")
                    })
                }
            }
        }
    }
}
