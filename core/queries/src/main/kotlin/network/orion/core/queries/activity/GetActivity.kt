package network.orion.core.queries.activity

import java.time.Instant
import java.util.Collections
import java.util.UUID
import network.orion.core.queries.Query

/** Strict timestamp + kind-prefixed UUID cursor; never a mutable queue receipt. */
class ActivityCursor(val occurredAtEpochMillis: Long, val key: String) {
    init {
        require(key.startsWith("E:") || key.startsWith("A:"))
        val id = key.substring(2)
        require(UUID.fromString(id).toString() == id)
    }
    override fun toString() = "ActivityCursor(<redacted>)"
}

data class GetActivity(val after: ActivityCursor? = null, val limit: Int = 50) : Query<ActivityPage> {
    init { require(limit in 1..100) }
    override fun toString() = "GetActivity(limit=$limit, after=<redacted>)"
}

enum class ActivityKind { DOMAIN_EVENT, ACTION }

/** Selected technical fact only; no entity IDs, encrypted payloads, errors or user text. */
class ActivityEntry(val kind: ActivityKind, val code: String, val status: String, val occurredAt: Instant) {
    init { require(code.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,79}"))); require(status.matches(Regex("[A-Z_]{1,32}"))) }
    override fun toString() = "ActivityEntry(<redacted>)"
}

class ActivityPage(items: List<ActivityEntry>, val next: ActivityCursor?) {
    val items: List<ActivityEntry> = Collections.unmodifiableList(ArrayList(items))
    init { require(items.size <= 100); require(next == null || items.isNotEmpty()) }
    override fun toString() = "ActivityPage(<redacted>)"
}

fun interface ActivityQuery { fun get(request: GetActivity): ActivityPage }
fun interface ActivityGateway { suspend fun get(request: GetActivity): ActivityPage? }
