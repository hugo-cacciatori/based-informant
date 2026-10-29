package io.github.hugo_cacciatori.basedinformant.core.prompt

import io.github.hugo_cacciatori.basedinformant.core.digest.RecencyWindow
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

/** Everything one run is built from: the run's settings snapshot. */
data class PromptInput(
    val keywords: List<String>,
    val interestTags: List<String>,
    val attentionMode: AttentionMode,
    val persona: Persona,
    val today: LocalDate,
    val lastDigestDate: LocalDate?,
    /** The second call after an empty digest searches [RecencyWindow.WIDE_DAYS] days. */
    val wideWindow: Boolean = false,
    val model: String = TemplateConfig.DEFAULT_MODEL,
    /** The topic tags of stored digests, newest first, for the writer to reuse (02-ai-call.md section 9). */
    val knownTopicTags: List<String> = emptyList(),
) {
    /** The period searched; the checks use the same one. */
    fun window(): RecencyWindow = if (wideWindow) RecencyWindow.wide(today) else RecencyWindow.of(today, lastDigestDate)
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
