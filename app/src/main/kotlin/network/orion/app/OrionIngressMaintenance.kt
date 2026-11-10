package network.orion.app

/** A full batch requests another bounded pass; a blocked consumer must not spin. */
internal fun drainIngressMaintenance(consumers: List<(Int) -> Int>, limit: Int = 25): Boolean {
    require(limit in 1..100)
    var continuation = false
    for (consume in consumers) {
        if (consume(limit) == limit) continuation = true
    }
    return continuation
}
