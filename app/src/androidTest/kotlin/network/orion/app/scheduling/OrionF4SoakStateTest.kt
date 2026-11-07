package network.orion.app.scheduling

import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.core.commands.tasks.CreateTask
import network.orion.core.commands.tasks.CreateTaskResult
import network.orion.core.commands.timers.CreateTimer
import network.orion.core.commands.timers.CreateTimerResult
import network.orion.core.orchestrator.tasks.TaskOperationOutcome
import network.orion.core.orchestrator.timers.TimerOperationOutcome
import network.orion.core.queries.timers.GetTimerStatus
import network.orion.core.queries.timers.TimerState
import network.orion.core.time.AndroidOrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.database.OrionDatabaseFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Planned instrumentation checkpoints restart MAIN; host must label them, never call them passive samples. */
@RunWith(AndroidJUnit4::class)
class OrionF4SoakStateTest {
    private val app: OrionApplication get() = ApplicationProvider.getApplicationContext()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun write(file: File, json: JSONObject) {
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun unresolvedDigest(db: OrionDatabase): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Hash technical identities and ciphertext, never export content or raw uncertain IDs.
        db.openHelper.readableDatabase.query("""SELECT actionId, status, attemptCount,
            normalizedPayloadHash, argumentsEnvelope, targetEnvelope FROM orion_action_requests
            WHERE status IN ('UNKNOWN','MANUAL_REVIEW') ORDER BY actionId""").use { rows ->
            var count = 0
            while (rows.moveToNext()) {
                check(++count <= 10_000)
                for (column in 0..3) {
                    digest.update(rows.getString(column).toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
                }
                digest.update(rows.getBlob(4)); digest.update(rows.getBlob(5))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun counts(db: OrionDatabase): JSONObject = JSONObject().apply {
        val allowed = setOf("PENDING", "NEEDS_CONFIRMATION", "CONFIRMED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED",
            "UNKNOWN", "MANUAL_REVIEW", "QUEUED", "PROCESSING", "CONSUMED", "REJECTED", "EXPIRED", "DELIVERED", "DEAD_LETTER")
        for (table in listOf("orion_action_requests", "orion_domain_event_outbox", "orion_ingress_envelopes")) {
            val states = JSONObject()
            db.openHelper.readableDatabase.query("SELECT status, count(*) FROM $table GROUP BY status").use { rows ->
                while (rows.moveToNext()) {
                    val state = rows.getString(0)
                    check(state in allowed)
                    states.put(state, rows.getLong(1))
                }
            }
            put(table, states)
        }
    }

    @Test fun explicitlyRequestedSoakCheckpoint() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("orion_soak_mode") ?: return@runBlocking
        require(mode in setOf("prepare", "snapshot", "verify", "cleanup"))
        val raw = checkNotNull(args.getString("orion_soak_fixture"))
        val fixture = UUID.fromString(raw).also { require(it.toString() == raw) }
        val taskId = UUID.nameUUIDFromBytes("$fixture/soak-task".toByteArray())
        val timerId = UUID.nameUUIDFromBytes("$fixture/soak-timer".toByteArray())
        val metadataFile = File(app.filesDir, "orion_soak_state_$fixture.json")
        val snapshotFile = File(app.filesDir, "orion_soak_snapshot_$fixture.json")
        val runtime = checkNotNull(app.mainRuntime)
        withTimeout(30_000) { runtime.health.first { it.core.readiness.isStartupBarrierOpen } }
        val db = OrionDatabaseFactory.open(app)
        try {
            if (mode == "prepare") {
                assertTrue(app.getSystemService(PowerManager::class.java).isInteractive &&
                    !app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                require(!metadataFile.exists() && db.tasks().find(taskId.toString()) == null && db.timers().find(timerId.toString()) == null)
                // Persist ownership before fixture writes so cleanup remains possible after interruption.
                val metadata = JSONObject().put("fixtureId", raw).put("taskId", taskId.toString()).put("timerId", timerId.toString())
                    .put("unresolvedDigest", unresolvedDigest(db))
                write(metadataFile, metadata)
                val taskChannel = runtime.taskAdmissions.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
                val timerChannel = runtime.timerAdmissions.openUiChannel().apply { onResumed(true); onWindowFocus(true) }
                try {
                    val tasks = runtime.taskGateway(taskChannel)
                    val timers = runtime.timerGateway(timerChannel)
                    val taskTitle = "O.R.I.O.N. soak task $fixture"
                    val timerTitle = "O.R.I.O.N. soak timer $fixture"
                    assertEquals(CreateTaskResult.QUEUED, tasks.create(CreateTask(taskId, taskTitle, taskId, fixture)))
                    val task = tasks.pending().single { it.title == taskTitle }
                    val taskSubmission = tasks.confirm(task.actionId, task.version)
                    assertTrue(taskSubmission in setOf(TaskOperationOutcome.APPLIED, TaskOperationOutcome.QUEUED))
                    awaitOriginalSoakAction(db, task.actionId, { tasks.processPending() })
                    assertEquals(CreateTimerResult.QUEUED, timers.create(CreateTimer(timerId, timerTitle, 60_000, timerId, fixture)))
                    val timer = timers.pending().single { it.title == timerTitle }
                    val timerSubmission = timers.confirm(timer.actionId, timer.version)
                    assertTrue(timerSubmission in setOf(TimerOperationOutcome.APPLIED, TimerOperationOutcome.QUEUED))
                    awaitOriginalSoakAction(db, timer.actionId, { timers.processPending() })
                    metadata.put("taskEnvelopeSha256", hash(checkNotNull(db.tasks().find(taskId.toString())).contentEnvelope))
                        .put("timerEnvelopeSha256", hash(checkNotNull(db.timers().find(timerId.toString())).contentEnvelope))
                    write(metadataFile, metadata)
                } finally { taskChannel.close(); timerChannel.close() }
            } else if (mode == "cleanup") {
                if (!metadataFile.isFile) {
                    require(db.tasks().find(taskId.toString()) == null && db.timers().find(timerId.toString()) == null)
                    require(!snapshotFile.exists())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("orion_soak_state", "CLEANUP") })
                    return@runBlocking
                }
                val metadata = JSONObject(metadataFile.readText())
                require(metadata.getString("fixtureId") == raw && metadata.getString("taskId") == taskId.toString() &&
                    metadata.getString("timerId") == timerId.toString())
                db.runInTransaction {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("DELETE FROM orion_action_audit WHERE actionId IN (SELECT actionId FROM orion_action_requests WHERE correlationId = ?)", arrayOf(raw))
                    sql.execSQL("DELETE FROM orion_action_requests WHERE correlationId = ?", arrayOf(raw))
                    sql.execSQL("DELETE FROM orion_ingress_envelopes WHERE correlationId = ?", arrayOf(raw))
                    sql.execSQL("DELETE FROM orion_domain_event_outbox WHERE aggregateId IN (?, ?)", arrayOf(taskId.toString(), timerId.toString()))
                    sql.execSQL("DELETE FROM orion_technical_events WHERE correlationId = ?", arrayOf(raw))
                    sql.execSQL("DELETE FROM orion_tasks WHERE taskId = ?", arrayOf(taskId.toString()))
                    sql.execSQL("DELETE FROM orion_timers WHERE timerId = ?", arrayOf(timerId.toString()))
                }
                android.util.AtomicFile(metadataFile).delete(); android.util.AtomicFile(snapshotFile).delete()
                check(!metadataFile.exists() && !snapshotFile.exists())
            } else {
                val metadata = JSONObject(metadataFile.readText())
                require(metadata.getString("fixtureId") == raw)
                assertEquals(metadata.getString("taskEnvelopeSha256"), hash(checkNotNull(db.tasks().find(taskId.toString())).contentEnvelope))
                assertEquals(metadata.getString("timerEnvelopeSha256"), hash(checkNotNull(db.timers().find(timerId.toString())).contentEnvelope))
                assertEquals(metadata.getString("unresolvedDigest"), unresolvedDigest(db))
                val clock = AndroidOrionClock(app.contentResolver)
                write(snapshotFile, JSONObject().put("fixtureId", raw).put("mode", mode)
                    .put("instrumentationCheckpoint", true).put("observedAtMillis", clock.wallNow().toEpochMilli())
                    .put("coreReadiness", runtime.health.value.core.readiness.name).put("queues", counts(db))
                    .put("unresolvedDigest", unresolvedDigest(db)))
                if (mode == "verify") {
                    assertEquals(TimerState.ELAPSED, runtime.timerKernel?.query?.get(GetTimerStatus(timerId))?.state)
                    db.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use {
                        assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("orion_soak_state", mode.uppercase())
            })
        } finally { db.close() }
    }
}
