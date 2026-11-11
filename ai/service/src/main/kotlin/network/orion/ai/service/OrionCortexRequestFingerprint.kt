package network.orion.ai.service

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import network.orion.core.time.MonotonicDeadline

/** Bounded ephemeral digests only; never retain or log a second copy of prompt content. */
internal object OrionCortexRequestFingerprint {
    fun load(model: String, sha256: String, size: Long, context: Int, threads: Int,
        deadline: MonotonicDeadline): String = digest("load", deadline,
        listOf(model, sha256, size.toString(), context.toString(), threads.toString()))

    fun inference(model: String, input: String, size: Long, tokens: Int, temperature: Float,
        deadline: MonotonicDeadline): String = digest("inference", deadline,
        listOf(model, input, size.toString(), tokens.toString(),
            (if (temperature == 0f) 0f else temperature).toString()))

    private fun digest(operation: String, deadline: MonotonicDeadline, fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in listOf("orion_cortex_payload_v1", operation,
            deadline.start.bootSessionId?.bootCount.toString(), deadline.start.elapsedRealtimeMillis.toString(),
            (deadline.expiresElapsedRealtimeMillis - deadline.start.elapsedRealtimeMillis).toString()) + fields) {
            // Reject malformed UTF-16 rather than collapsing different strings to replacement bytes.
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(field))
            try { digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); digest.update(bytes) }
            finally { if (bytes.hasArray()) bytes.array().fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
