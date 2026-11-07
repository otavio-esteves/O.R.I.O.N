package network.orion.core.exitinfo

enum class ForceStopStartupObservation { UNAVAILABLE, UNSUPPORTED, NOT_OBSERVED, OBSERVED }

/**
 * Current-start primary evidence only. The Android adapter supplies the public
 * ApplicationStartInfo.wasForceStopped value for this startup, never an exit
 * reason or an arbitrary historical record. Incomplete negative data is not a
 * conclusion. Positive evidence remains pending until its durable write succeeds.
 * The MAIN owner serializes access; no method grants execution authority.
 */
class OrionForceStopStartupEvidence {
    var observation: ForceStopStartupObservation = ForceStopStartupObservation.UNAVAILABLE
        private set
    var needsPersistence: Boolean = false
        private set

    fun observe(wasForceStopped: Boolean?, startupComplete: Boolean) {
        if (observation == ForceStopStartupObservation.OBSERVED || observation == ForceStopStartupObservation.UNSUPPORTED) return
        if (wasForceStopped == true) {
            observation = ForceStopStartupObservation.OBSERVED
            needsPersistence = true
        } else if (wasForceStopped == false && startupComplete) {
            observation = ForceStopStartupObservation.NOT_OBSERVED
        }
    }

    fun markUnsupported() {
        if (observation == ForceStopStartupObservation.UNAVAILABLE)
            observation = ForceStopStartupObservation.UNSUPPORTED
    }

    fun markPersisted() {
        check(observation == ForceStopStartupObservation.OBSERVED)
        needsPersistence = false
    }
}
