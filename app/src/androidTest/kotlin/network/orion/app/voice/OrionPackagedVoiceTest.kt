package network.orion.app.voice

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
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
}
