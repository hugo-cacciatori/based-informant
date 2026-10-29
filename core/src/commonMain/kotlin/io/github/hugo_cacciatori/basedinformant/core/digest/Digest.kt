package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A digest as History stores it (template v2, 02-ai-call.md section 12): the free writer's text joined with the paid
 * search's checked sources. Serialized with these field names: that JSON is what History stores per digest.
 */
@Serializable
data class Digest(
    @SerialName("template_version") val templateVersion: Int,
    @SerialName("generated_at") val generatedAt: LocalDate,
    val opening: String?,
    val closing: String?,
    val topics: List<Topic>,
    /** The email subject in the digest's voice, or null to use the plain one (03-rendering.md section 1). */
    val subject: String? = null,
    /** The History card's one line in the digest's voice (01-product.md section 5), or null to list the titles. */
    @SerialName("card_summary") val cardSummary: String? = null,
)

@Serializable
data class Topic(
    val id: String,
    /** The ongoing subject, reused across digests (02-ai-call.md section 9). */
    @SerialName("topic_tag") val topicTag: String? = null,
    val title: String,
    val summary: String,
    /** The longer "More" text. */
    val detail: String?,
    /** The owner's keywords the topic matched, shown on the card. */
    val keywords: List<String>,
    /** 0-100, the models' estimate. Never shown as measured data. */
    val score: Int,
    @SerialName("score_parts") val scoreParts: ScoreParts?,
    val sources: List<Source>,
)

/** The writer's relevance and the search's attention (template v2: no repeat penalty, owner 2026-10-23). */
@Serializable
data class ScoreParts(val relevance: Int, val attention: Int) {
    fun total(): Int = (relevance + attention).coerceIn(0, 100)

    companion object {
        const val MAX_RELEVANCE = 60
        const val MAX_ATTENTION = 40
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
