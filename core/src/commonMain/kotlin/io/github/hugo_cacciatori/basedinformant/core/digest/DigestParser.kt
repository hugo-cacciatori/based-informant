package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Turns the model's `content` text into a JSON object (02-ai-call.md section 8: strip fences, parse). */
object DigestParser {
    sealed interface Result {
        /** [truncated] is true when the text was cut off and only the complete topics were kept. */
        data class Parsed(val json: JsonObject, val truncated: Boolean) : Result
        data class Failed(val reason: Failure) : Result
    }

    enum class Failure { EMPTY, NOT_JSON, NOT_AN_OBJECT, TRUNCATED_BEFORE_TOPICS }

    private val json = Json

    fun parse(content: String): Result {
        val text = stripFences(content)
        if (text.isEmpty()) return Result.Failed(Failure.EMPTY)
        val start = text.indexOf('{')
        if (start < 0) return Result.Failed(if (text.trimStart().startsWith("[")) Failure.NOT_AN_OBJECT else Failure.NOT_JSON)
        val body = text.substring(start)

        decodeObject(body)?.let { return Result.Parsed(it, truncated = false) }

        val recovered = recoverTruncated(body)
        if (recovered == null) {
            val scan = TruncationScanner(body).scan()
            val cutBeforeTopics = scan.endDepth > 0 && scan.topicsOpenAt < 0
            return Result.Failed(if (cutBeforeTopics) Failure.TRUNCATED_BEFORE_TOPICS else Failure.NOT_JSON)
        }
        return decodeObject(recovered)?.let { Result.Parsed(it, truncated = true) } ?: Result.Failed(Failure.NOT_JSON)
    }

    /** Removes a surrounding markdown code fence (```json ... ```), if any. */
    internal fun stripFences(content: String): String {
        var text = content.trim()
        if (text.startsWith("```")) {
            val firstLineEnd = text.indexOf('\n')
            text = if (firstLineEnd < 0) "" else text.substring(firstLineEnd + 1)
            text = text.trimEnd()
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        return text.trim()
    }

    private fun decodeObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A cut-off answer keeps everything before the `topics` array and every topic that is complete;
     * the topic being written when the text stopped is dropped.
     */
    internal fun recoverTruncated(text: String): String? {
        val scan = TruncationScanner(text).scan()
        if (scan.topicsOpenAt < 0 || scan.topicsClosed || scan.endDepth == 0) return null
        return buildString {
            append(text, 0, scan.topicsOpenAt + 1)
            scan.completeTopics.joinTo(this, ",") { text.substring(it) }
            append("]}")
        }
    }

    /** Walks the JSON text tracking strings and nesting, to find the `topics` array and its complete objects. */
    private class TruncationScanner(private val text: String) {
        class Scan(val topicsOpenAt: Int, val topicsClosed: Boolean, val completeTopics: List<IntRange>, val endDepth: Int)

        fun scan(): Scan {
            var depth = 0
            var inString = false
            var escaped = false
            var stringStart = -1
            var lastTopLevelString: String? = null
            var topicsOpenAt = -1
            var topicsClosed = false
            var topicStart = -1
            val complete = mutableListOf<IntRange>()

            for ((i, c) in text.withIndex()) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> {
                            inString = false
                            if (depth == 1) lastTopLevelString = text.substring(stringStart + 1, i)
                        }
                    }
                    continue
                }
                val inTopics = topicsOpenAt >= 0 && !topicsClosed
                when (c) {
                    '"' -> { inString = true; stringStart = i }
                    '{', '[' -> {
                        depth++
                        if (c == '[' && depth == 2 && topicsOpenAt < 0 && lastTopLevelString == "topics") topicsOpenAt = i
                        if (c == '{' && depth == 3 && inTopics) topicStart = i
                    }
                    '}', ']' -> {
                        if (c == '}' && depth == 3 && inTopics && topicStart >= 0) {
                            complete += topicStart..i
                            topicStart = -1
                        }
                        if (c == ']' && depth == 2 && inTopics) topicsClosed = true
                        depth--
                    }
                }
            }
            return Scan(topicsOpenAt, topicsClosed, complete, depth)
        }
    }
}
