package network.orion.data.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.UserManager
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole

/** Technical format marker, preserved independently of the Room schema version. */
@Entity(tableName = "orion_storage_metadata")
data class OrionStorageMetadata(@PrimaryKey val id: Int, val formatVersion: Int)

@Dao
interface OrionStorageMetadataDao {
    @Query("SELECT formatVersion FROM orion_storage_metadata WHERE id = 1")
    fun formatVersion(): Int?

    @Query("SELECT count(*) FROM orion_storage_metadata")
    fun rowCount(): Int
}

@Database(entities = [OrionStorageMetadata::class, ActionRequestEntity::class, ActionAuditEntity::class,
    TaskEntity::class, TimerEntity::class, ReminderEntity::class, MemoryEntity::class, MemoryClaimEntity::class,
    AuditEntity::class, TechnicalEventEntity::class, DomainEventOutboxEntity::class,
    EventConsumerReceiptEntity::class, IngressEnvelopeEntity::class,
    OrionReminderSeriesEntity::class, OrionReminderOccurrenceEntity::class, ModelRegistryEntity::class,
    OrionMemoryIndexRow::class, OrionMemoryIndexState::class, OrionSentinelConsent::class,
    OrionSentinelSuggestion::class, OrionSentinelFeedback::class], version = 13, exportSchema = true)
abstract class OrionDatabase : RoomDatabase() {
    abstract fun models(): OrionModelRegistryDao
    abstract fun metadata(): OrionStorageMetadataDao
    abstract fun actions(): OrionActionDao
    abstract fun timers(): OrionTimerDao
    abstract fun memories(): OrionMemoryDao
    abstract fun memoryIndex(): OrionMemoryIndexDao
    abstract fun sentinel(): OrionSentinelDao
    abstract fun tasks(): OrionTaskDao
    abstract fun reminders(): OrionReminderDao
    abstract fun reminderSchedules(): OrionReminderScheduleDao
    abstract fun reminderSeries(): OrionReminderSeriesDao
    abstract fun records(): OrionRecordDao
    abstract fun events(): OrionEventDao
    abstract fun ingress(): OrionIngressDao
    abstract fun retention(): OrionRetentionDao

    /** Performs disk I/O: call outside the UI thread. Failure never deletes the database. */
    fun verifyStructure() = runInTransaction {
        check(metadata().formatVersion() == 1 && metadata().rowCount() == 1) { "Storage metadata invariant failed." }
        // Android SQLite 3.44 validates FTS4 using a scratch write. A transaction pins
        // this check to the writable connection instead of a pooled read-only connection.
        openHelper.writableDatabase.query("PRAGMA quick_check(1)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Storage integrity check failed." }
        }
    }
}

object OrionDatabaseFactory {
    fun open(context: Context): OrionDatabase {
        val app = context.applicationContext
        MainProcessStoragePolicy.requireAccess(
            OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
            app.getSystemService(UserManager::class.java).isUserUnlocked,
            context.isDeviceProtectedStorage,
        )
        return Room.databaseBuilder(app, OrionDatabase::class.java, "orion.db")
            .openHelperFactory(OrionPreservingOpenHelperFactory)
            .addMigrations(*OrionDatabaseMigrations.ALL)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO orion_storage_metadata (id, formatVersion) VALUES (1, 1)")
                    db.execSQL(ORION_SCRUB_INELIGIBLE_MEMORY_POSTINGS)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.beginTransaction()
                    try {
                        enableOrionSecureDelete(db)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            })
            // Deliberately no destructive fallback or main-thread query permission.
            .build().also { database ->
                try {
                    database.verifyStructure()
                } catch (failure: Exception) {
                    database.close()
                    throw failure
                }
            }
    }
}

private object OrionPreservingOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                // The default callback deletes the database. Propagate the failure so the Core stays gated.
                throw SQLiteDatabaseCorruptException("O.R.I.O.N. database is corrupt.")
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build(),
        )
    }
}
