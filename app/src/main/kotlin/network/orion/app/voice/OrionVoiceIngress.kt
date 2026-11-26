package network.orion.app.voice

import java.io.*
import network.orion.app.tasks.OrionTaskTransactionBlocked
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import network.orion.voice.ipc.ORION_VOICE_REPLY_TIMEOUT_MILLIS
import network.orion.voice.ipc.OrionVoiceAdmission

/** MAIN owns durable task admission and bounded, in-memory replies to read-only voice turns. */
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
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val router = OrionIntentRouter()
    private val results = LinkedHashMap<UUID, List<VoiceCoreResponse>>()
    private val readTurns = LinkedHashMap<UUID, ReadTurn>()
    private var processingId: UUID? = null

    private class ReadWork(val request: VoiceUtteranceRequest, val route: OrionIntentRoute,
        val reminder: OrionSpokenReminder?, val allowInference: Boolean)

    private class ReadTurn(val fingerprint: ByteArray, val acceptedAt: TimeMark, var work: ReadWork?) {
        var reply: List<VoiceCoreResponse>? = null
    }

    private class LiveQuestion(val id: UUID, val turn: ReadTurn) {
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
        val now = clock.wallNow()
        if (!request.isFinalTranscript || request.source != OrionVoiceSource.PUSH_TO_TALK ||
            request.text.length > OrionIntentRouter.MAX_INPUT_UTF8_BYTES ||
            request.capturedAt.isAfter(now.plusSeconds(5)) ||
            request.capturedAt.isBefore(now.minusMillis(QUEUE_TIMEOUT_MILLIS))) return OrionVoiceAdmission.REJECTED_INVALID
        if (!storageReadable()) return OrionVoiceAdmission.CORE_UNAVAILABLE
        expireReadTurns()
        val bytes = encode(request)
        return try {
            val id = request.requestId
            readTurns[id]?.let { previous ->
                return if (previous.fingerprint.contentEquals(fingerprint(bytes))) OrionVoiceAdmission.DUPLICATE_REQUEST
                    else OrionVoiceAdmission.REJECTED_INVALID
            }
            val route = router.route(OrionRoutingInput(request.text, id, request.correlationId, OrionRoutingSource.VOICE_FINAL, true))
            // Previously persisted voice turns keep the original replay and conflict semantics after upgrade.
            if (route is OrionIntentRoute.TaskCreation || db.ingress().findByKey(SCOPE, id.toString()) != null)
                return submitDurable(request, bytes)
            if (route is OrionIntentRoute.Rejected || route is OrionIntentRoute.AwaitingFinalInput)
                return OrionVoiceAdmission.REJECTED_INVALID
            if (readTurns.size >= MAX_READ_TURNS) return OrionVoiceAdmission.CORE_UNAVAILABLE

            val ready = readyForReads()
            val work = ReadWork(request, route, spokenReminder(request.sessionId, request.text), allowInference = ready)
            readTurns[id] = ReadTurn(fingerprint(bytes), timeSource.markNow(), work)
            if (ready) OrionVoiceAdmission.ACCEPTED else OrionVoiceAdmission.QUEUED_DURING_RECOVERY
        } finally { bytes.fill(0) }
    }

    private fun submitDurable(request: VoiceUtteranceRequest, bytes: ByteArray): OrionVoiceAdmission {
        val outcome = coordinator.submit(DurableIngressRequest(request.requestId, SCOPE, request.requestId, SOURCE, TYPE, 1,
            Sensitivity.SENSITIVE, false, null, bytes, QUEUE_TIMEOUT_MILLIS, Duration.ofDays(1).toMillis(),
            correlationId = request.correlationId))
        return when (outcome) {
            DurableIngressOutcome.QUEUED -> if (readyForReads()) OrionVoiceAdmission.ACCEPTED
                else OrionVoiceAdmission.QUEUED_DURING_RECOVERY
            DurableIngressOutcome.DEDUP -> OrionVoiceAdmission.DUPLICATE_REQUEST
            DurableIngressOutcome.IDEMPOTENCY_CONFLICT, DurableIngressOutcome.INGRESS_ID_CONFLICT -> OrionVoiceAdmission.REJECTED_INVALID
            else -> OrionVoiceAdmission.CORE_UNAVAILABLE
        }
    }

    @Synchronized fun drain() {
        coordinator.drain(25)
        expireReadTurns()
        for ((id, turn) in readTurns) {
            val work = turn.work ?: continue
            val live = barrier.whileOpen {
                if (!readyForReads() || !storageReadable()) return@whileOpen null
                if (work.allowInference && work.reminder == null && work.route is OrionIntentRoute.CognitiveCandidate &&
                    conversationScope != null && question == null) {
                    turn.work = null
                    LiveQuestion(id, turn).also { question = it }
                } else {
                    turn.reply = try {
                        checkNotNull(replyToRead(id, work.route, work.request.sessionId, work.reminder))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        listOf(terminal(id, OrionVoiceTerminalState.FAILED, error = OrionVoiceErrorCode.EXECUTION_FAILED))
                    }
                    turn.work = null
                    null
                }
            }
            if (live != null) launchQuestion(live, work.request)
        }
    }

    private fun launchQuestion(live: LiveQuestion, request: VoiceUtteranceRequest) {
        live.job = checkNotNull(conversationScope).launch {
            val remaining = ORION_VOICE_REPLY_TIMEOUT_MILLIS - live.turn.acceptedAt.elapsedNow().inWholeMilliseconds
            var answer: String? = null
            var state = OrionVoiceTerminalState.SUCCEEDED
            try {
                answer = withTimeout(remaining) { answerQuestion(request.text, request.correlationId) }
            } catch (_: TimeoutCancellationException) {
                state = OrionVoiceTerminalState.TIMED_OUT
            } catch (cancelled: CancellationException) {
                state = OrionVoiceTerminalState.CANCELLED
                throw cancelled
            } catch (_: Exception) {
                // The local inference port has no authority to change the request or run a Skill.
            } finally {
                complete(live, state, answer)
            }
        }
    }

    @Synchronized fun closeConversation() {
        for ((id, turn) in readTurns) {
            if (turn.reply == null) stopRead(id, turn, OrionVoiceTerminalState.CANCELLED)
        }
    }

    @Synchronized private fun complete(live: LiveQuestion, state: OrionVoiceTerminalState, answer: String?) {
        if (question !== live) return
        question = null
        live.turn.reply = if (state == OrionVoiceTerminalState.SUCCEEDED)
            success(live.id, answer ?: "O Cortex está temporariamente indisponível. Tente novamente.", false)
        else listOf(terminal(live.id, state))
    }

    @Synchronized fun cancel(id: UUID): Boolean {
        expireReadTurns()
        readTurns[id]?.let { turn ->
            if (turn.reply != null) return false
            stopRead(id, turn, OrionVoiceTerminalState.CANCELLED)
            return true
        }
        val row = db.ingress().findByKey(SCOPE, id.toString()) ?: return false
        if (row.status != DurableIngressStatus.QUEUED) return false
        return db.ingress().cancelQueuedVoiceRequest(row.ingressId, row.version, clock.wallNow().toEpochMilli()) == 1
    }

    @Synchronized fun responses(id: UUID): List<VoiceCoreResponse> {
        expireReadTurns()
        readTurns[id]?.let { return it.reply ?: pending(id) }
        val row = db.ingress().findByKey(SCOPE, id.toString()) ?: return emptyList()
        return when (row.status) {
            DurableIngressStatus.CONSUMED -> results[id] ?: recoverResponse(id, row.correlationId)
            DurableIngressStatus.REJECTED, DurableIngressStatus.FAILED -> listOf(
                if (row.lastErrorCode == "VOICE_CANCELLED") terminal(id, OrionVoiceTerminalState.CANCELLED)
                else terminal(id, OrionVoiceTerminalState.FAILED, error = OrionVoiceErrorCode.INVALID_REQUEST),
            )
            DurableIngressStatus.EXPIRED -> listOf(terminal(id, OrionVoiceTerminalState.TIMED_OUT))
            else -> pending(id)
        }
    }

    private fun storageReadable() = coordinator.admit(IngressClass.READ_ONLY_SAFE) == IngressAdmission.READ_ALLOWED

    private fun readyForReads() = barrier.isOpen() && readiness().isStartupBarrierOpen

    private fun expireReadTurns() {
        for ((id, turn) in readTurns) {
            val timeout = if (turn.work != null) QUEUE_TIMEOUT_MILLIS else ORION_VOICE_REPLY_TIMEOUT_MILLIS
            if (turn.reply == null && turn.acceptedAt.elapsedNow().inWholeMilliseconds >= timeout) {
                stopRead(id, turn, OrionVoiceTerminalState.TIMED_OUT)
            }
        }
        readTurns.entries.removeAll { (_, turn) ->
            turn.acceptedAt.elapsedNow().inWholeMilliseconds >= ORION_VOICE_REPLY_TIMEOUT_MILLIS + 30_000
        }
    }

    private fun stopRead(id: UUID, turn: ReadTurn, state: OrionVoiceTerminalState) {
        turn.work = null
        turn.reply = listOf(terminal(id, state))
        val live = question?.takeIf { it.id == id } ?: return
        question = null
        live.job?.cancel()
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
        val route = router.route(input)
        val reply = if (route is OrionIntentRoute.TaskCreation && reminder == null) {
            val prepared = prepareTask(route.intent.arguments)
            success(id, if (prepared) TASK_REVIEW_TEXT else "Abra o O.R.I.O.N. e tente novamente.", prepared)
        } else {
            replyToRead(id, route, decoded.sessionId, reminder) ?: return LocalIngressDisposition.REJECTED
        }
        remember(id, reply)
        return LocalIngressDisposition.CONSUMED
    }

    private fun replyToRead(id: UUID, route: OrionIntentRoute, sessionId: UUID, reminder: OrionSpokenReminder?): List<VoiceCoreResponse>? {
        val text = if (reminder != null) {
            if (transcriptPreview?.publishReminder(sessionId, reminder) == true) "Confira o lembrete na tela."
            else "Abra Voz e repita o pedido de lembrete."
        } else when (route) {
            is OrionIntentRoute.BatteryQuery -> battery()?.let { "Bateria em $it por cento." } ?: "Bateria indisponível."
            is OrionIntentRoute.TaskList -> "Você tem ${taskCount()} tarefas abertas."
            is OrionIntentRoute.Clarification -> "Repita o pedido com o título da tarefa."
            is OrionIntentRoute.CognitiveCandidate -> if (conversationScope == null)
                "Use voz para criar ou listar tarefas e consultar a bateria."
                else "Repita a pergunta para o Cortex responder."
            else -> return null
        }
        return success(id, text, false)
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

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

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
        private const val QUEUE_TIMEOUT_MILLIS = 90_000L
        private const val MAX_READ_TURNS = 64
        private const val TASK_REVIEW_TEXT = "Revise e confirme o pedido na tela Tarefas."
    }
}
