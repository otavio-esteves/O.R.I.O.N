package network.orion.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import network.orion.voice.service.OrionSpeakerQualification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionSpeakerQualificationTest {
    @Test fun comparesSeparateSyntheticUtterancesOnDevice() {
        if (InstrumentationRegistry.getArguments().getString("orion_speaker_fixture") != "synthetic") return
        val app: Context = ApplicationProvider.getApplicationContext()
        val files = File(app.noBackupFilesDir, "orion_speaker_fixture")
        val names = listOf("reference.pcm", "same-speaker.pcm", "other-speaker.pcm")
        val pcm = names.map { name ->
            val file = File(files, name)
            check(file.length() in 32_000L..256_000L)
            val bytes = file.readBytes()
            try {
                check(bytes.size % 2 == 0)
                val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(samples.remaining()).also { samples.get(it) }
            } finally { bytes.fill(0) }
        }
        try {
            val scores = checkNotNull(OrionSpeakerQualification.compare(File(files, "speaker.onnx"), pcm.first(), pcm.drop(1)))
            assertEquals(2, scores.size)
            assertTrue("Finite similarity scores are required", scores.all { it.isFinite() && it in -1.0..1.0 })
            assertTrue("The separate utterance from the same voice must rank higher", scores[0] > scores[1])
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("orion_speaker_result", "SYNTHETIC_VOICE_ORDER_PASSED")
            })
        } finally { pcm.forEach { it.fill(0) } }
    }
}
