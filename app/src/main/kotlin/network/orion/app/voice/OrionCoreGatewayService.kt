package network.orion.app.voice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Process
import network.orion.app.OrionApplication
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.voice.ipc.IOrionVoiceCoreGateway

/** Version and readiness only. MAIN owns the snapshot and all business authority. */
class OrionCoreGatewayService : Service() {
    private val endpoint = object : IOrionVoiceCoreGateway.Stub() {
        override fun getProtocolVersion(): IntArray {
            enforceAppCaller()
            val local = OrionIpcProtocol.CURRENT
            return intArrayOf(local.protocolMajor, local.protocolMinor)
        }

        override fun getCoreReadiness(protocolMajor: Int, protocolMinor: Int): String? {
            enforceAppCaller()
            if (protocolMajor != OrionIpcProtocol.CURRENT.protocolMajor || protocolMinor < 0) return null
            return (application as OrionApplication).mainRuntime?.health?.value?.core?.readiness?.name
        }
    }

    override fun onBind(intent: Intent) = endpoint

    private fun enforceAppCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Invalid voice gateway caller.")
    }
}
