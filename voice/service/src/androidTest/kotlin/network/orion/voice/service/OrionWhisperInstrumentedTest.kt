package network.orion.voice.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.TimeSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in software check using a pinned local model and synthetic 16 kHz mono PCM. */
@RunWith(AndroidJUnit4::class)
class OrionWhisperInstrumentedTest {
    @Test fun localModelTranscribesSyntheticPortuguese() {
        val opts = InstrumentationRegistry.getArguments()
        assumeTrue(opts.getString("orion_voice_fixture") == "battery")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = checkNotNull(OrionVoiceModel.verified(context))
        val pcm = File(context.filesDir, "orion_voice_battery.pcm").readBytes()
        assertTrue(pcm.size in 2..OrionVoiceActivityDetection.maxSamples * 2 && pcm.size % 2 == 0)
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        pcm.fill(0)
        val speech = checkNotNull(OrionVoiceActivityDetection.speech(samples, samples.size))
        samples.fill(0)
        val start = TimeSource.Monotonic.markNow()
        val bytes = try { checkNotNull(OrionWhisper.transcribe(1, model.absolutePath, speech)) }
            finally { speech.fill(0) }
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
        assertTrue("Expected the synthetic battery keyword.", "bateria" in text.lowercase())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putLong("orion_voice_decode_wall_ms", start.elapsedNow().inWholeMilliseconds)
        })
    }
}
