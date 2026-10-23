package io.github.hugo_cacciatori.basedinformant.core.memory

import io.github.hugo_cacciatori.basedinformant.core.digest.Topic
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.charCount
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** One topic the owner already received (ai-call.md section 9). */
data class CuratedTopic(
    val id: String,
    val title: String,
    /** One line. */
    val gist: String,
    val firstFeatured: LocalDate,
    val lastFeatured: LocalDate,
    val timesFeatured: Int,
    val lastScore: Int,
)

/**
 * The memory sent with every call so the model avoids repeats and spots new developments. Each device keeps its
 * own. Pure functions: storage lives in `:data`.
 */
object CuratedMemory {
    const val RETENTION_DAYS = 30
    const val MAX_ENTRIES = InputLimits.MEMORY_MAX_ENTRIES
    const val GIST_MAX_CHARS = 80

    /** Drops entries last featured more than [RETENTION_DAYS] days ago, then the oldest-featured beyond [MAX_ENTRIES]. */
    fun prune(entries: List<CuratedTopic>, today: LocalDate): List<CuratedTopic> {
        val cutoff = today.minus(DatePeriod(days = RETENTION_DAYS))
        return entries.filter { it.lastFeatured >= cutoff }
            .sortedWith(compareByDescending<CuratedTopic> { it.lastFeatured }.thenBy { it.id })
            .take(MAX_ENTRIES)
    }

    /**
     * Records the topics of a **delivered** digest on [date]. A topic that continues a known entry, or has a known id,
     * updates that entry (keeping its id); any other topic becomes a new entry. The result is pruned.
     */
    fun record(entries: List<CuratedTopic>, delivered: List<Topic>, date: LocalDate): List<CuratedTopic> {
        val byId = entries.associateByTo(LinkedHashMap()) { it.id }
        for (topic in delivered) {
            val key = topic.continues?.takeIf { it in byId } ?: topic.id
            val existing = byId[key]
            byId[key] = CuratedTopic(
                id = key,
                title = topic.title,
                gist = gistOf(topic.summary),
                firstFeatured = existing?.firstFeatured ?: date,
                lastFeatured = date,
                timesFeatured = (existing?.timesFeatured ?: 0) + 1,
                lastScore = topic.score,
            )
        }
        return prune(byId.values.toList(), date)
    }

    /** The summary's first sentence on one line, at most [GIST_MAX_CHARS] characters. */
    internal fun gistOf(summary: String): String {
        val oneLine = summary.replace(Regex("\\s+"), " ").trim()
        val end = Regex("[.!?](\\s|$)").find(oneLine)?.range?.first?.plus(1) ?: oneLine.length
        return truncate(oneLine.substring(0, end), GIST_MAX_CHARS)
    }

    /**
     * The compact one-line-per-topic form sent in the prompt, most recently featured first, each line within
     * [InputLimits.MEMORY_LINE_MAX_CHARS]: `id | last YYYY-MM-DD | xN | title: gist`.
     */
    fun promptLines(entries: List<CuratedTopic>): List<String> =
        entries.sortedWith(compareByDescending<CuratedTopic> { it.lastFeatured }.thenBy { it.id })
            .take(MAX_ENTRIES)
            .map { e ->
                val line = "${e.id} | last ${e.lastFeatured} | x${e.timesFeatured} | ${e.title}: ${e.gist}"
                truncate(line.replace(Regex("\\s+"), " "), InputLimits.MEMORY_LINE_MAX_CHARS)
            }

    /** Cuts to [max] characters (code points), ending with an ellipsis when cut. */
    internal fun truncate(text: String, max: Int): String {
        if (text.charCount() <= max) return text
        val sb = StringBuilder()
        var count = 0
        var i = 0
        while (i < text.length && count < max - 1) {
            val step = if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
            sb.append(text, i, i + step)
            i += step
            count++
        }
        return sb.toString().trimEnd() + "…"
    }
}
