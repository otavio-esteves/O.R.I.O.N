package network.orion.ai.api

import android.os.Bundle
import android.os.Parcel
import android.os.SharedMemory
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import network.orion.core.ipc.InlineIpcPayloadPolicy

/** Explicit primitive-only schema; content is carried through read-only descriptors. */
object OrionCortexWire {
    const val SCHEMA = 1
    const val MAX_SPEC_BYTES = 16 * 1024
    const val MAX_INPUT_BYTES = 1024 * 1024
    const val MAX_CONTEXT = 32 * 1024
    const val MAX_TOKENS = 4096
    const val MAX_THREADS = 16

    fun reply(status: String): Bundle = Bundle().apply {
        putInt("schemaVersion", SCHEMA)
        putString("status", status)
    }

    /** Validate actual Parcel size before access; callers still validate operation-specific fields. */
    fun requireEnvelope(bundle: Bundle, keys: Set<String>) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            require(parcel.dataSize() <= MAX_SPEC_BYTES &&
                parcel.dataSize() <= InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
        } finally { parcel.recycle() }
        require(bundle.keySet().all { it in keys })
        require(int(bundle, "schemaVersion") == SCHEMA)
    }

    @Suppress("DEPRECATION")
    fun text(bundle: Bundle, name: String, maximum: Int = 128): String {
        val value = bundle.get(name)
        require(value is String && value.length in 1..maximum && value.none(Char::isISOControl))
        return value
    }

    @Suppress("DEPRECATION")
    fun optionalText(bundle: Bundle, name: String): String? {
        val value = bundle.get(name)
        require(value == null || value is String)
        return value as String?
    }

    @Suppress("DEPRECATION")
    fun int(bundle: Bundle, name: String): Int {
        val value = bundle.get(name)
        require(value is Int)
        return value
    }

    @Suppress("DEPRECATION")
    fun long(bundle: Bundle, name: String): Long {
        val value = bundle.get(name)
        require(value is Long)
        return value
    }

    @Suppress("DEPRECATION")
    fun boolean(bundle: Bundle, name: String): Boolean {
        val value = bundle.get(name)
        require(value is Boolean)
        return value
    }

    @Suppress("DEPRECATION")
    fun temperature(bundle: Bundle): Float {
        val value = bundle.get("temperature")
        require(value is Float && value.isFinite() && value in 0f..2f)
        return value
    }

    /** Pipes, sockets, writable descriptors and inconsistent lengths are rejected. No implicit seek. */
    fun requireReadOnlyFile(descriptor: ParcelFileDescriptor, expectedSize: Long, maxSize: Long) {
        require(expectedSize in 1..maxSize)
        val fd = descriptor.fileDescriptor
        val stat = Os.fstat(fd)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == expectedSize)
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        require(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
    }

    /** SharedMemory is bounded, protected against future writable mappings and strictly decoded. */
    fun readUtf8(memory: SharedMemory, expectedSize: Long): String {
        require(expectedSize in 1..MAX_INPUT_BYTES.toLong() && memory.size.toLong() == expectedSize)
        require(memory.setProtect(OsConstants.PROT_READ))
        val mapped = memory.mapReadOnly()
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(mapped).toString()
        } finally { SharedMemory.unmap(mapped) }
    }

    /** Positional reads do not share a mutable file offset with the sender's descriptor. */
    fun readUtf8(descriptor: ParcelFileDescriptor, expectedSize: Long): String {
        requireReadOnlyFile(descriptor, expectedSize, MAX_INPUT_BYTES.toLong())
        val bytes = ByteArrayOutputStream(expectedSize.toInt())
        val buffer = ByteArray(16 * 1024)
        var offset = 0L
        while (offset < expectedSize) {
            val length = minOf(buffer.size.toLong(), expectedSize - offset).toInt()
            val count = Os.pread(descriptor.fileDescriptor, buffer, 0, length, offset)
            if (count <= 0) throw IOException("Inference input truncated.")
            bytes.write(buffer, 0, count)
            offset += count
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
