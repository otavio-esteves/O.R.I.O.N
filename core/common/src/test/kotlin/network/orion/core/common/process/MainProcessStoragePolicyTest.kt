package network.orion.core.common.process

import org.junit.Assert.assertThrows
import org.junit.Test

class MainProcessStoragePolicyTest {
    @Test fun `only unlocked main in credential storage can initialize storage`() {
        for (role in OrionProcessRole.entries) {
            for (unlocked in listOf(false, true)) {
                for (deviceProtected in listOf(false, true)) {
                    if (role == OrionProcessRole.MAIN && unlocked && !deviceProtected) {
                        MainProcessStoragePolicy.requireAccess(role, unlocked, deviceProtected)
                    } else {
                        assertThrows(IllegalStateException::class.java) {
                            MainProcessStoragePolicy.requireAccess(role, unlocked, deviceProtected)
                        }
                    }
                }
            }
        }
    }
}
