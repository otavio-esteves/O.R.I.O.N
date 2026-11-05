package network.orion.data.database

import androidx.room.Dao
import androidx.room.Query

/** Read-only MAIN metadata projection. Mutable writes remain in the owned adapter. */
@Dao
abstract class OrionMemoryDao {
    @Query("SELECT * FROM orion_memories WHERE origin = 'USER_DECLARED' AND userConfirmed = 1 AND sensitivity = 'PERSONAL' AND (:after IS NULL OR memoryId > :after) ORDER BY memoryId LIMIT :limit")
    abstract fun page(after: String?, limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM orion_memories WHERE memoryId = :id")
    abstract fun find(id: String): MemoryEntity?
    @Query("SELECT (SELECT count(*) FROM orion_memories) + (SELECT count(*) FROM orion_audit WHERE eventCode = 'MEMORY_FORGOTTEN')")
    abstract fun retainedMemoryCount(): Int

    @Query("SELECT count(*) FROM orion_audit WHERE aggregateId = :id AND eventCode = 'MEMORY_FORGOTTEN'")
    abstract fun forgotten(id: String): Int

    @Query("SELECT count(*) FROM orion_audit WHERE aggregateId = :id AND aggregateVersion = :version AND eventCode = 'MEMORY_FORGOTTEN'")
    abstract fun deletionCommitted(id: String, version: Long): Int

    @Query("SELECT * FROM orion_action_requests WHERE skill = 'memory.create' AND idempotencyScope = 'memory.create' ORDER BY actionId LIMIT 10001")
    abstract fun creationActions(): List<ActionRequestEntity>

    @Query("SELECT * FROM orion_ingress_envelopes WHERE idempotencyScope = 'orion_ui_memory_v1' ORDER BY ingressId LIMIT 10001")
    abstract fun memoryIngress(): List<IngressEnvelopeEntity>

    /** Terminal payload retention erasure only; state/hash/key/audit material stays unchanged. */
    @Query("""UPDATE orion_action_requests SET argumentsEnvelope = X'', targetEnvelope = X''
        WHERE actionId = :id AND version = :version AND skill = 'memory.create'
        AND idempotencyScope = 'memory.create' AND status IN ('SUCCEEDED','FAILED','CANCELLED')""")
    abstract fun eraseTerminalCreate(id: String, version: Long): Int

    @Query("""UPDATE orion_ingress_envelopes SET payloadEnvelope = X''
        WHERE ingressId = :id AND version = :version AND idempotencyScope = 'orion_ui_memory_v1'
        AND status IN ('CONSUMED','REJECTED','EXPIRED','FAILED')""")
    abstract fun eraseTerminalIngress(id: String, version: Long): Int
}
