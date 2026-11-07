package network.orion.core.exitinfo

import java.time.Instant

enum class ProcessExitKind {
    UNKNOWN, NORMAL_EXIT, SIGNALLED, LOW_MEMORY, JVM_CRASH, NATIVE_CRASH, ANR,
    INITIALIZATION_FAILURE, PERMISSION_CHANGED, EXCESSIVE_RESOURCE_USAGE,
    USER_REQUESTED, USER_STOPPED, DEPENDENCY_DIED, OTHER_SYSTEM, FREEZER,
    PACKAGE_CHANGED, PACKAGE_UPDATED, MEMORY_LIMITER,
}

/**
 * Pure diagnostic classification of public ApplicationExitInfo reason values.
 * The adapter supplies a dedicated Memory Limiter constant only when the current
 * platform exposes it (the public symbol was added in 37.2). Older/preview builds
 * can provide OTHER plus the precise Architecture §35.1 marker instead.
 * Description is inspected transiently: it never enters the returned signal,
 * logs or persistence. A reason alone cannot establish Force Stop or authorize
 * restarting a process, retrying an action, or running inference.
 */
object OrionProcessExitClassifier {
    private const val MEMORY_LIMITER_MARKER = "MemoryLimiter:AnonSwap"

    fun classify(
        reason: Int,
        occurredAt: Instant,
        description: String? = null,
        dedicatedMemoryLimiterReason: Int? = null,
    ): ProcessExitSignal {
        // A future dedicated public constant must not shadow the stable 0..16 reasons.
        require(dedicatedMemoryLimiterReason == null || dedicatedMemoryLimiterReason > 16)
        val limiter = reason == dedicatedMemoryLimiterReason ||
            reason == 13 && description?.contains(MEMORY_LIMITER_MARKER) == true
        val kind = if (limiter) ProcessExitKind.MEMORY_LIMITER else when (reason) {
            1 -> ProcessExitKind.NORMAL_EXIT
            2 -> ProcessExitKind.SIGNALLED
            3 -> ProcessExitKind.LOW_MEMORY
            4 -> ProcessExitKind.JVM_CRASH
            5 -> ProcessExitKind.NATIVE_CRASH
            6 -> ProcessExitKind.ANR
            7 -> ProcessExitKind.INITIALIZATION_FAILURE
            8 -> ProcessExitKind.PERMISSION_CHANGED
            9 -> ProcessExitKind.EXCESSIVE_RESOURCE_USAGE
            10 -> ProcessExitKind.USER_REQUESTED
            11 -> ProcessExitKind.USER_STOPPED
            12 -> ProcessExitKind.DEPENDENCY_DIED
            13 -> ProcessExitKind.OTHER_SYSTEM
            14 -> ProcessExitKind.FREEZER
            15 -> ProcessExitKind.PACKAGE_CHANGED
            16 -> ProcessExitKind.PACKAGE_UPDATED
            else -> ProcessExitKind.UNKNOWN
        }
        return ProcessExitSignal(reason, occurredAt, kind, limiter)
    }
}
