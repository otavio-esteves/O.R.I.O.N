package network.orion.core.queries.memories

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import network.orion.core.queries.Query

/** Only quoted Unicode words are ever sent to FTS. The cursor belongs to that exact normalized query. */
class SearchMemories(text: String, val limit: Int = 20, val after: MemorySearchCursor? = null) : Query<MemorySearchPage> {
    val tokens: List<String>
    val ftsExpression: String get() = tokens.joinToString(" AND ") { "\"$it\"" }

    init {
        require(text.length in 1..256 && Charsets.UTF_8.newEncoder().canEncode(text))
        require(text.toByteArray(Charsets.UTF_8).size <= 256 && text.none { it.isISOControl() })
        require(limit in 1..100)
        tokens = Collections.unmodifiableList(memorySearchTokens(text).distinct())
        require(tokens.size in 1..12 && tokens.all { it.length <= 64 })
        require(after == null || after.expression == ftsExpression)
    }

    override fun toString() = "SearchMemories(<redacted>)"
}

class MemorySearchCursor(val expression: String, val rankedAt: Instant, val score: Int, val memoryId: UUID) {
    override fun toString() = "MemorySearchCursor(<redacted>)"
}

enum class MemorySearchState { READY, BUILDING, KEY_UNAVAILABLE }

class MemorySearchHit(val memory: MemorySummary, val score: Int) {
    override fun toString() = "MemorySearchHit(<redacted>)"
}

class MemorySearchPage(val state: MemorySearchState, hits: List<MemorySearchHit> = emptyList(),
    val next: MemorySearchCursor? = null, val candidateLimitReached: Boolean = false) {
    val hits: List<MemorySearchHit> = Collections.unmodifiableList(ArrayList(hits))

    init {
        require(hits.size <= 100)
        require(state == MemorySearchState.READY || hits.isEmpty() && next == null && !candidateLimitReached)
    }

    override fun toString() = "MemorySearchPage(<redacted>)"
}

private val word = Regex("[\\p{L}\\p{Nd}]+")
private val mark = Regex("\\p{M}+")

private fun memorySearchTokens(text: String): List<String> {
    val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(mark, "")
    return word.findAll(folded).map { it.value }.toList()
}

/** Text match dominates the metadata adjustments. Ranking time stays fixed across pages. */
object OrionMemoryRanking {
    fun score(query: SearchMemories, content: String, updatedAt: Instant, at: Instant,
        importance: Double?, confidence: Double): Int {
        val words = memorySearchTokens(content)
        val matches = words.groupingBy { it }.eachCount()
        val frequency = query.tokens.sumOf { (matches[it] ?: 0).coerceAtMost(4) }
        val consecutive = words.windowed(query.tokens.size).any { it == query.tokens }
        val age = Duration.between(updatedAt, at).toDays().coerceIn(0, 30)
        return frequency * 250 + (if (consecutive) 200 else 0) + (30 - age).toInt() +
            ((importance ?: 0.0) * 100).toInt() + (confidence * 100).toInt()
    }
}
