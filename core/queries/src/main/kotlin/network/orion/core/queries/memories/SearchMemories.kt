package network.orion.core.queries.memories

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.UUID
import network.orion.core.queries.Query

/** Only quoted Unicode words are ever sent to FTS. The cursor belongs to that exact normalized query. */
class SearchMemories(text: String, val limit: Int = 20, val after: MemorySearchCursor? = null,
    private val anyTerm: Boolean = false) : Query<MemorySearchPage> {
    val tokens: List<String>
    // A space intersects terms in both SQLite FTS4 query parsers.
    val ftsExpression: String get() = tokens.joinToString(if (anyTerm) " OR " else " ") { "\"$it\"" }

    init {
        require(text.length in 1..256 && Charsets.UTF_8.newEncoder().canEncode(text))
        require(text.toByteArray(Charsets.UTF_8).size <= 256 && text.none { it.isISOControl() })
        require(limit in 1..100)
        tokens = Collections.unmodifiableList(memorySearchTokens(text).distinct())
        require(tokens.size in 1..12 && tokens.all { it.length <= 64 })
        require(after == null || after.expression == ftsExpression)
    }

    override fun toString() = "SearchMemories(<redacted>)"

    companion object {
        fun forChat(text: String): SearchMemories? {
            if (text.length !in 1..4_096) return null
            val keywords = memorySearchTokens(text).asSequence()
                .filter { it.length in 3..24 && it !in chatStopWords }.distinct().take(8).toList()
            if (keywords.isEmpty()) return null
            return try { SearchMemories(keywords.joinToString(" "), limit = 4, anyTerm = true) }
            catch (_: IllegalArgumentException) { null }
        }

        private val chatStopWords = setOf("como", "qual", "quais", "quando", "onde", "voce", "pode", "sabe",
            "sobre", "minha", "meu", "meus", "minhas", "para", "com", "que", "isso", "este", "essa", "esse",
            "lembra", "lembre", "memoria", "informacao", "esta", "estou", "tenho", "favor", "diga")
    }
}

class MemorySearchCursor(val expression: String, val rankedAt: Instant, val score: Int, val memoryId: UUID,
    val personalized: Boolean = false) {
    override fun toString() = "MemorySearchCursor(<redacted>)"
}

enum class MemorySearchState { READY, BUILDING, KEY_UNAVAILABLE, PREFERENCES_CHANGED }

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
        importance: Double?, confidence: Double, personalized: Boolean = false): Int {
        val words = memorySearchTokens(content)
        val matches = words.groupingBy { it }.eachCount()
        val frequency = query.tokens.sumOf { (matches[it] ?: 0).coerceAtMost(4) }
        val consecutive = words.windowed(query.tokens.size).any { it == query.tokens }
        val age = Duration.between(updatedAt, at).toDays().coerceIn(0, 30)
        val textScore = frequency * 250 + if (consecutive) 200 else 0
        val recency = (30 - age).toInt()
        val importanceScore = ((importance ?: 0.0) * 100).toInt()
        val confidenceScore = (confidence * 100).toInt()
        return if (personalized) textScore * 2 + recency * 3 + importanceScore * 2 + confidenceScore
        else textScore + recency + importanceScore + confidenceScore
    }
}
