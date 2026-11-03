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
                        val denial = assertThrows(OrionStorageAccessDeniedException::class.java) {
                            MainProcessStoragePolicy.requireAccess(role, unlocked, deviceProtected)
                        }
                        org.junit.Assert.assertEquals(
                            if (role != OrionProcessRole.MAIN) StorageAccessDeniedReason.NON_MAIN_PROCESS
                            else StorageAccessDeniedReason.CREDENTIAL_STORAGE_UNAVAILABLE,
                            denial.reason,
                        )
                    }
                }
            }
        }
    }
}
