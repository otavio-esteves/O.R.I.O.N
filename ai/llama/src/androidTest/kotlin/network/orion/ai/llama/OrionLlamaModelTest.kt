package network.orion.ai.llama

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import network.orion.core.resources.*
import network.orion.core.time.AndroidOrionClock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator fixture only. Synthetic resource values never qualify or activate production. */
@RunWith(AndroidJUnit4::class)
class OrionLlamaModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = AndroidOrionClock(context.contentResolver)
    private val model = InferenceModelProfile(MODEL_SHA256, 1024L * 1024 * 1024)
    private val governor = OrionMinimumResourceGovernor(clock, {
        OrionResourceObservation(clock.monotonicNow(), model, 100, 0, true,
            OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE,
            100L * 1024 * 1024, 100L * 1024 * 1024, 4L * 1024 * 1024 * 1024,
            emptyList(), 2, 512, 32)
    }, listOf(OrionInferenceResourcePlan(model, "ANDROID_EMULATOR_SYNTHETIC_TEST_ONLY", 2, 512, 32, 0,
        2L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024, 512L * 1024 * 1024, 20, 2)), 1000, 1000)

    private fun fixture(): File {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("orionModelPath")
        assumeTrue("Requires explicit private model fixture", name != null)
        check(args.getString("orionEnvironment") == "ANDROID_EMULATOR") {
            "An explicit model fixture requires declared emulator test environment."
        }
        val file = File(checkNotNull(name)).canonicalFile
        check(file.parentFile == context.filesDir.canonicalFile) { "Fixture must be in test app private files." }
        assertEquals(MODEL_SIZE, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(MODEL_SHA256, digest.digest().joinToString("") { "%02x".format(it) })
        return file
    }

    private fun approved() {
        val fresh = governor.evaluate()
        check(fresh.allowed && fresh.plan?.tier == "ANDROID_EMULATOR_SYNTHETIC_TEST_ONLY") {
            "Synthetic fixture governor denied test request."
        }
    }

    @Test fun realModelLoadsStreamsAndUnloadsWithFreshFixtureDecisions() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            assertTrue(runtime.validateModel(file.absolutePath))
            approved()
            assertTrue(runtime.load(file.absolutePath, 512, 2))
            var chunks = 0
            var bytes = 0
            approved()
            val result = runtime.generate("The capital of France is", 16, 0f) { piece ->
                chunks++
                bytes += piece.size
                true
            }
            assertTrue(result == OrionLlamaFinish.SUCCEEDED || result == OrionLlamaFinish.TOKEN_LIMIT)
            assertTrue(chunks > 0 && bytes > 0)
            runtime.unload()
            assertThrows(IllegalStateException::class.java) {
                runtime.generate("synthetic", 1, 0f) { true }
            }
        }
    }

    @Test fun realDecodeCancelsAfterFirstPieceAndRuntimeCanBeReused() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            approved()
            assertTrue(runtime.load(file.absolutePath, 512, 2))
            var chunks = 0
            approved()
            assertEquals(OrionLlamaFinish.CANCELLED,
                runtime.generate("Count from one to one hundred:", 32, 0f) { chunks++; false })
            assertEquals(1, chunks)
            runtime.cancel() // After completion this is a no-op.
            approved()
            val result = runtime.generate("Hello", 4, 0f) { true }
            assertTrue(result == OrionLlamaFinish.SUCCEEDED || result == OrionLlamaFinish.TOKEN_LIMIT)
            runtime.unload()
        }
    }

    @Test fun realLoadHonorsConcurrentCancellationAndLeavesNoLoadedContext() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            val started = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val outcome = AtomicReference<Boolean>()
            val failure = AtomicReference<Throwable>()
            approved()
            val worker = Thread {
                try {
                    started.countDown()
                    outcome.set(runtime.load(file.absolutePath, 512, 2))
                } catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }
            worker.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            for (attempt in 0 until 3000) {
                runtime.cancel()
                if (completed.await(10, TimeUnit.MILLISECONDS)) break
            }
            assertTrue("Load cancellation must finish within 30 seconds", completed.count == 0L)
            worker.join(1000)
            failure.get()?.let { throw AssertionError("Load cancellation failed", it) }
            assertEquals(false, outcome.get())
            assertThrows(IllegalStateException::class.java) {
                runtime.generate("synthetic", 1, 0f) { true }
            }
            runtime.unload()
        }
    }

    companion object {
        const val MODEL_SIZE = 491400032L
        const val MODEL_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    }
}
