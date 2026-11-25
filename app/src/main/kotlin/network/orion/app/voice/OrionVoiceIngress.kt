package network.orion.app.voice

import java.io.*
import network.orion.app.tasks.OrionTaskTransactionBlocked
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import network.orion.core.health.CoreReadiness
import network.orion.core.ingress.*
import network.orion.core.ipc.*
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.routing.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor
import network.orion.core.time.WallClock
import network.orion.data.database.*
import network.orion.data.repository.*
import network.orion.voice.ipc.OrionVoiceAdmission

/** All voice text is scoped to encrypted MAIN ingress. Only final requests reach routing. */
internal class OrionVoiceIngress(
    private val db: OrionDatabase,
    crypto: OrionFieldEncryption,
    private val clock: WallClock,
    private val barrier: StartupBarrier,
    private val readiness: () -> CoreReadiness,
    storageAvailable: () -> Boolean,
    private val battery: () -> Int?,
    private val taskCount: () -> Int,
    private val prepareTask: (network.orion.core.commands.tasks.ValidatedCreateTask) -> Boolean,
    private val conversationScope: CoroutineScope? = null,
    private val answerQuestion: suspend (String, UUID) -> String? = { _, _ -> null },
    private val transcriptPreview: OrionVoiceTranscriptPreview? = null,
) {
    private val router = OrionIntentRouter()
    private val results = LinkedHashMap<UUID, List<VoiceCoreResponse>>()
    private var processingId: UUID? = null
    private class LiveQuestion(val id: UUID, val correlationId: UUID) {
        var prompt: String? = null
        var job: Job? = null
    }
    private var question: LiveQuestion? = null
    private val store = OrionDurableIngressStore(db, crypto, clock, barrier, LocalIngressConsumer { payload ->
        processingId = payload.ingressId
        consume(payload)
    }, SCOPE)
    private val queue = object : DurableIngressQueue {
        override fun enqueue(request: DurableIngressRequest) = store.enqueue(request)
        override fun consumeNext(): Boolean {
            processingId = null
            return try { store.consumeNext() } catch (_: OrionTaskTransactionBlocked) {
                val row = processingId?.let { db.ingress().find(it.toString()) } ?: return false
                db.ingress().transition(row.ingressId, row.version, DurableIngressStatus.REJECTED,
                    clock.wallNow().toEpochMilli()) == ActionMutation.APPLIED
            }
        }
    }
    private val coordinator = DurableIngressCoordinator(queue, barrier, readiness, storageAvailable)

    @Synchronized fun submit(request: VoiceUtteranceRequest): OrionVoiceAdmission {
        if (!request.isFinalTranscript || request.source != OrionVoiceSource.PUSH_TO_TALK ||
            request.text.length > OrionIntentRouter.MAX_INPUT_UTF8_BYTES ||
            request.capturedAt.isAfter(clock.wallNow().plusSeconds(5)) ||
            request.capturedAt.isBefore(clock.wallNow().minusSeconds(90))) return OrionVoiceAdmission.REJECTED_INVALID
        val bytes = encode(request)
        val outcome = try {
            coordinator.submit(DurableIngressRequest(request.requestId, SCOPE, request.requestId, SOURCE, TYPE, 1,
                Sensitivity.SENSITIVE, false, null, bytes, 90_000, Duration.ofDays(1).toMillis(),
                correlationId = request.correlationId))
        } finally { bytes.fill(0) }
        return when (outcome) {
            DurableIngressOutcome.QUEUED -> if (barrier.isOpen() && readiness().isStartupBarrierOpen) {
                if (conversationScope != null && question == null && spokenReminder(request.sessionId, request.text) == null &&
                    router.route(OrionRoutingInput(
                        request.text, request.requestId, request.correlationId, OrionRoutingSource.VOICE_FINAL, true,
                    )) is OrionIntentRoute.CognitiveCandidate) {
                    question = LiveQuestion(request.requestId, request.correlationId)
                }
                OrionVoiceAdmission.ACCEPTED
            } else OrionVoiceAdmission.QUEUED_DURING_RECOVERY
            DurableIngressOutcome.DEDUP -> OrionVoiceAdmission.DUPLICATE_REQUEST
            DurableIngressOutcome.IDEMPOTENCY_CONFLICT, DurableIngressOutcome.INGRESS_ID_CONFLICT -> OrionVoiceAdmission.REJECTED_INVALID
            else -> OrionVoiceAdmission.CORE_UNAVAILABLE
        }
    }

    @Synchronized fun drain() {
        coordinator.drain(25)
        val live = question ?: return
        // The transaction only stages a live read. Cortex starts after the consumed receipt commits.
        val status = db.ingress().findByKey(SCOPE, live.id.toString())?.status
        if (status != DurableIngressStatus.QUEUED && status != DurableIngressStatus.CONSUMED) {
            question = null
            return
        }
        val prompt = live.prompt ?: return
        if (status != DurableIngressStatus.CONSUMED || live.job != null) return
        live.prompt = null
        live.job = checkNotNull(conversationScope).launch {
            var answer: String? = null
            try { answer = answerQuestion(prompt, live.correlationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            finally { complete(live, answer) }
        }
    }

    @Synchronized fun closeConversation() {
        val live = question ?: return
        question = null
        live.job?.cancel()
    }

    @Synchronized private fun complete(live: LiveQuestion, answer: String?) {
        if (question !== live) return
        question = null
        remember(live.id, success(live.id, answer ?: "O Cortex está temporariamente indisponível. Tente novamente.", false))
    }

    @Synchronized fun cancel(id: UUID): Boolean {
        val row = db.ingress().findByKey(SCOPE, id.toString()) ?: return false
        val live = question?.takeIf { it.id == id }
        if (row.status == DurableIngressStatus.CONSUMED && live != null) {
            question = null
            live.job?.cancel()
            remember(id, listOf(terminal(id, OrionVoiceTerminalState.CANCELLED)))
            return true
        }
        if (row.status != DurableIngressStatus.QUEUED) return false
        val cancelled = db.ingress().cancelQueuedVoiceRequest(row.ingressId, row.version, clock.wallNow().toEpochMilli()) == 1
        if (cancelled && live != null) question = null
        return cancelled
    }

    @Synchronized fun responses(id: UUID): List<VoiceCoreResponse> {
        val row = db.ingress().findByKey(SCOPE, id.toString()) ?: return emptyList()
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> if (question?.id == id) pending(id) else
                results[id] ?: recoverResponse(id, row.correlationId)
            DurableIngressStatus.REJECTED, DurableIngressStatus.FAILED -> listOf(
                if (row.lastErrorCode == "VOICE_CANCELLED") terminal(id, OrionVoiceTerminalState.CANCELLED)
                else terminal(id, OrionVoiceTerminalState.FAILED, error = OrionVoiceErrorCode.INVALID_REQUEST),
            )
            DurableIngressStatus.EXPIRED -> listOf(terminal(id, OrionVoiceTerminalState.TIMED_OUT))
            else -> pending(id)
        }
    }

    private fun pending(id: UUID) = listOf(VoiceCoreResponse(SCHEMA, id, 0, OrionVoiceResponseKind.STATUS, null, false, null, null))

    private fun recoverResponse(id: UUID, correlationId: String): List<VoiceCoreResponse> {
        // The voice router uses its request ID as the task creation idempotency key.
        // A committed receipt can be reconstructed without reading or replaying a transcript.
        val skill = OrionCreateTaskDescriptor.value.name
        val prepared = db.actions().findByKey(skill, id.toString())?.let {
            it.skill == skill && it.correlationId == correlationId && it.requiresConfirmation
        } == true
        val text = if (prepared) TASK_REVIEW_TEXT else "A resposta anterior ficou indisponível. Você pode repetir a consulta."
        return success(id, text, prepared)
    }

    private fun consume(payload: LocalIngressPayload): LocalIngressDisposition {
        if (payload.source != SOURCE || payload.commandType != TYPE || payload.schemaVersion != 1 ||
            payload.sensitivity != Sensitivity.SENSITIVE || payload.ingressId != payload.idempotencyKey)
            return LocalIngressDisposition.REJECTED
        val bytes = payload.copyContent() ?: return LocalIngressDisposition.REJECTED
        val decoded = try { decode(bytes, payload) }
            catch (_: IllegalArgumentException) { return LocalIngressDisposition.REJECTED }
            catch (_: IOException) { return LocalIngressDisposition.REJECTED }
            finally { bytes.fill(0) }
        val input = decoded.routing
        val id = input.inputId
        val reminder = spokenReminder(decoded.sessionId, input.text)
        if (reminder != null && transcriptPreview?.publishReminder(decoded.sessionId, reminder) == true) {
            remember(id, success(id, "Confira o lembrete na tela.", false))
            return LocalIngressDisposition.CONSUMED
        }
        val result = when (val route = router.route(input)) {
            is OrionIntentRoute.BatteryQuery -> battery()?.let { "Bateria em $it por cento." } ?: "Bateria indisponível."
            is OrionIntentRoute.TaskList -> "Você tem ${taskCount()} tarefas abertas."
            is OrionIntentRoute.TaskCreation -> {
                val prepared = prepareTask(route.intent.arguments)
                if (prepared) {
                    remember(id, success(id, TASK_REVIEW_TEXT, true))
                    return LocalIngressDisposition.CONSUMED
                }
                "Abra o O.R.I.O.N. e tente novamente."
            }
            is OrionIntentRoute.Clarification -> "Repita o pedido com o título da tarefa."
            is OrionIntentRoute.CognitiveCandidate -> {
                val live = question?.takeIf { it.id == id }
                if (live != null) {
                    live.prompt = input.text
                    return LocalIngressDisposition.CONSUMED
                }
                if (conversationScope == null) "Use voz para criar ou listar tarefas e consultar a bateria."
                else "Repita a pergunta para o Cortex responder."
            }
            else -> return LocalIngressDisposition.REJECTED
        }
        remember(id, success(id, result, false))
        return LocalIngressDisposition.CONSUMED
    }

    private fun spokenReminder(sessionId: UUID, text: String): OrionSpokenReminder? =
        if (transcriptPreview?.watchesReminders(sessionId) == true) OrionSpokenReminder.parse(text) else null

    private fun encode(request: VoiceUtteranceRequest): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use {
            it.writeUTF(request.sessionId.toString())
            it.writeUTF(request.locale)
            it.writeLong(request.capturedAt.toEpochMilli())
            it.writeInt(request.priority)
            it.writeUTF(request.correlationId.toString())
            it.writeUTF(request.text)
        }
    }.toByteArray()

    private data class DecodedVoiceInput(val sessionId: UUID, val routing: OrionRoutingInput)

    private fun decode(bytes: ByteArray, payload: LocalIngressPayload): DecodedVoiceInput =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            val sessionId = UUID.fromString(it.readUTF())
            it.readUTF()
            val captured = Instant.ofEpochMilli(it.readLong())
            require(it.readInt() in 0..100)
            val correlation = UUID.fromString(it.readUTF())
            val text = it.readUTF()
            require(it.available() == 0 && captured <= Instant.ofEpochMilli(payload.receivedAt).plusSeconds(5))
            DecodedVoiceInput(sessionId, OrionRoutingInput(text, payload.ingressId, correlation, OrionRoutingSource.VOICE_FINAL, true))
        }

    private fun remember(id: UUID, response: List<VoiceCoreResponse>) {
        results[id] = response
        if (results.size > 64) results.remove(results.keys.first())
    }

    private fun success(id: UUID, text: String, confirmation: Boolean) = listOf(
        VoiceCoreResponse(SCHEMA, id, 1, OrionVoiceResponseKind.TEXT_DELTA, text, confirmation, null, null),
        terminal(id, OrionVoiceTerminalState.SUCCEEDED, confirmation),
    )

    private fun terminal(id: UUID, state: OrionVoiceTerminalState, confirmation: Boolean = false,
        error: OrionVoiceErrorCode? = null) =
        VoiceCoreResponse(SCHEMA, id, 2, OrionVoiceResponseKind.TERMINAL, null, confirmation, state, error)

    companion object {
        private val SCHEMA = IpcSchemaVersion(1)
        private const val SCOPE = "orion_voice_v1"
        private const val SOURCE = "VOICE_SESSION"
        private const val TYPE = "FinalUtterance"
        private const val TASK_REVIEW_TEXT = "Revise e confirme o pedido na tela Tarefas."
    }
}
