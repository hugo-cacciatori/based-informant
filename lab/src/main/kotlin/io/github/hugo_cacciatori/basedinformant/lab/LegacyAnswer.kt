package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
import io.github.hugo_cacciatori.basedinformant.core.search.SearchParser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lab only: reads a search answer, or turns a saved template-v1 sonar answer (`lab-out/`, 2026-10-18) into one, so free
 * writer runs can replay the old paid answers. Each v1 topic becomes a story; its summary and detail sentences become the
 * facts of its first source, its attention part is kept.
 */
object LegacyAnswer {
    fun read(text: String): SearchAnswer? {
        if ("\"stories\"" in text) return SearchParser.parse(text)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val digest = try {
            lenient.decodeFromString(V1.serializer(), text.substring(start, end + 1))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SearchAnswer(
            searchedAt = digest.generatedAt,
            stories = digest.topics.map { t ->
                val facts = listOfNotNull(t.summary, t.detail).flatMap(::sentences)
                SearchAnswer.Story(
                    keywords = t.keywords,
                    whatHappened = t.title,
                    attention = t.scoreParts?.attention,
                    sources = t.sources.mapIndexed { i, s ->
                        SearchAnswer.SearchSource(title = s.title, publisher = s.publisher, url = s.url, language = s.language, published = s.published, facts = if (i == 0) facts else emptyList())
                    },
                )
            },
        )
    }

    private fun sentences(text: String): List<String> = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    @Serializable
    private data class V1(@SerialName("generated_at") val generatedAt: String, val topics: List<V1Topic>)

    @Serializable
    private data class V1Topic(
        val title: String,
        val summary: String,
        val detail: String? = null,
        val keywords: List<String>,
        @SerialName("score_parts") val scoreParts: V1Parts? = null,
        val sources: List<V1Source>,
    )

    @Serializable
    private data class V1Parts(val attention: Int)

    @Serializable
    private data class V1Source(val title: String, val publisher: String, val url: String, val language: String, val published: String)

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
}
