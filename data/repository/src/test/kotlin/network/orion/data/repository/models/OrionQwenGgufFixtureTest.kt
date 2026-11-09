package network.orion.data.repository.models

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Excluded unless -PorionModelFixture is explicit; this fixture is not bundled or hardware-qualified. */
class OrionQwenGgufFixtureTest {
    @Test fun pinnedRealQwenArtifactPassesBoundedStructuralValidation() {
        val file = File(checkNotNull(System.getProperty("orionModelFixture")) { "Explicit fixture path required." })
        assertTrue("Requested model fixture is missing.", file.isFile)
        assertEquals(491400032L, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (input.read(bytes) >= 0) { }
        }
        assertEquals("74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            digest.digest().joinToString("") { "%02x".format(it) })
        val metadata = OrionGgufValidator().inspect(file)
        assertNotNull("Pinned Qwen fixture failed structural validation.", metadata)
        assertEquals("qwen2", metadata!!.architecture)
        assertTrue(metadata.contextLimit > 0)
        assertTrue(metadata.tensorCount > 0)
        assertTrue(metadata.tensorTypes.contains(OrionGgufTensorType.Q4_K))
        assertTrue(metadata.tensorTypes.contains(OrionGgufTensorType.Q6_K))
        assertTrue(metadata.tensorTypes.contains(OrionGgufTensorType.Q5_0))
    }
}
