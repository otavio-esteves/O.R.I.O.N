package network.orion.app.ai

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.app.chat.OrionChatCortexAdapter
import network.orion.app.chat.OrionChatInferenceRuntime
import network.orion.app.chat.QWEN_MODEL_ID
import network.orion.app.chat.chatTerminal
import network.orion.app.chat.qwenChatModel
import network.orion.app.resources.OrionAndroidResourceObserver
import network.orion.app.resources.OrionCortexMemoryTelemetry
import network.orion.ai.api.OrionInferenceChunk
import network.orion.ai.api.OrionInferenceRequest
import network.orion.ai.api.OrionInferenceStatus
import network.orion.core.orchestrator.chat.OrionChatContext
import network.orion.core.orchestrator.chat.OrionChatModel
import network.orion.core.orchestrator.chat.OrionChatResponse
import network.orion.core.orchestrator.chat.OrionChatTerminal
import network.orion.core.orchestrator.chat.OrionStructuredResponse
import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionCortexRestartGuard
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionMinimumResourceGovernor
import network.orion.core.resources.OrionResourceObservation
import network.orion.core.resources.OrionResourceBlock
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionExpectedModelFile
import network.orion.data.repository.models.OrionModelImportCoordinator
import network.orion.data.repository.models.OrionModelImportResult
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One foreground diagnostic on the measured S21 build, with a separate app UID and actual Android observations. */
class OrionS21CortexSmokeTest {
    @Test fun validatedPortugueseReplyFromPrivateCortexProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as OrionApplication
        assumeTrue(app.packageName == LAB_PACKAGE && InstrumentationRegistry.getArguments().getString("orionS21Smoke") == "true")
        check(Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-G991B" && Build.VERSION.SDK_INT == 35)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
        check(fingerprint.joinToString("") { "%02x".format(it) } == S21_BUILD_SHA256)
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && Os.sysconf(OsConstants._SC_PAGESIZE) == 4_096L)
        val source = File(app.filesDir, "orion_s21_qwen.gguf").canonicalFile
        check(source.parentFile == app.filesDir.canonicalFile && source.length() == MODEL_BYTES)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking(Dispatchers.IO) {
                val runtime = checkNotNull(app.mainRuntime)
                withTimeout(30_000) {
                    while (!runtime.health.value.core.readiness.isStartupBarrierOpen) delay(100)
                }
                scenario.onActivity { check(it.hasWindowFocus()) { "Open the test app with the S21 unlocked." } }
                val clock = AndroidOrionClock(app.contentResolver)
                val appName = app.packageName
                val aiName = "$appName:ai"
                val main = mapOf(appName to Process.myPid())
                fun processes(): Map<String, Int>? {
                    val running = app.getSystemService(ActivityManager::class.java)
                        ?.runningAppProcesses?.filter { it.uid == Process.myUid() } ?: return null
                    if (running.size !in 1..2 || running.any { it.pid <= 0 || appName !in it.pkgList }) return null
                    val byName = running.associate { it.processName to it.pid }
                    return byName.takeIf { it.size == running.size && it[appName] == Process.myPid() &&
                        (it == main || it.keys == setOf(appName, aiName) && it[aiName] != Process.myPid()) }
                }

                val telemetry = OrionCortexMemoryTelemetry(appName, clock, MAX_AGE_MS)
                lateinit var client: OrionCortexClient
                val observer = OrionAndroidResourceObserver(app, clock, auxiliaryMemory = memory@{
                    val before = processes() ?: return@memory null
                    val aiPid = before[aiName] ?: return@memory null
                    val receipt = client.currentMemoryTelemetry() ?: return@memory null
                    if (processes() != before) return@memory null
                    telemetry.read(receipt, aiPid, before.keys, true)
                }, maximumMemoryAgeMillis = MAX_AGE_MS)
                val model = checkNotNull(qwenChatModel(plan))
                check(processes() == main) { "The isolated test requires an idle app UID before binding Cortex." }
                val initial = observer.observe(null, 2, 1024, 32).observation
                check(initial.foreground == true && initial.powerSave == false &&
                    (initial.batteryPercent ?: 0) >= plan.minimumBatteryPercent &&
                    initial.thermalStatus == plan.maxThermalStatus) { "Foreground, battery, power or thermal conditions changed." }

                val work = File(app.cacheDir, "orion_s21_${UUID.randomUUID()}").also { check(it.mkdir()) }
                val isolated = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun getNoBackupFilesDir(): File = work
                }
                val database = Room.inMemoryDatabaseBuilder(app, OrionDatabase::class.java)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("INSERT INTO orion_storage_metadata(id,formatVersion) VALUES(1,1)")
                        }
                    }).build()
                var observation: OrionResourceObservation? = null
                val governor = OrionMinimumResourceGovernor(clock, { observation }, listOf(plan), MAX_AGE_MS, 60_000)
                val restartGuard = OrionCortexRestartGuard(clock, listOf(plan), MAX_AGE_MS, 60_000)
                var permits = 0
                var resourceBlock: OrionResourceBlock? = null
                var restartBlock: OrionRestartBlock? = null
                var inference: OrionInferenceStatus? = null
                var peakAiPss = 0L
                var peakAppPss = 0L
                client = OrionCortexClient(app, clock, resources = { id, context, threads, tokens ->
                    check(id == QWEN_MODEL_ID)
                    val snapshot = observer.observe(plan, threads, context, tokens).observation
                    observation = snapshot
                    peakAiPss = maxOf(peakAiPss, snapshot.processMemoryBytes ?: 0L)
                    peakAppPss = maxOf(peakAppPss, snapshot.aggregateAppMemoryBytes ?: 0L)
                    governor.evaluate().also { decision ->
                        resourceBlock = decision.block
                        if (decision.allowed) permits++
                    }
                }, restart = {
                    restartGuard.evaluate(if (processes() == null) null else observer.observe(null, 2, 1024, 32).aiExitHistory)
                        .also { restartBlock = it.block }
                })

                try {
                    val importer = OrionModelImportCoordinator.open(isolated, database, clock,
                        maximumModelBytes = MODEL_BYTES, reserveBytes = 512 * MIB)
                    check(importer.importModel(OrionExpectedModelFile(QWEN_MODEL_ID, MODEL_BYTES), source::inputStream)
                        is OrionModelImportResult.Ready)
                    val row = checkNotNull(database.models().find(QWEN_MODEL_ID))
                    check(profileMatchesReadyModel(plan, row, model.maxContextTokens, model.threads, model.reservedOutputTokens))
                    val adapter = OrionChatCortexAdapter(clock) {
                        object : OrionChatInferenceRuntime {
                            override fun configuration() = model
                            override fun newRequestId() = client.newRequestId()
                            override suspend fun run(request: OrionInferenceRequest, model: OrionChatModel,
                                onChunks: (List<OrionInferenceChunk>) -> Unit): OrionChatTerminal =
                                checkNotNull(importer.openReadOnly(row.modelId)).use { fd ->
                                    val status = client.runInference(row, fd, request, model.maxContextTokens, model.threads, onChunks)
                                    inference = status
                                    status.terminal.chatTerminal()
                                }
                        }
                    }
                    val input = UUID.randomUUID()
                    val correlation = UUID.randomUUID()
                    val prompt = checkNotNull(OrionChatContext.build("Diga apenas olá.", emptyList(), model)).prompt
                    val output = StringBuffer()
                    val terminal = adapter.generate(model, prompt, correlation.toString()) { output.append(it) }
                    check(terminal == OrionChatTerminal.SUCCEEDED) {
                        "Terminal=$terminal, failure=${inference?.failure}, restart=$restartBlock, " +
                            "resource=$resourceBlock, battery=${observation?.batteryPercent}, permits=$permits, " +
                            "aiPssBytes=${peakAiPss.takeIf { it > 0 }}, appPssBytes=${peakAppPss.takeIf { it > 0 }}, " +
                            "availableBytes=${observation?.systemAvailableMemoryBytes}"
                    }
                    val reply = OrionStructuredResponse.parse(output.toString(), input, correlation)
                    check(reply is OrionChatResponse.Message && reply.text.contains("olá", ignoreCase = true))
                    check(permits >= 2) { "Expected resource decisions before load and generation." }
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("environment", "PHYSICAL_DEVICE")
                        putString("qualification", "S21_DIAGNOSTIC_ONLY")
                        putInt("freshGovernorPermits", permits)
                        putLong("peakSampledAiPssBytes", peakAiPss)
                        putLong("peakSampledAppPssBytes", peakAppPss)
                        putBoolean("validatedReply", true)
                    })
                } finally {
                    client.close()
                    database.close()
                    work.deleteRecursively()
                }
            }
        }
    }

    private companion object {
        const val LAB_PACKAGE = "network.orion.cortexlab"
        const val S21_BUILD_SHA256 = "a91399143f406214bc34b0a0980205b4b231fa171d38841ea912f240144c6fab"
        const val MAX_AGE_MS = 1_000L
        const val MODEL_BYTES = 491_400_032L
        const val MIB = 1_048_576L

        // Diagnostic scope only. This build measured up to 545521 KiB PSS with a 1024-token
        // context, two threads and a 32-token output cap, at thermal status 0 and 14–16% battery.
        // The optimized APK measured 792570880 bytes of aggregate PSS before generation.
        // Its measured PSS plus a second model allowance rounds up to 1344 MiB.
        // The one-turn diagnostic uses the Core's existing critical-battery boundary (5%).
        val plan = OrionInferenceResourcePlan(InferenceModelProfile(QWEN_MODEL_ID, 544 * MIB),
            "S21_DIAGNOSTIC_ONLY", 2, 1024, 32, 0, 1_152 * MIB, 1_344 * MIB, 1_024 * MIB, 6, 0)
    }
}
