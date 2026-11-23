package network.orion.app.resources

import network.orion.voice.ipc.OrionVoiceProcessInstance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionVoiceMemoryCollectorTest {
    private val processName = "network.orion:voice"
    private val instance = OrionVoiceProcessInstance(42, 77)

    @Test fun onlyThePinnedProcessMaySupplyTheReceipt() {
        fun accepts(uid: Int = 10_021, pid: Int = 42, major: Int = 1,
            role: String = processName, epoch: Long = 77, mainPid: Int = 1) =
            validVoiceMemorySender(10_021, instance, processName, uid, pid, mainPid, major, role, epoch)

        assertTrue(accepts())
        assertFalse(accepts(uid = 10_022))
        assertFalse(accepts(pid = 43))
        assertFalse(accepts(mainPid = 42))
        assertFalse(accepts(major = 2))
        assertFalse(accepts(role = "network.orion:voice_session"))
        assertFalse(accepts(epoch = 78))
    }
}
