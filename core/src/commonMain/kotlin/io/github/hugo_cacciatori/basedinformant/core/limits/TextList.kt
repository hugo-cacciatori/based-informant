package io.github.hugo_cacciatori.basedinformant.core.limits

/**
 * Editing rules for a list of short texts: the keyword list (product.md section 3) and the interest tags.
 * Entries are stored trimmed, in the order they were added; a duplicate (ignoring case) is refused.
 * Every operation returns the new list or the reason it was refused, and never changes the list on refusal.
 */
class TextListRules(val maxEntries: Int, val maxChars: Int) {
    fun add(list: List<String>, text: String): EditResult {
        if (list.size >= maxEntries) return EditResult.Refused(Refusal.FULL)
        return check(list, text, ignoreIndex = -1) ?: EditResult.Done(list + text.trim())
    }

    fun edit(list: List<String>, index: Int, text: String): EditResult {
        require(index in list.indices) { "no entry at $index" }
        return check(list, text, ignoreIndex = index)
            ?: EditResult.Done(list.toMutableList().also { it[index] = text.trim() })
    }

    /** The removed entry and its place, so an Undo can put it back. */
    fun delete(list: List<String>, index: Int): Deleted {
        require(index in list.indices) { "no entry at $index" }
        return Deleted(list.filterIndexed { i, _ -> i != index }, Removed(index, list[index]))
    }

    /** Puts a deleted entry back at its place, unless an equal one was added since (then nothing changes). */
    fun undo(list: List<String>, removed: Removed): List<String> {
        if (list.size >= maxEntries || list.any { it.lowercase() == removed.text.lowercase() }) return list
        return list.toMutableList().also { it.add(removed.index.coerceAtMost(list.size), removed.text) }
    }

    private fun check(list: List<String>, text: String, ignoreIndex: Int): EditResult.Refused? {
        val trimmed = text.trim()
        val key = trimmed.lowercase()
        return when {
            trimmed.isEmpty() -> EditResult.Refused(Refusal.BLANK)
            trimmed.charCount() > maxChars -> EditResult.Refused(Refusal.TOO_LONG)
            list.withIndex().any { (i, it) -> i != ignoreIndex && it.trim().lowercase() == key } ->
                EditResult.Refused(Refusal.DUPLICATE)
            else -> null
        }
    }

    data class Removed(val index: Int, val text: String)
    data class Deleted(val list: List<String>, val removed: Removed)

    sealed interface EditResult {
        data class Done(val list: List<String>) : EditResult
        data class Refused(val reason: Refusal) : EditResult
    }

    enum class Refusal { BLANK, TOO_LONG, DUPLICATE, FULL }

    companion object {
        val KEYWORDS = TextListRules(InputLimits.KEYWORDS_MAX, InputLimits.KEYWORD_MAX_CHARS)
        val TAGS = TextListRules(InputLimits.TAGS_MAX, InputLimits.TAG_MAX_CHARS)
    }
}

/** What a text field shows under itself: "count / max", and whether it can be saved. */
data class FieldCounter(val count: Int, val max: Int) {
    val canSave: Boolean get() = count <= max

    companion object {
        fun of(text: String, max: Int) = FieldCounter(text.trim().charCount(), max)
        fun keyword(text: String) = of(text, InputLimits.KEYWORD_MAX_CHARS)
        fun tag(text: String) = of(text, InputLimits.TAG_MAX_CHARS)
    }
}
