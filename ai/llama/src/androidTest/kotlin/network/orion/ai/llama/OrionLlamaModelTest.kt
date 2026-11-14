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

    @Test fun realLocalMetricsPreserveGreedyPiecesAndCountNativeTokensSeparately() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            approved(); assertTrue(runtime.load(file.absolutePath, 512, 2))
            val prompt = "The capital of France is"
            val normalPieces = mutableListOf<ByteArray>()
            approved()
            val normal = runtime.generate(prompt, 4, 0f) { normalPieces.add(it.copyOf()); true }
            val capture = OrionLlamaGenerationCapture()
            val measuredPieces = mutableListOf<ByteArray>()
            approved()
            val measured = runtime.generateObserved(prompt, 4, 0f, capture) { measuredPieces.add(it.copyOf()); true }
            assertEquals(normal, measured)
            assertEquals(normalPieces.size, measuredPieces.size)
            normalPieces.indices.forEach { assertArrayEquals(normalPieces[it], measuredPieces[it]) }
            assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, capture.observation.state)
            val metrics = checkNotNull(capture.observation.metrics)
            assertEquals(measured, metrics.finish)
            assertTrue(checkNotNull(metrics.promptTokens) > 0)
            assertNotEquals(prompt.toByteArray(Charsets.UTF_8).size, metrics.promptTokens)
            assertEquals(2, metrics.tokenizerCalls)
            assertEquals(metrics.promptTokens, metrics.prefill.tokensInSuccessfulCalls)
            assertEquals(measuredPieces.size, metrics.callbacksReturnedNormally)
            assertEquals(metrics.callbacksReturnedNormally + metrics.eogSamples, metrics.sampledTokens)
            assertTrue(checkNotNull(metrics.tokenizeApiWallNanos) >= 0)
            assertTrue(checkNotNull(metrics.prefill.apiWallNanos) >= 0)
            runtime.unload()
        }
    }

    @Test fun realOneTokenAndCallbackCancellationDoNotEvaluateFinalOutputToken() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            approved(); assertTrue(runtime.load(file.absolutePath, 512, 2))
            val prompt = "The capital of France is"
            val one = OrionLlamaGenerationCapture()
            var pieces = 0
            approved()
            assertEquals(OrionLlamaFinish.TOKEN_LIMIT,
                runtime.generateObserved(prompt, 1, 0f, one) { pieces++; true })
            val single = checkNotNull(one.observation.metrics)
            assertEquals(1, pieces); assertEquals(1, single.sampledTokens)
            assertEquals(1, single.callbacksReturnedNormally)
            assertEquals(0, single.decode.tokensInSuccessfulCalls)
            assertNull(single.decode.apiWallNanos)
            val cancel = OrionLlamaGenerationCapture()
            approved()
            assertEquals(OrionLlamaFinish.CANCELLED,
                runtime.generateObserved(prompt, 8, 0f, cancel) { false })
            val cancelled = checkNotNull(cancel.observation.metrics)
            assertEquals(1, cancelled.callbacksAttempted)
            assertEquals(1, cancelled.callbacksReturnedNormally)
            assertEquals(0, cancelled.decode.tokensSubmitted)
            assertEquals(OrionLlamaFinish.TOKEN_LIMIT, one.observation.metrics?.finish)
            runtime.unload()
        }
    }

    @Test fun realCallbackExceptionIdentitySurvivesAndMetricsStayUnavailableUntilFreshInvocation() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            approved(); assertTrue(runtime.load(file.absolutePath, 512, 2))
            val capture = OrionLlamaGenerationCapture()
            val original = IllegalStateException("Scripted callback failure")
            approved()
            val thrown = assertThrows(IllegalStateException::class.java) {
                runtime.generateObserved("The capital of France is", 8, 0f, capture) { throw original }
            }
            assertSame(original, thrown)
            assertEquals(OrionLlamaObservationState.UNAVAILABLE_GENERATION, capture.observation.state)
            assertNull(capture.observation.metrics)
            val fresh = OrionLlamaGenerationCapture()
            approved()
            assertEquals(OrionLlamaFinish.TOKEN_LIMIT,
                runtime.generateObserved("The capital of France is", 1, 0f, fresh) { true })
            assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, fresh.observation.state)
            assertNull(capture.observation.metrics)
            runtime.unload()
        }
    }

    @Test fun realEogSamplesAreNotOutputCallbacksWhenFixtureReachesEog() {
        val file = fixture()
        OrionLlamaRuntime().use { runtime ->
            approved(); assertTrue(runtime.load(file.absolutePath, 512, 2))
            val capture = OrionLlamaGenerationCapture()
            approved()
            val finish = runtime.generateObserved(
                "<|im_start|>user\nSay only Hi.<|im_end|>\n<|im_start|>assistant\nHi", 32, 0f, capture) { true }
            val metrics = checkNotNull(capture.observation.metrics)
            runtime.unload()
            assumeTrue("This fixture run did not reach EOG; no real EOG evidence", finish == OrionLlamaFinish.SUCCEEDED)
            assertEquals(1, metrics.eogSamples)
            assertEquals(metrics.callbacksReturnedNormally + 1, metrics.sampledTokens)
            assertEquals(metrics.callbacksReturnedNormally, metrics.decode.tokensInSuccessfulCalls)
        }
    }

    @Test fun realCloseDuringObservedCallbackRetainsNativeLifetimeUntilReturnWithoutHandleGetter() {
        val file = fixture()
        val runtime = OrionLlamaRuntime()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val capture = OrionLlamaGenerationCapture()
        val outcome = AtomicReference<OrionLlamaFinish>()
        val failure = AtomicReference<Throwable>()
        try {
            approved(); assertTrue(runtime.load(file.absolutePath, 512, 2))
            approved()
            val worker = Thread {
                try {
                    outcome.set(runtime.generateObserved("The capital of France is", 8, 0f, capture) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        true
                    })
                } catch (error: Throwable) { failure.set(error) }
                  finally { completed.countDown() }
            }
            worker.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            runtime.close()
            release.countDown()
            assertTrue(completed.await(30, TimeUnit.SECONDS))
            worker.join(1000)
            failure.get()?.let { throw AssertionError("Observed fixture did not finish", it) }
            assertEquals(OrionLlamaFinish.CANCELLED, outcome.get())
            assertEquals(OrionLlamaObservationState.LOCAL_JNI_RETURNED, capture.observation.state)
            assertEquals(1, capture.observation.metrics?.callbacksReturnedNormally)
            assertEquals(0, capture.observation.metrics?.decode?.tokensSubmitted)
            OrionLlamaRuntime().use { it.unload() } // Previous shared Runtime really unwound.
        } finally {
            release.countDown(); runtime.close()
            completed.await(5, TimeUnit.SECONDS)
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
