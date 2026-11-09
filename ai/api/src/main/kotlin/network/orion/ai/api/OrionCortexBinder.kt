package network.orion.ai.api

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.util.UUID
import network.orion.core.ipc.InlineIpcPayloadPolicy
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant

/** Explicit versioned Binder wire. Models use descriptors; arbitrary large inline prompts are refused. */
object OrionCortexWire {
    const val DESCRIPTOR = "network.orion.ai.api.OrionCortex.v1"
    const val CALLBACK = "network.orion.ai.api.OrionCortexCallback.v1"
    const val PROTOCOL = IBinder.FIRST_CALL_TRANSACTION
    const val LOAD = PROTOCOL + 1
    const val INFER = PROTOCOL + 2
    const val CANCEL = PROTOCOL + 3
    const val REQUEST_STATUS = PROTOCOL + 4
    const val STATUS = PROTOCOL + 5
    const val UNLOAD = PROTOCOL + 6
    const val HEALTH = PROTOCOL + 7
    const val CHUNK = IBinder.FIRST_CALL_TRANSACTION

    fun frame(parcel: Parcel, descriptor: String = DESCRIPTOR) {
        parcel.writeInterfaceToken(descriptor)
        parcel.writeInt(1); parcel.writeInt(0); parcel.writeInt(1)
    }
    fun checkFrame(parcel: Parcel, descriptor: String = DESCRIPTOR) {
        require(Binder.getCallingUid() == Process.myUid()) { "Internal Cortex caller required." }
        require(parcel.dataSize() <= InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
        parcel.enforceInterface(descriptor)
        require(parcel.readInt() == 1 && parcel.readInt() >= 0 && parcel.readInt() == 1)
    }
    fun exhausted(parcel: Parcel) { require(parcel.dataAvail() == 0) }
    fun requestId(parcel: Parcel): UUID = UUID.fromString(requireNotNull(parcel.readString()))
    fun writeApproval(parcel: Parcel, approval: OrionCortexApproval) {
        parcel.writeInt(approval.contextSize); parcel.writeInt(approval.threads); parcel.writeInt(approval.maxTokens)
        parcel.writeInt(approval.sampledAt.bootSessionId?.bootCount ?: -1)
        parcel.writeLong(approval.sampledAt.elapsedRealtimeMillis); parcel.writeLong(approval.validForMillis)
        parcel.writeLong(approval.keepAliveMs)
    }
    fun readApproval(parcel: Parcel, modelId: String): OrionCortexApproval {
        val context = parcel.readInt(); val threads = parcel.readInt(); val tokens = parcel.readInt()
        val boot = BootSessionId(parcel.readInt()); val elapsed = parcel.readLong()
        return OrionCortexApproval(modelId, context, threads, tokens, MonotonicInstant(boot, elapsed),
            parcel.readLong(), parcel.readLong())
    }
    fun <T> call(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val request = Parcel.obtain(); val response = Parcel.obtain()
        try {
            frame(request); write(request)
            require(request.dataSize() <= InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
            if (!binder.transact(code, request, response, 0)) throw RemoteException("Cortex endpoint unavailable.")
            require(response.dataSize() <= InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
            response.readException()
            val result = read(response); exhausted(response)
            return result
        } finally { request.recycle(); response.recycle() }
    }
    fun chunk(binder: IBinder, value: OrionCortexChunk) {
        val parcel = Parcel.obtain()
        try {
            frame(parcel, CALLBACK)
            parcel.writeString(value.requestId.toString()); parcel.writeLong(value.sequence)
            parcel.writeString(value.textDelta); parcel.writeString(value.terminal?.name)
            require(parcel.dataSize() <= InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
            if (!binder.transact(CHUNK, parcel, null, IBinder.FLAG_ONEWAY)) throw RemoteException("Cortex callback unavailable.")
        } finally { parcel.recycle() }
    }
}

/** No arbitrary Bundle/Java object deserialization at this boundary. */
class OrionCortexCallbackBinder(private val receive: (OrionCortexChunk) -> Unit) : Binder() {
    init { attachInterface(null, OrionCortexWire.CALLBACK) }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(OrionCortexWire.CALLBACK); return true }
        if (code != OrionCortexWire.CHUNK) return false
        return try {
            OrionCortexWire.checkFrame(data, OrionCortexWire.CALLBACK)
            val id = OrionCortexWire.requestId(data); val sequence = data.readLong()
            val delta = data.readString(); val terminal = data.readString()?.let(OrionCortexRequestStatus::valueOf)
            OrionCortexWire.exhausted(data)
            receive(OrionCortexChunk(id, sequence, delta, terminal)); true
        } catch (_: Exception) { false }
    }
}

class OrionCortexRemote(private val binder: IBinder) {
    fun getProtocolInfo(): Pair<Int, Int> = OrionCortexWire.call(binder, OrionCortexWire.PROTOCOL) {
        val major = it.readInt(); val minor = it.readInt(); require(major == 1 && minor >= 0); major to minOf(minor, 0)
    }
    fun loadModel(id: UUID, modelId: String, model: ParcelFileDescriptor, modelSize: Long,
        timeoutMillis: Long, approval: OrionCortexApproval, callback: IBinder): OrionCortexAdmission =
        OrionCortexWire.call(binder, OrionCortexWire.LOAD, {
            it.writeString(id.toString()); it.writeString(modelId); it.writeTypedObject(model, 0); it.writeLong(modelSize)
            it.writeLong(timeoutMillis); OrionCortexWire.writeApproval(it, approval); it.writeStrongBinder(callback)
        }) { OrionCortexAdmission.valueOf(requireNotNull(it.readString())) }
    fun startInference(id: UUID, modelId: String, prompt: String, maxTokens: Int,
        temperature: Float, timeoutMillis: Long, priority: Int, approval: OrionCortexApproval, callback: IBinder): OrionCortexAdmission =
        OrionCortexWire.call(binder, OrionCortexWire.INFER, {
            it.writeString(id.toString()); it.writeString(modelId); it.writeString(prompt)
            it.writeInt(maxTokens); it.writeFloat(temperature)
            it.writeLong(timeoutMillis); it.writeInt(priority); OrionCortexWire.writeApproval(it, approval); it.writeStrongBinder(callback)
        }) { OrionCortexAdmission.valueOf(requireNotNull(it.readString())) }
    fun cancelInference(id: UUID) = OrionCortexWire.call(binder, OrionCortexWire.CANCEL,
        { it.writeString(id.toString()) }) { it.readInt() == 1 }
    fun getRequestStatus(id: UUID): OrionCortexRequestStatus? = OrionCortexWire.call(binder, OrionCortexWire.REQUEST_STATUS,
        { it.writeString(id.toString()) }) { it.readString()?.let(OrionCortexRequestStatus::valueOf) }
    fun getStatus(): OrionCortexHealth = OrionCortexWire.call(binder, OrionCortexWire.STATUS) {
        OrionCortexHealth.valueOf(requireNotNull(it.readString()))
    }
    fun unloadModel() = OrionCortexWire.call(binder, OrionCortexWire.UNLOAD) { it.readInt() == 1 }
    fun healthCheck() = OrionCortexWire.call(binder, OrionCortexWire.HEALTH) { it.readInt() == 1 }
}
