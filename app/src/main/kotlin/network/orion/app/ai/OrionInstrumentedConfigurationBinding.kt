package network.orion.app.ai

import java.security.MessageDigest
import network.orion.core.resources.OrionInferenceResourcePlan

/** Technical descriptor hash of actual immutable MAIN configuration, never approval or prompt identity. */
internal class OrionInstrumentedConfigurationBinding(val modelSha256: String, val configurationSha256: String) {
    override fun toString() = "OrionInstrumentedConfigurationBinding(REDACTED, qualified=false)"
}

/** Descriptor v2 preserves ordered fallback plans and original scalar request configuration.
 * Source/APK binding, not a content hash, identifies the trusted workload text. No prompt is read here.
 */
internal fun deriveInstrumentedConfigurationBinding(plans: List<OrionInferenceResourcePlan>, timing: OrionCortexResourceTiming?,
    template: OrionCortexAttemptTemplate?, observation: OrionRuntimeCaptureObservationConfiguration?,
    exitConfiguration: OrionQualificationExitConfiguration? = null): OrionInstrumentedConfigurationBinding? = try {
    if (plans.isEmpty() || plans.size > 64 || timing == null || template == null || observation == null ||
        !validModelHash(template.modelId) || plans.none { it.model.modelId == template.modelId }) null else {
        val descriptor = StringBuilder("ORION_INSTRUMENTED_RESOURCE_DESCRIPTOR_V2\n")
        fun text(value: String) {
            require(value.length in 1..128 && value.all { it.code in 33..126 })
            descriptor.append(value.length).append(':').append(value).append('\n')
        }
        fun number(value: Long) { descriptor.append(value).append('\n') }
        number(plans.size.toLong())
        for (plan in plans) {
            text(plan.model.modelId); number(plan.model.estimatedMemoryBytes); text(plan.tier)
            number(plan.maxThreads.toLong()); number(plan.maxContext.toLong()); number(plan.maxTokens.toLong())
            number(plan.keepAliveMs); number(plan.processBudgetBytes); number(plan.aggregateBudgetBytes)
            number(plan.systemReserveBytes); number(plan.minimumBatteryPercent.toLong()); number(plan.maxThermalStatus.toLong())
        }
        number(timing.maximumObservationAgeMillis); number(timing.memoryExitCooldownMillis)
        text(template.modelId); number(template.contextTokens.toLong()); number(template.threads.toLong()); number(template.maxTokens.toLong())
        number(template.requestDurationMillis); number(java.lang.Double.doubleToRawLongBits(template.temperature)); text(template.priority.name)
        number(observation.maximumSampleAgeMillis); number(observation.postReturnWindowMillis)
        // Diagnostic configuration is part of the technical binding, including each explicitly absent limit.
        fun exitLimit(name: String, value: Long?) { text(name); if (value == null) text("UNAVAILABLE") else number(value) }
        exitLimit("exit.maximumPairSpanMillis", exitConfiguration?.maximumPairSpanMillis)
        exitLimit("exit.maximumLiveGapMillis", exitConfiguration?.maximumLiveGapMillis)
        exitLimit("exit.maximumExitDelayMillis", exitConfiguration?.maximumExitDelayMillis)
        val digest = MessageDigest.getInstance("SHA-256").digest(descriptor.toString().toByteArray(Charsets.US_ASCII))
        val hash = digest.joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
        OrionInstrumentedConfigurationBinding(template.modelId, hash)
    }
} catch (_: Throwable) { null }
