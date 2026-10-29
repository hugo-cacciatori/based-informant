package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.prompt.DigestMode
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the lab sends, as a JSON file. `lab/inputs/synthetic.json` is committed and synthetic; the owner's real
 * keywords and tags go in a file under the git-ignored `lab-out/` (05-security.md section 4).
 */
@Serializable
data class LabInput(
    val keywords: List<String>,
    @SerialName("interest_tags") val interestTags: List<String> = emptyList(),
    @SerialName("attention_mode") val attentionMode: String = "balanced",
    val persona: String = "off",
    /** Defaults to the computer's date. */
    val today: String? = null,
    /** "keywords" (default) or "topics": the keywords are then topic tags of earlier digests. */
    val mode: String = "keywords",
    /** Topic tags of earlier digests, for the writer (02-ai-call.md section 9). */
    @SerialName("known_topic_tags") val knownTopicTags: List<String> = emptyList(),
    /** Right-wing sources only (02-ai-call.md section 4). */
    val based: Boolean = false,
) {
    fun todayDate(): LocalDate = today?.let(LocalDate::parse) ?: LocalDate.parse(java.time.LocalDate.now().toString())

    fun toPromptInput(model: String, personaOverride: Persona? = null): PromptInput = PromptInput(
        keywords = keywords,
        interestTags = interestTags,
        attentionMode = parseAttention(attentionMode),
        persona = personaOverride ?: parsePersona(persona),
        today = todayDate(),
        model = model,
        knownTopicTags = knownTopicTags,
        based = based,
        mode = DigestMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) } ?: throw LabUsageError("unknown mode \"$mode\" (keywords, topics)"),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun read(file: File): LabInput = json.decodeFromString(serializer(), file.readText())

        fun parseAttention(text: String): AttentionMode =
            AttentionMode.entries.firstOrNull { it.name.equals(text.replace('-', '_'), ignoreCase = true) }
                ?: throw LabUsageError("unknown attention mode \"$text\" (hottest, balanced, niche)")

        fun parsePersona(text: String): Persona =
            Persona.entries.firstOrNull { it.name.equals(text.replace('-', '_'), ignoreCase = true) }
                ?: throw LabUsageError("unknown persona \"$text\" (off, roach-king, doctor-wife, dog-zapper)")

        /** Display names only: the real people are named in the voice files alone (01-product.md section 9). */
        fun displayName(persona: Persona): String? = when (persona) {
            Persona.OFF -> null
            Persona.ROACH_KING -> "Roach King"
            Persona.DOCTOR_WIFE -> "Doctor Wife"
            Persona.DOG_ZAPPER -> "Dog Zapper"
        }
    }
}

class LabUsageError(message: String) : Exception(message)
