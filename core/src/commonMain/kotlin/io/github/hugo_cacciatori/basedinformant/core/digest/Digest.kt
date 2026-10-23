package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A validated digest. Built from the preliminary contract of ai-call.md section 7: the real schema comes out
 * of the template session (`core/prompt/digest.schema.json`), and this model follows it then.
 * Serialized with the contract's field names: that JSON is what History stores per digest.
 */
@Serializable
data class Digest(
    @SerialName("template_version") val templateVersion: Int,
    @SerialName("generated_at") val generatedAt: LocalDate,
    val opening: String?,
    val closing: String?,
    val topics: List<Topic>,
)

@Serializable
data class Topic(
    val id: String,
    /** Id of the curated topic this one continues, or null. */
    val continues: String?,
    @SerialName("new_development") val newDevelopment: String?,
    val title: String,
    val summary: String,
    /** The longer "More" text. */
    val detail: String?,
    /** The owner's keywords the topic matched, shown on the card. */
    val keywords: List<String>,
    /** 0-100, the model's estimate. Never shown as measured data. */
    val score: Int,
    @SerialName("score_parts") val scoreParts: ScoreParts?,
    val sources: List<Source>,
)

@Serializable
data class ScoreParts(val relevance: Int, val attention: Int, @SerialName("repeat_penalty") val repeatPenalty: Int) {
    /** relevance + attention - repeat penalty, kept within 0-100 (template v1). */
    fun total(): Int = (relevance + attention - repeatPenalty).coerceIn(0, 100)

    companion object {
        const val MAX_RELEVANCE = 60
        const val MAX_ATTENTION = 40
        const val MAX_REPEAT_PENALTY = 50
    }
}

@Serializable
data class Source(
    val title: String,
    val publisher: String,
    val url: String,
    val language: String,
    val published: LocalDate,
)
