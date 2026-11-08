package network.orion.core.health

enum class OrionNativeQualification { PENDING, PASS, FAIL, NOT_APPLICABLE_NO_NATIVE_ARTIFACTS, UNAVAILABLE }
enum class OrionMemoryLimiterClassification { DEDICATED_REASON_AVAILABLE, DESCRIPTION_FALLBACK_ONLY }
enum class OrionMemoryLimiterEvidence { OBSERVED, NOT_OBSERVED, UNAVAILABLE }

/** Diagnostic evidence only. Neither qualification metadata nor missing exits grants runtime authority. */
data class OrionPlatformDiagnostics(
    val nativeQualification: OrionNativeQualification,
    val memoryLimiterClassification: OrionMemoryLimiterClassification,
    val memoryLimiterEvidence: OrionMemoryLimiterEvidence,
) {
    companion object {
        fun from(nativeStatus: String?, dedicatedReasonAvailable: Boolean, limiterObserved: Boolean?) = OrionPlatformDiagnostics(
            OrionNativeQualification.entries.firstOrNull { it.name == nativeStatus } ?: OrionNativeQualification.UNAVAILABLE,
            if (dedicatedReasonAvailable) OrionMemoryLimiterClassification.DEDICATED_REASON_AVAILABLE else
                OrionMemoryLimiterClassification.DESCRIPTION_FALLBACK_ONLY,
            when (limiterObserved) {
                true -> OrionMemoryLimiterEvidence.OBSERVED
                false -> OrionMemoryLimiterEvidence.NOT_OBSERVED
                null -> OrionMemoryLimiterEvidence.UNAVAILABLE
            },
        )
    }
}
