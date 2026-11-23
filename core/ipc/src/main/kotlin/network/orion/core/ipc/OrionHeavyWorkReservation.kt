package network.orion.core.ipc

import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** The owning native worker releases the reservation after unload or process death. */
object OrionHeavyWorkReservation {
    private val arbiter = Any()
    private var held = false

    fun tryAcquire(context: Context): Closeable? = synchronized(arbiter) {
        if (held) return@synchronized null
        try {
            val path = File(context.createDeviceProtectedStorageContext().filesDir, "orion_native_work.lock")
            val channel = FileChannel.open(path.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (failure: Exception) {
                channel.close()
                throw failure
            }
            if (lock == null) {
                channel.close()
                return@synchronized null
            }
            held = true
            object : Closeable {
                private var closed = false

                override fun close() = synchronized(arbiter) {
                    if (closed) return@synchronized
                    closed = true
                    try {
                        runCatching { lock.release() }
                        runCatching { channel.close() }
                    } finally {
                        held = false
                    }
                    Unit
                }
            }
        } catch (_: Exception) { null }
    }
}
