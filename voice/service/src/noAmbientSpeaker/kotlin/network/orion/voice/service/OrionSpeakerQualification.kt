package network.orion.voice.service

import java.io.File

/** Speaker qualification is available only with the pinned development runtime. */
object OrionSpeakerQualification {
    fun compare(model: File, reference: ShortArray, samples: List<ShortArray>): List<Double>? = null
}
