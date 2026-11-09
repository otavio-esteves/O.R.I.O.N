package network.orion.ai.llama

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Boundary smoke cases; real model generation and :ai ownership require service qualification. */
@RunWith(AndroidJUnit4::class)
class OrionLlamaBoundaryTest {
    @Test fun emptyRuntimeUnloadsAndClosesIdempotently() {
        val runtime = OrionLlamaRuntime()
        runtime.cancel()
        runtime.unload()
        runtime.unload()
        runtime.close()
        runtime.close()
        runtime.cancel()
        assertThrows(IllegalStateException::class.java) { runtime.unload() }
        OrionLlamaRuntime().use { it.unload() }
    }

    @Test fun corruptPrivateModelNeverLoads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("orion_corrupt_model_", ".gguf", context.cacheDir)
        try {
            file.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0, 0, 0, 0))
            OrionLlamaRuntime().use { runtime ->
                assertFalse(runtime.validateModel(file.absolutePath))
                assertThrows(IllegalStateException::class.java) { runtime.load(file.absolutePath, 512, 1) }
                runtime.unload()
            }
        } finally { file.delete() }
    }

    @Test fun generationWithoutLoadedModelFailsWithoutCallback() {
        var called = false
        OrionLlamaRuntime().use { runtime ->
            assertThrows(IllegalStateException::class.java) {
                runtime.generate("synthetic input", 8, 0f) { called = true; true }
            }
        }
        assertFalse(called)
    }

    @Test fun limitsAndClosedRuntimeAreRejectedBeforeNativeWork() {
        OrionLlamaRuntime().use { runtime ->
            assertThrows(IllegalArgumentException::class.java) { runtime.load("relative.gguf", 512, 1) }
            assertThrows(IllegalArgumentException::class.java) { runtime.load("/synthetic.gguf", 0, 1) }
            assertThrows(IllegalArgumentException::class.java) {
                runtime.generate("synthetic", 1, Float.NaN) { true }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runtime.generate("é".repeat(OrionLlamaRuntime.MAX_PROMPT_BYTES), 1, 0f) { true }
            }
        }
    }
}
