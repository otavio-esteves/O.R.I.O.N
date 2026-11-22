package network.orion.voice.wakeword

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.TimeSource
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWakeWordSoftwareBenchmarkTest {
    @Test fun measuresHeldOutKeywordAndNonKeywordClips() {
        val results = mutableListOf(measure("PARAMETRIC_SYNTHETIC_PCM", syntheticCorpus()))
        System.getenv("ORION_WAKE_WORD_PCM_CORPUS")?.let { path ->
            results += measure("LOCAL_LABELED_WAV", readPrivateCorpus(path))
        }
        val environment = if (Files.exists(Path.of("/.dockerenv"))) "CONTAINER" else "HOST"
        val report = """
            {
              "schemaVersion":1,
              "environment":"$environment",
              "runtime":"JVM",
              "definitiveHardwareEvidence":false,
              "qualificationResult":"NOT_EVALUATED",
              "evaluationUnit":"PRESEGMENTED_PCM_CLIP",
              "sampleRateHz":${OrionWakeWordDetector.SAMPLE_RATE_HZ},
              "scoreThreshold":${OrionWakeWordDetector.DEFAULT_THRESHOLD},
              "measurements":[${results.joinToString(",")}]
            }
        """.trimIndent()
        println("ORION_WAKE_WORD_SOFTWARE_BENCHMARK=" + report.replace("\n", ""))
        System.getenv("ORION_WAKE_WORD_BENCHMARK_REPORT")?.let { path -> writeReport(path, report) }
    }

    @Test fun acceptsPaddedWavChunksAndRejectsUnsupportedOrIncompletePcm() {
        val samples = OrionWakeWordDetector.MIN_SAMPLES
        val wav = ByteBuffer.allocate(56 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.putInt(RIFF).putInt(wav.capacity() - 8).putInt(WAVE)
        wav.putInt(FORMAT).putInt(16).putShort(1).putShort(1)
            .putInt(OrionWakeWordDetector.SAMPLE_RATE_HZ).putInt(OrionWakeWordDetector.SAMPLE_RATE_HZ * 2)
            .putShort(2).putShort(16)
        wav.putInt(0x4B4E554A).putInt(3).putInt(0) // Three JUNK bytes and a RIFF pad byte.
        wav.putInt(DATA).putInt(samples * 2)
        repeat(samples) { wav.putShort(7) }
        assertTrue(readWave(wav.array()).all { it.toInt() == 7 })

        assertThrows(IllegalArgumentException::class.java) { readWave(wav.array().copyOf(wav.capacity() - 1)) }
        val ordered = wav.array()
        val dataBeforeFormat = ordered.copyOfRange(0, 12) + ordered.copyOfRange(48, ordered.size) +
            ordered.copyOfRange(12, 48)
        assertThrows(IllegalArgumentException::class.java) { readWave(dataBeforeFormat) }
        wav.putShort(22, 2)
        assertThrows(IllegalArgumentException::class.java) { readWave(wav.array()) }
    }

    private data class Corpus(
        val references: List<ShortArray>,
        val positives: List<ShortArray>,
        val negatives: List<ShortArray>,
    )

    private fun measure(label: String, corpus: Corpus): String {
        val clips = corpus.references + corpus.positives + corpus.negatives
        try {
            val hashes = HashSet<List<Byte>>()
            val sha256 = MessageDigest.getInstance("SHA-256")
            for (clip in clips) {
                val bytes = ByteBuffer.allocate(clip.size * 2)
                for (sample in clip) bytes.putShort(sample)
                val distinct = try {
                    hashes.add(sha256.digest(bytes.array()).toList())
                } finally {
                    bytes.array().fill(0)
                }
                assertTrue("Reference and evaluation PCM must be distinct.", distinct)
            }
            val detector = OrionWakeWordDetector(corpus.references)
            repeat(3) {
                detector.evaluate(corpus.positives.first())
                detector.evaluate(corpus.negatives.first())
            }
            val durationsNs = mutableListOf<Long>()
            fun detect(clip: ShortArray): Boolean {
                val start = TimeSource.Monotonic.markNow()
                val result = detector.evaluate(clip)
                durationsNs += start.elapsedNow().inWholeNanoseconds
                assertTrue("Detector must return a finite, bounded distance.", result.distance in 0.0..1.0)
                return result.detected
            }
            val trueAccepts = corpus.positives.count(::detect)
            val falseAccepts = corpus.negatives.count(::detect)
            val falseRejects = corpus.positives.size - trueAccepts
            val sorted = durationsNs.sorted()
            val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
            val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
            return """
                {"fixture":"$label","referenceClips":${corpus.references.size},
                "positiveClips":${corpus.positives.size},"negativeClips":${corpus.negatives.size},
                "trueAccepts":$trueAccepts,"falseRejects":$falseRejects,
                "falseAccepts":$falseAccepts,"trueRejects":${corpus.negatives.size - falseAccepts},
                "falseRejectRate":${falseRejects.toDouble() / corpus.positives.size},
                "falseAcceptRate":${falseAccepts.toDouble() / corpus.negatives.size},
                "inProcessScoreLatencyNs":{"samplesMeasured":${sorted.size},"median":$median,"p95":$p95}}
            """.trimIndent().replace("\n", "")
        } finally {
            clips.forEach { it.fill(0) }
        }
    }

    private data class Formants(val millis: Int, val first: Int, val second: Int, val third: Int)

    private fun syntheticCorpus(): Corpus {
        // A fixed formant contour, without a speaker or a linguistically valid utterance.
        val opening = Formants(210, 480, 900, 2550)
        val transition = Formants(75, 370, 1550, 2700)
        val middle = Formants(165, 310, 2220, 3150)
        val ending = Formants(245, 440, 1060, 2380)
        val pattern = listOf(opening, transition, middle, ending)
        val references = listOf(
            formantClip(pattern, 11, 0.98, 0.99),
            formantClip(pattern, 29, 1.04, 1.01),
        )
        val positives = List(12) { index ->
            formantClip(pattern, 200 + index, 0.88 + index * 0.021, 0.97 + index * 0.006)
        }
        val alternatives = listOf(
            pattern.reversed(),
            listOf(opening, transition, middle, Formants(245, 780, 1420, 2680)),
            listOf(opening, transition, ending, middle),
            listOf(ending, middle, transition, opening, transition),
            listOf(middle, Formants(200, 850, 1480, 2860), middle),
            listOf(opening, transition, middle),
        )
        val negatives = alternatives.flatMapIndexed { index, contour ->
            List(3) { variant ->
                formantClip(contour, 400 + index * 10 + variant, 0.95 + variant * 0.05, 0.98 + variant * 0.02)
            }
        }.toMutableList()
        negatives += ShortArray(OrionWakeWordDetector.MIN_SAMPLES)
        repeat(4) { variant ->
            val random = Random(700 + variant)
            negatives += ShortArray(9600) { index ->
                val hum = 400 * sin(2 * PI * 60 * index / OrionWakeWordDetector.SAMPLE_RATE_HZ)
                (hum + random.nextInt(-900, 901)).roundToInt().toShort()
            }
        }
        return Corpus(references, positives, negatives)
    }

    private fun formantClip(contour: List<Formants>, seed: Int, pace: Double, spectrum: Double): ShortArray {
        val rate = OrionWakeWordDetector.SAMPLE_RATE_HZ
        val segmentLengths = contour.map { (it.millis * pace * rate / 1000).roundToInt() }
        val pcm = ShortArray(segmentLengths.sum())
        val random = Random(seed)
        var offset = 0
        for ((segment, lengths) in contour.zip(segmentLengths)) {
            val first = 2 * PI * segment.first * spectrum / rate
            val second = 2 * PI * segment.second * spectrum / rate
            val third = 2 * PI * segment.third * spectrum / rate
            for (index in 0 until lengths) {
                val envelope = minOf(index / 160.0, (lengths - index - 1) / 160.0, 1.0)
                val signal = 2600 * sin(first * index) + 1800 * sin(second * index) + 1000 * sin(third * index)
                pcm[offset++] = (envelope * signal + random.nextInt(-48, 49)).roundToInt().toShort()
            }
        }
        return pcm
    }

    private fun readPrivateCorpus(value: String): Corpus = safeFileOperation("Could not read the private WAV corpus.") {
        val root = Path.of(value)
        Corpus(
            readWaveGroup(root.resolve("enroll"), OrionWakeWordDetector.MAX_TEMPLATES),
            readWaveGroup(root.resolve("positive"), MAX_EVALUATION_CLIPS),
            readWaveGroup(root.resolve("negative"), MAX_EVALUATION_CLIPS),
        )
    }

    private fun readWaveGroup(directory: Path, maximum: Int): List<ShortArray> {
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "A labeled WAV group is missing." }
        val clips = Files.list(directory).use { it.limit(maximum + 1L).toList() }
        require(clips.size in 1..maximum) { "A WAV group has an invalid clip count." }
        return clips.sortedBy { it.fileName.toString() }.map { path ->
            require(path.fileName.toString().endsWith(".wav", ignoreCase = true) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "WAV groups must contain regular .wav files." }
            val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_WAV_BYTES + 1) }
            try {
                readWave(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun readWave(bytes: ByteArray): ShortArray {
        require(bytes.size in 44..MAX_WAV_BYTES) { "WAV file exceeds the bounded PCM container." }
        val wave = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(wave.int == RIFF && wave.int == bytes.size - 8 && wave.int == WAVE) { "Invalid RIFF/WAVE header." }
        var formatFound = false
        var dataOffset = -1
        var dataSamples = 0
        while (wave.hasRemaining()) {
            require(wave.remaining() >= 8) { "Incomplete WAV chunk header." }
            val name = wave.int
            val size = wave.int
            require(size in 0..wave.remaining()) { "Invalid WAV chunk length." }
            val next = wave.position() + size + (size and 1)
            require(next <= wave.limit()) { "Incomplete WAV chunk padding." }
            if (name == FORMAT) {
                require(!formatFound && size >= 16 && wave.short.toInt() == 1 && wave.short.toInt() == 1 &&
                    wave.int == OrionWakeWordDetector.SAMPLE_RATE_HZ && wave.int == OrionWakeWordDetector.SAMPLE_RATE_HZ * 2 &&
                    wave.short.toInt() == 2 && wave.short.toInt() == 16) { "WAV must be 16 kHz mono PCM16." }
                formatFound = true
            } else if (name == DATA) {
                require(formatFound) { "WAV format must precede PCM data." }
                require(dataOffset == -1 && size % 2 == 0 &&
                    size / 2 in OrionWakeWordDetector.MIN_SAMPLES..OrionWakeWordDetector.MAX_SAMPLES) {
                    "WAV PCM clip is outside the detector bounds."
                }
                dataOffset = wave.position()
                dataSamples = size / 2
            }
            wave.position(next)
        }
        require(formatFound && dataOffset >= 0) { "WAV format or data chunk missing." }
        wave.position(dataOffset)
        return ShortArray(dataSamples) { wave.short }
    }

    private fun writeReport(value: String, report: String) =
        safeFileOperation("Could not write a new aggregate report.") {
            Files.writeString(Path.of(value), "$report\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }

    private fun <T> safeFileOperation(message: String, operation: () -> T): T = try {
        operation()
    } catch (_: IOException) {
        throw IllegalArgumentException(message)
    } catch (_: UncheckedIOException) {
        throw IllegalArgumentException(message)
    } catch (_: InvalidPathException) {
        throw IllegalArgumentException(message)
    } catch (_: SecurityException) {
        throw IllegalArgumentException(message)
    }

    private companion object {
        const val MAX_EVALUATION_CLIPS = 64
        const val MAX_WAV_BYTES = 65_536
        const val RIFF = 0x46464952
        const val WAVE = 0x45564157
        const val FORMAT = 0x20746D66
        const val DATA = 0x61746164
    }
}
