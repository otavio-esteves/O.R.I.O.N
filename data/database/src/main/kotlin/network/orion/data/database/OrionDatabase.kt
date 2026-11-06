package network.orion.data.database

import android.app.Application
import android.content.Context
import android.os.UserManager
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    EventConsumerReceiptEntity::class, IngressEnvelopeEntity::class], version = 4, exportSchema = true)
abstract class OrionDatabase : RoomDatabase() {
    abstract fun metadata(): OrionStorageMetadataDao
    abstract fun actions(): OrionActionDao
    abstract fun timers(): OrionTimerDao
    abstract fun memories(): OrionMemoryDao
    abstract fun tasks(): OrionTaskDao
    abstract fun reminders(): OrionReminderDao
    abstract fun records(): OrionRecordDao
    abstract fun events(): OrionEventDao
    abstract fun ingress(): OrionIngressDao
    abstract fun retention(): OrionRetentionDao

    /** Performs disk I/O: call outside the UI thread. Failure never deletes the database. */
    fun verifyStructure() {
        check(metadata().formatVersion() == 1 && metadata().rowCount() == 1) { "Storage metadata invariant failed." }
        openHelper.readableDatabase.query("PRAGMA quick_check(1)").use { cursor ->
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
            .addMigrations(OrionDatabaseMigrations.MIGRATION_1_2, OrionDatabaseMigrations.MIGRATION_2_3, OrionDatabaseMigrations.MIGRATION_3_4)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO orion_storage_metadata (id, formatVersion) VALUES (1, 1)")
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
