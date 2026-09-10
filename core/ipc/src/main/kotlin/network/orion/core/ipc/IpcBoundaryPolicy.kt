package network.orion.core.ipc

/**
 * Positive schema version carried by each evolvable IPC DTO.
 *
 * This wrapper neither serializes a DTO nor makes two schema versions compatible.
 * DTO-specific compatibility rules remain explicit at each future boundary.
 */
@JvmInline
value class IpcSchemaVersion(val value: Int) {
    init {
        require(value > 0) { "IPC schema version must be positive." }
    }
}

/** Transport class selected from the encoded inline payload size. */
enum class IpcPayloadTransport {
    INLINE,
    EXTERNAL_REQUIRED,
}

/**
 * Conservative Binder payload policy expressed in encoded bytes.
 *
 * The caller must measure the complete encoded inline payload, including envelope
 * overhead. A result of [IpcPayloadTransport.EXTERNAL_REQUIRED] requires a future
 * validated large-payload path; it does not create one or permit chunking implicitly.
 */
object InlineIpcPayloadPolicy {
    const val MAX_INLINE_IPC_PAYLOAD_BYTES: Int = 256 * 1024

    fun transportFor(encodedSizeBytes: Int): IpcPayloadTransport {
        require(encodedSizeBytes >= 0) { "Encoded IPC payload size must be nonnegative." }
        return if (encodedSizeBytes <= MAX_INLINE_IPC_PAYLOAD_BYTES) {
            IpcPayloadTransport.INLINE
        } else {
            IpcPayloadTransport.EXTERNAL_REQUIRED
        }
    }
}
