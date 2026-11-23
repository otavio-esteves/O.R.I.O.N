package network.orion.voice.ipc

import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

enum class OrionVoiceProcess(val suffix: String) {
    CONTROL(":voice"), SESSION(":voice_session"),
}

data class OrionVoiceProcessInstance(val pid: Int, val epoch: Long)

sealed interface OrionVoicePresence {
    data object Idle : OrionVoicePresence
    data class Active(val instance: OrionVoiceProcessInstance) : OrionVoicePresence
}

/** Locks hold only ephemeral technical ownership. The kernel releases them on process death. */
object OrionVoiceResources {
    private val held = mutableSetOf<String>()

    fun track(context: Context, role: OrionVoiceProcess, instance: OrionVoiceProcessInstance): Closeable? {
        val path = file(context, role.name) ?: return null
        val lease = open(path) as? Owned ?: return null
        return try {
            val marker = ByteBuffer.allocate(MARKER_BYTES).putInt(instance.pid).putLong(instance.epoch)
            marker.flip()
            while (marker.hasRemaining()) lease.channel.write(marker)
            lease
        } catch (_: Exception) {
            lease.close()
            null
        }
    }

    /** Only MAIN calls this. A busy lease without a complete marker is ambiguous. */
    fun presence(context: Context, role: OrionVoiceProcess): OrionVoicePresence? {
        val path = file(context, role.name) ?: return null
        return when (val attempt = open(path)) {
            is Owned -> attempt.use { OrionVoicePresence.Idle }
            Missing -> null
            Busy -> try {
                // The lease holder is in a different process. Closing this descriptor
                // cannot release that process's advisory lock.
                if (synchronized(held) { path.path in held }) return null
                DataInputStream(path.inputStream()).use { stream ->
                    val pid = stream.readInt()
                    val epoch = stream.readLong()
                    if (pid <= 0 || epoch == 0L) null else OrionVoicePresence.Active(OrionVoiceProcessInstance(pid, epoch))
                }
            } catch (_: Exception) { null }
        }
    }

    private fun file(context: Context, name: String): File? = try {
        val directory = File(context.createDeviceProtectedStorageContext().filesDir, "orion_resource_leases")
        if (directory.isDirectory || directory.mkdirs() || directory.isDirectory) File(directory, name) else null
    } catch (_: RuntimeException) { null }

    private sealed interface Attempt
    private data object Busy : Attempt
    private data object Missing : Attempt

    private class Owned(val channel: FileChannel, private val lock: FileLock, private val path: String) :
        Attempt, Closeable {
        private var closed = false

        override fun close() = synchronized(held) {
            if (closed) return@synchronized
            closed = true
            try {
                runCatching { lock.release() }
                runCatching { channel.close() }
            } finally {
                held.remove(path)
            }
            Unit
        }
    }

    private fun open(file: File): Attempt = synchronized(held) {
        // Closing another channel to the same file can release this process's lock.
        if (file.path in held) return@synchronized Busy
        try {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (failure: Exception) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                channel.close()
                Busy
            } else {
                held.add(file.path)
                Owned(channel, lock, file.path)
            }
        } catch (_: Exception) { Missing }
    }

    const val MEMORY_PROTOCOL = 1
    const val MEMORY_ACTION = "network.orion.orion_voice_memory"
    const val MEMORY_REQUEST = "orion_memory_request"
    const val MEMORY_CALLBACK = "orion_memory_callback"
    const val MEMORY_NONCE = "orion_memory_nonce"
    const val MEMORY_MAJOR = "orion_memory_major"
    const val MEMORY_ROLE = "orion_memory_role"
    private const val MARKER_BYTES = 12
}
