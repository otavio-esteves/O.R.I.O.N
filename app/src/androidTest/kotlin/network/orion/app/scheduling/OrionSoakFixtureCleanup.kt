package network.orion.app.scheduling

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import network.orion.core.actions.ActionStatus
import network.orion.data.database.OrionDatabase
import network.orion.scheduler.alarms.AndroidOrionAlarmBackend
import network.orion.scheduler.alarms.OrionAlarmToken
import network.orion.scheduler.alarms.OrionReminderAlarms
import org.json.JSONObject

/** Test-only ownership marker precedes the first synthetic write, including partial preparation. */
internal fun writeRuntimeFixtureMetadata(file: File, metadata: JSONObject) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(metadata.toString().toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (failure: Throwable) {
        atomic.failWrite(stream)
        throw failure
    }
}

/** Exact owned fixture only. No preparation, confirmation, retry or delivery assertion. */
internal fun cleanupSoakRuntimeReminder(context: Context, db: OrionDatabase, fixture: UUID) {
    val id = UUID.nameUUIDFromBytes("$fixture/runtime-reminder".toByteArray(Charsets.UTF_8))
    val atomic = AtomicFile(File(context.filesDir, "orion_scheduled_runtime_$fixture.json"))
    val row = db.reminders().find(id.toString())
    if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
        check(row == null) { "A remaining reminder has no ownership marker." }
        return
    }
    val metadata = JSONObject(atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    require(metadata.getString("fixtureId") == fixture.toString() && metadata.getString("reminderId") == id.toString())
    val retainedAction = if (metadata.has("actionId")) UUID.fromString(metadata.getString("actionId")) else null
    val rowAction = row?.deliveryActionId?.let(UUID::fromString)
    require(retainedAction == null || rowAction == null || retainedAction == rowAction)
    val actionIds = db.openHelper.readableDatabase.query("""SELECT actionId, status FROM orion_action_requests
        WHERE skill = 'reminder.deliver' AND correlationId = ? LIMIT 101""", arrayOf(id.toString())).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                check(size < 100 && rows.getString(1) != "RUNNING") { "Fixture execution has not settled." }
                add(UUID.fromString(rows.getString(0)))
            }
        }
    }
    for (action in listOfNotNull(retainedAction, rowAction)) {
        require(db.actions().find(action.toString()) == null || action in actionIds)
    }
    val generation = if (metadata.has("generation")) metadata.getLong("generation") else row?.fireGeneration ?: 0
    val requestCode = if (metadata.has("requestCode")) metadata.getInt("requestCode") else row?.requestCode ?: 0
    require(row == null || row.fireGeneration == generation && row.requestCode == requestCode)
    // Keep the marker if any operation fails so the same exact cleanup can be retried.
    fun cancelArtifacts() {
        for (action in (actionIds + listOfNotNull(retainedAction, rowAction)).distinct())
            context.getSystemService(NotificationManager::class.java).cancel(action.toString(), 0)
        if (generation > 0) check(OrionReminderAlarms(AndroidOrionAlarmBackend(context,
            ComponentName(context, OrionReminderAlarmReceiver::class.java))).cancel(OrionAlarmToken(id, generation, requestCode)))
    }
    cancelArtifacts()
    db.runInTransaction {
        val sql = db.openHelper.writableDatabase
        // A concurrently claimed effect must settle before cleanup. Check under SQLite's writer.
        for (action in actionIds) check(db.actions().find(action.toString())?.status != ActionStatus.RUNNING)
        sql.query("SELECT count(*) FROM orion_action_requests WHERE skill = 'reminder.deliver' AND correlationId = ?",
            arrayOf(id.toString())).use { check(it.moveToFirst() && it.getInt(0) == actionIds.size) }
        sql.execSQL("DELETE FROM orion_ingress_envelopes WHERE correlationId = ?", arrayOf(id.toString()))
        sql.execSQL("DELETE FROM orion_domain_event_outbox WHERE aggregateId = ?", arrayOf(id.toString()))
        sql.execSQL("DELETE FROM orion_technical_events WHERE correlationId IN (?, ?)", arrayOf(fixture.toString(), id.toString()))
        for (action in actionIds) {
            sql.execSQL("DELETE FROM orion_technical_events WHERE correlationId = ?", arrayOf(action.toString()))
            sql.execSQL("DELETE FROM orion_action_audit WHERE actionId = ?", arrayOf(action.toString()))
            sql.execSQL("DELETE FROM orion_action_requests WHERE actionId = ? AND skill = 'reminder.deliver' AND correlationId = ?",
                arrayOf(action.toString(), id.toString()))
        }
        sql.execSQL("DELETE FROM orion_reminders WHERE reminderId = ?", arrayOf(id.toString()))
    }
    // Cover a completed delivery between the first cancellation and acquiring the writer.
    cancelArtifacts()
    atomic.delete()
    check(!atomic.baseFile.exists() && db.reminders().find(id.toString()) == null)
}
