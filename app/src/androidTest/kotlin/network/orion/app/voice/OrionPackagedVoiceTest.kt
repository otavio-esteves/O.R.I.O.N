package network.orion.app.voice

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OrionPackagedVoiceTest {
    @Test fun packagedKokoroRendersArbitraryPortugueseWithoutNativeCallback() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("orionPackagedVoice") == "true")
        // Exercise the installed renderer with synthetic text. Reflection keeps it internal to the voice module.
        val synthesis = Class.forName("network.orion.voice.service.OrionSelectedVoiceKt")
        val renderer = synthesis.getDeclaredMethod(
            "renderOrionKokoro", Context::class.java, String::class.java,
        ).apply { isAccessible = true }
        val samples = checkNotNull(renderer.invoke(null, instrumentation.targetContext, "Boa noite. Estou por aqui.") as? ShortArray)
        try {
            assertTrue(samples.size in 12_000..240_000)
            assertTrue(samples.any { abs(it.toInt()) > 655 })
            val playback = synthesis.getDeclaredMethod("playSpeech", ShortArray::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation -> playback.invoke(null, samples, continuation) }
        } finally { samples.fill(0) }
    }

    @Test fun packagedWhisperPreservesNameInPortugueseGreeting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("orionPackagedSpeech") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.noBackupFilesDir, "orion_voice_small_q5_1.bin")
        val pcm = File(context.filesDir, "orion_voice_greeting_fixture.pcm").readBytes()
        require(model.length() == 190_085_487L && pcm.size in 2..256_000 && pcm.size % 2 == 0)
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        pcm.fill(0)
        val vad = Class.forName("network.orion.voice.service.OrionVoiceActivityDetection")
        val speech = try {
            checkNotNull(vad.getMethod("speech", ShortArray::class.java, Int::class.javaPrimitiveType)
                .invoke(vad.getField("INSTANCE").get(null), samples, samples.size) as? ShortArray)
        } finally { samples.fill(0) }
        val decoder = Class.forName("network.orion.voice.service.OrionWhisper")
        val decoded = try {
            checkNotNull(decoder.getMethod("transcribe", Long::class.javaPrimitiveType, String::class.java, ShortArray::class.java)
                .invoke(decoder.getField("INSTANCE").get(null), 1L, model.absolutePath, speech) as? ByteArray)
        } finally { speech.fill(0) }
        val text = try { decoded.decodeToString().lowercase() } finally { decoded.fill(0) }
        assertTrue("Expected the greeting and app name in the synthetic Portuguese fixture.",
            "boa noite" in text && "orion" in text && "por aí" in text)
    }
}
