package network.orion.ai.api

import java.util.UUID

/** MAIN owns terminality. Capacity never evicts a tombstone and accidentally reexecutes an old ID. */
class OrionCortexStreamLedger(private val capacity: Int = OrionCortexBounds.MAX_REQUESTS) {
    private class Request {
        var next = 0L
        var chars = 0
        var status = OrionCortexRequestStatus.QUEUED
        val pending = sortedMapOf<Long, OrionCortexChunk>()
    }
    private val requests = linkedMapOf<UUID, Request>()
    init { require(capacity in 1..OrionCortexBounds.MAX_REQUESTS) }

    @Synchronized fun register(id: UUID): Boolean {
        if (id in requests || requests.size >= capacity) return false
        requests[id] = Request()
        return true
    }
    @Synchronized fun status(id: UUID) = requests[id]?.status
    @Synchronized fun markRunning(id: UUID) {
        requests[id]?.let { if (!it.status.terminal) it.status = OrionCortexRequestStatus.RUNNING }
    }
    @Synchronized fun accept(chunk: OrionCortexChunk): List<OrionCortexChunk> {
        val request = requests[chunk.requestId] ?: return emptyList()
        if (request.status.terminal || chunk.sequence < request.next) return emptyList()
        if (chunk.sequence - request.next > 4 || chunk.sequence in request.pending && request.pending[chunk.sequence] != chunk)
            return fail(chunk.requestId, request)
        request.pending[chunk.sequence] = chunk
        val accepted = mutableListOf<OrionCortexChunk>()
        while (!request.status.terminal) {
            val current = request.pending.remove(request.next) ?: break
            val added = current.textDelta?.length ?: 0
            if (request.chars > OrionCortexBounds.MAX_OUTPUT_CHARS - added ||
                current.sequence >= OrionCortexBounds.MAX_CHUNKS && current.terminal == null) {
                accepted += fail(chunk.requestId, request)
                break
            }
            request.chars += added
            request.next++
            request.status = current.terminal ?: OrionCortexRequestStatus.RUNNING
            accepted += current
        }
        if (request.status.terminal) request.pending.clear()
        return accepted
    }
    @Synchronized fun terminate(id: UUID, status: OrionCortexRequestStatus): List<OrionCortexChunk> {
        require(status.terminal)
        val request = requests[id] ?: return emptyList()
        if (request.status.terminal) return emptyList()
        request.pending.clear()
        request.status = status
        return listOf(OrionCortexChunk(id, request.next, terminal = status))
    }
    @Synchronized fun terminateAll(status: OrionCortexRequestStatus): List<OrionCortexChunk> =
        requests.keys.flatMap { terminate(it, status) }
    private fun fail(id: UUID, request: Request): List<OrionCortexChunk> {
        request.status = OrionCortexRequestStatus.FAILED
        request.pending.clear()
        return listOf(OrionCortexChunk(id, request.next, terminal = OrionCortexRequestStatus.FAILED))
    }
}
