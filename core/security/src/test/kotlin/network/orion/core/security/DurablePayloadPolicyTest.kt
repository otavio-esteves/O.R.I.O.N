package network.orion.core.security

import network.orion.core.model.Sensitivity
import org.junit.Assert.*
import org.junit.Test

class DurablePayloadPolicyTest {
    @Test fun `classified references retain encryption requirement`() {
        for (sensitivity in Sensitivity.entries) {
            for (referenceSufficient in listOf(false, true)) {
                val result = DurablePayloadPolicy.requirementsFor(sensitivity, referenceSufficient)
                assertEquals(referenceSufficient, result.referenceOnly)
                assertEquals(sensitivity == Sensitivity.SENSITIVE || sensitivity == Sensitivity.SECRET,
                    result.requiresVersionedEncryption)
            }
        }
    }
}
