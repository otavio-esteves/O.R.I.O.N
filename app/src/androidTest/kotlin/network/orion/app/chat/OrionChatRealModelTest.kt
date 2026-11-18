package network.orion.app.chat

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import network.orion.ai.api.*
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.app.ai.OrionCortexClient
import network.orion.app.tasks.OrionTaskAdmissionOwner
import network.orion.core.orchestrator.chat.*
import network.orion.core.queries.tasks.GetTasks
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.*
import network.orion.feature.chat.ChatScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Required explicit pinned fixture. ANDROID_EMULATOR + synthetic resource observations only.
 * Uses real importer, native :ai Binder/client, production chat adapter/controller/UI and real task Core.
 * Never installs a production profile or treats this as hardware/resource calibration.
 */
class OrionChatRealModelTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test(timeout = 600_000) fun nativeLocalChatProposalConfirmationAndAiDeathSurviveEndToEnd() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("orionModelPath")
        assumeTrue("Explicit pinned emulator model required", path != null)
        check(args.getString("orionEnvironment") == "ANDROID_EMULATOR")
        val activity = compose.activity
        val app = activity.applicationContext
        val runtime = checkNotNull((activity.application as OrionApplication).mainRuntime)
        compose.waitUntil(30_000) { runtime.taskStartupBarrier.isOpen() && activity.hasWindowFocus() }
        val source = File(checkNotNull(path)).canonicalFile
        check(source.parentFile == app.filesDir.canonicalFile && source.length() == 491400032L)
        val clock = AndroidOrionClock(app.contentResolver)
        val id = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "orion_chat_fixture_$id").also { check(it.mkdirs()) }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val dbName = "orion_chat_fixture_$id.db"
        val db = Room.databaseBuilder(app, OrionDatabase::class.java, dbName).addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) { db.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)") }
        }).build()
        val decisions = AtomicInteger(); val streamed = AtomicInteger(); val killNext = AtomicBoolean()
        val pauseNextDelta = AtomicBoolean(); val deltaPaused = CountDownLatch(1); val releaseDelta = CountDownLatch(1)
        // Emulator-only calibration: four provisioned vCPUs, bounded 1024-token context.
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(QWEN_MODEL_ID, 1024L * 1024 * 1024),
            "ANDROID_EMULATOR_SYNTHETIC_CHAT_TEST_ONLY", 4, 1024, 128, 0,
            2L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024, 512L * 1024 * 1024, 20, 2)
        val guard = OrionCortexRestartGuard(clock, listOf(plan), 1000, 1000)
        val client = OrionCortexClient(app, clock, resources = { modelId, context, threads, tokens ->
            check(modelId == QWEN_MODEL_ID); decisions.incrementAndGet()
            OrionMinimumResourceGovernor(clock, {
                OrionResourceObservation(clock.monotonicNow(), plan.model, 100, 0, true,
                    OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
                    100L * 1024 * 1024, 100L * 1024 * 1024, 4L * 1024 * 1024 * 1024,
                    emptyList(), threads, context, tokens)
            }, listOf(plan), 1000, 1000).evaluate()
        }, restart = { guard.evaluate(OrionAiExitHistory(clock.monotonicNow(), emptyList())) })
        var controller: OrionChatController? = null
        var channel: OrionTaskAdmissionOwner.UiChannel? = null
        try {
            val importer = OrionModelImportCoordinator.open(isolated, db, clock,
                maximumModelBytes = 512L * 1024 * 1024, reserveBytes = 64L * 1024 * 1024)
            val row = runBlocking(Dispatchers.IO) {
                check(importer.importModel(OrionExpectedModelFile(QWEN_MODEL_ID, source.length()), source::inputStream) is OrionModelImportResult.Ready)
                checkNotNull(db.models().find(QWEN_MODEL_ID))
            }
            val requestIds = mutableSetOf<String>()
            val backend = object : OrionChatInferenceRuntime {
                override fun configuration() = qwenChatModel(plan)
                override fun newRequestId() = client.newRequestId().also { check(requestIds.add(it)) }
                override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel, onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatTerminal =
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        checkNotNull(importer.openReadOnly(row.modelId)).use { fd ->
                            client.runInference(row, fd, request, model.maxContextTokens, model.threads) { chunks ->
                                streamed.addAndGet(chunks.count { !it.textDelta.isNullOrEmpty() })
                                onChunks(chunks)
                                if (chunks.any { !it.textDelta.isNullOrEmpty() } && pauseNextDelta.compareAndSet(true, false)) {
                                    // Bounded observation barrier makes a real native chunk cancellable by an actual UI gesture.
                                    // No generated output is replaced and the native cancellation still uses the existing client.
                                    deltaPaused.countDown(); check(releaseDelta.await(10, TimeUnit.SECONDS))
                                }
                                if (chunks.any { !it.textDelta.isNullOrEmpty() } && killNext.compareAndSet(true, false)) {
                                    check(chunks.none { it.isFinal })
                                    val target = app.getSystemService(ActivityManager::class.java).runningAppProcesses.single {
                                        it.processName == app.packageName + ":ai" && it.uid == Process.myUid()
                                    }
                                    check(target.pid != Process.myPid()); Process.killProcess(target.pid)
                                }
                            }.terminal.chatTerminal()
                        }
                    }
            }
            compose.runOnIdle {
                check(activity.hasWindowFocus())
                channel = runtime.taskAdmissions.openUiChannel().also { it.onResumed(true); it.onWindowFocus(activity.hasWindowFocus()) }
                controller = OrionChatController(activity.lifecycleScope, runtime.taskGateway(checkNotNull(channel)), runtime.deviceGateway,
                    OrionChatCortexAdapter(clock) { backend })
                activity.setContent { MaterialTheme { ChatScreen(checkNotNull(controller)) } }
            }
            fun scroll(label: String) { compose.onNodeWithTag("orion_chat").performScrollToNode(hasText(label)) }
            fun checkpoint(stage: String) {
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("orion_chat_checkpoint", stage)
                    putString("orion_chat_cortex_state", checkNotNull(controller).state.value.cortex)
                    putInt("orion_chat_governor_decisions", decisions.get())
                    putInt("orion_chat_native_deltas", streamed.get())
                    putInt("orion_chat_issued_ids", requestIds.size)
                })
            }
            fun send(text: String) {
                compose.waitUntil(15_000) { !checkNotNull(controller).state.value.busy }
                val oldUserIds = checkNotNull(controller).state.value.turns.filter { it.user }.map { it.id }.toSet()
                scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").assertIsEnabled().performTextReplacement(text)
                scroll("Enviar")
                // Exercise the button's accessibility action. IME viewport animations can move
                // its touch coordinates between scroll and injection on a slow emulator.
                compose.onNodeWithText("Enviar").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
                // Observe admission before completion: a fast query can finish between frames,
                // and an old idle snapshot must never count as the new click's completion.
                try {
                    compose.waitUntil(15_000) { checkNotNull(controller).state.value.turns.any { it.user && it.text == text && it.id !in oldUserIds } }
                } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                    val snapshot = checkNotNull(controller).state.value
                    throw AssertionError("Send not admitted: busy=${snapshot.busy}, cortex=${snapshot.cortex}, notice=${snapshot.notice}, turns=${snapshot.turns.size}, inputLength=${text.length}", failure)
                }
                compose.waitUntil(150_000) { !checkNotNull(controller).state.value.busy }
                checkpoint("REQUEST_COMPLETED")
            }
            val mainPid = Process.myPid()
            send("Diga apenas olá.")
            assertTrue("Actual native deltas required", streamed.get() > 0)
            assertTrue("Validated local answer required", checkNotNull(controller).state.value.turns.any { !it.user && it.proposal == null })
            assertFalse(checkNotNull(controller).state.value.canRetry)
            assertTrue("Greeting must answer the request, not copy a schema placeholder",
                checkNotNull(controller).state.value.turns.last().text.contains("olá", ignoreCase = true))
            pauseNextDelta.set(true)
            scroll("Mensagem"); compose.onNodeWithTag("orion_chat_input").performTextInput("Diga olá novamente.")
            scroll("Enviar"); compose.onNodeWithText("Enviar").performClick()
            compose.waitUntil(150_000) { deltaPaused.count == 0L }
            scroll("Cancelar"); compose.onNodeWithText("Cancelar").performClick(); releaseDelta.countDown()
            compose.waitUntil(15_000) { !checkNotNull(controller).state.value.busy }
            assertTrue(checkNotNull(controller).state.value.canRetry)
            assertEquals("", checkNotNull(controller).state.value.draft)
            scroll("Tentar inferência novamente"); compose.onNodeWithText("Tentar inferência novamente").performClick()
            compose.waitUntil(150_000) { !checkNotNull(controller).state.value.busy }
            checkpoint("EXPLICIT_RETRY_COMPLETED")
            assertFalse("Retry Cortex state: " + checkNotNull(controller).state.value.cortex, checkNotNull(controller).state.value.canRetry)
            assertFalse(checkNotNull(controller).state.value.turns.last().user)
            val title = "Comprar pão"
            send("Crie uma tarefa com o título Comprar pão.")
            val proposal = checkNotNull(controller).state.value.turns.last().proposal
            assertNotNull("Real model must produce a validated task proposal", proposal)
            assertEquals("The proposed title must match the natural-language request", title, checkNotNull(proposal).title)
            val taskId = checkNotNull(proposal).taskId
            assertFalse(runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items.any { it.taskId == taskId } })
            scroll("Preparar tarefa sugerida"); compose.onNodeWithText("Preparar tarefa sugerida").performClick()
            compose.waitUntil(15_000) { !checkNotNull(controller).state.value.busy && checkNotNull(controller).state.value.pending.any { it.title == title } }
            assertFalse(runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items.any { it.taskId == taskId } })
            scroll("Confirmar criação"); compose.onNodeWithText("Confirmar criação").performClick()
            compose.waitUntil(15_000) { runBlocking(Dispatchers.IO) { runtime.taskKernel!!.query.get(GetTasks()).items.any { it.taskId == taskId } } }
            val before = checkNotNull(controller).state.value.turns.size
            killNext.set(true)
            send("Conte uma história longa sobre o espaço.")
            assertFalse(killNext.get())
            assertEquals("Cortex interrompido", checkNotNull(controller).state.value.cortex)
            assertTrue(checkNotNull(controller).state.value.canRetry)
            assertEquals(before + 1, checkNotNull(controller).state.value.turns.size)
            assertEquals(mainPid, Process.myPid())
            assertTrue(runBlocking(Dispatchers.IO) {
                runtime.taskKernel!!.query.get(GetTasks()).items.any { it.taskId == taskId && it.title == title }
            })
            send("minhas tarefas")
            assertTrue(checkNotNull(controller).state.value.turns.last().text.contains(title))
            assertTrue(decisions.get() >= 10)
            assertEquals(5, requestIds.size)
            runBlocking(Dispatchers.IO) { db.verifyStructure() }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("orion_environment", "ANDROID_EMULATOR")
                putInt("orion_chat_native_delta_count", streamed.get())
                putInt("orion_chat_governor_decisions", decisions.get())
                putInt("orion_chat_fresh_request_ids", requestIds.size)
                putString("orion_chat_profile", plan.tier)
                putInt("orion_chat_context_cap", plan.maxContext)
                putInt("orion_chat_threads", plan.maxThreads)
                putBoolean("orion_chat_main_survived_ai_kill", true)
            })
        } finally {
            releaseDelta.countDown()
            compose.runOnIdle { controller?.close(); channel?.close() }
            client.close(); db.close(); app.deleteDatabase(dbName); root.deleteRecursively()
        }
    }
}
