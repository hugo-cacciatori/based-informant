package io.github.hugo_cacciatori.basedinformant.core.prompt

import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Settings of the template version in use, fixed in the lab (02-ai-call.md sections 2, 10 and 12). */
object TemplateConfig {
    /** Template v2: the paid call only searches, free models write (call redesign, 2026-10-23). */
    const val TEMPLATE_VERSION = 2

    /** Chosen in the lab (owner, 2026-10-18; 02-ai-call.md section 11), id verified 2026-10-18 in rfab's models list. */
    const val DEFAULT_MODEL = "openrouter:perplexity/sonar"

    /** The models Settings may offer: only those validated in the lab (02-ai-call.md section 11). */
    val ALLOWED_MODELS: List<String> = listOf(DEFAULT_MODEL)

    /** Kept by the owner, 2026-10-18 (02-ai-call.md section 2): the longest sonar answer used 1,541 tokens. */
    const val MAX_TOKENS = 4_000

    /** Kept by the owner, 2026-10-18: every lab run used it. */
    const val TEMPERATURE = 0.2

    fun voiceFile(persona: Persona) = "voices/${persona.voiceFile}.txt"
}

/** Keyword mode, or topic mode: the digest covers up to 5 topic tags of earlier digests (01-product.md section 3). */
enum class DigestMode { KEYWORDS, TOPICS }

/** Everything one run is built from: the run's settings snapshot. */
data class PromptInput(
    /** The owner's keywords, or in topic mode the chosen topic tags. */
    val keywords: List<String>,
    val interestTags: List<String>,
    val attentionMode: AttentionMode,
    val persona: Persona,
    val today: LocalDate,
    val model: String = TemplateConfig.DEFAULT_MODEL,
    /** The topic tags of stored digests, newest first, for the writer to reuse (02-ai-call.md section 9). */
    val knownTopicTags: List<String> = emptyList(),
    val mode: DigestMode = DigestMode.KEYWORDS,
    /** Right-wing sources only (02-ai-call.md section 4). */
    val based: Boolean = false,
) {
    /** Topic mode does not use the interest tags (owner, 2026-10-23). */
    fun tagsSent(): List<String> = if (mode == DigestMode.TOPICS) emptyList() else interestTags
}

/** The body of `POST /api/chat/completions` (02-ai-call.md section 1). Headers, and so the key, are never part of it. */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val maxTokens: Int,
    val temperature: Double,
)

@Serializable
data class ChatMessage(val role: String, val content: String)

/** Data sent to a model goes between markers; '<' and '>' are escaped inside so no text can forge a marker. */
internal fun escapedMarkers(text: String) = text.replace("<", "\\u003c").replace(">", "\\u003e")
