package network.orion.core.resources

import network.orion.core.exitinfo.ProcessExitKind
import network.orion.core.exitinfo.ProcessExitSignal

/** Sanitized evidence only. A resource kill is not proof of memory exhaustion. */
internal fun ProcessExitSignal.isMemoryExit() = reason == 3 || memoryLimiterEvidence ||
    kind == ProcessExitKind.LOW_MEMORY || kind == ProcessExitKind.MEMORY_LIMITER

internal fun ProcessExitSignal.requiresResourceBackoff() = isMemoryExit() || reason == 9 ||
    kind == ProcessExitKind.EXCESSIVE_RESOURCE_USAGE
