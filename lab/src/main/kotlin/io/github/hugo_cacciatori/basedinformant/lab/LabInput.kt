package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.memory.CuratedTopic
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
 * keywords and tags go in a file under the git-ignored `lab-out/` (security.md section 4).
 */
@Serializable
data class LabInput(
    val keywords: List<String>,
    @SerialName("interest_tags") val interestTags: List<String> = emptyList(),
    @SerialName("attention_mode") val attentionMode: String = "balanced",
    val persona: String = "off",
    /** Defaults to the computer's date. */
    val today: String? = null,
    @SerialName("last_digest") val lastDigest: String? = null,
    val memory: List<MemoryEntry> = emptyList(),
    /** Topic tags of earlier digests, for the redesign's writer (ai-call.md section 9). */
    @SerialName("known_topic_tags") val knownTopicTags: List<String> = emptyList(),
) {
    @Serializable
    data class MemoryEntry(
        val id: String,
        val title: String,
        val gist: String,
        @SerialName("first_featured") val firstFeatured: String,
        @SerialName("last_featured") val lastFeatured: String,
        @SerialName("times_featured") val timesFeatured: Int,
        @SerialName("last_score") val lastScore: Int,
    )

    fun todayDate(): LocalDate = today?.let(LocalDate::parse) ?: LocalDate.parse(java.time.LocalDate.now().toString())

    fun toPromptInput(model: String, personaOverride: Persona? = null): PromptInput = PromptInput(
        keywords = keywords,
        interestTags = interestTags,
        memory = memory.map {
            CuratedTopic(
                it.id, it.title, it.gist, LocalDate.parse(it.firstFeatured), LocalDate.parse(it.lastFeatured),
                it.timesFeatured, it.lastScore,
            )
        },
        attentionMode = parseAttention(attentionMode),
        persona = personaOverride ?: parsePersona(persona),
        today = todayDate(),
        lastDigestDate = lastDigest?.let(LocalDate::parse),
        model = model,
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

        /** Display names only: the real people are named in the voice files alone (product.md section 9). */
        fun displayName(persona: Persona): String? = when (persona) {
            Persona.OFF -> null
            Persona.ROACH_KING -> "Roach King"
            Persona.DOCTOR_WIFE -> "Doctor Wife"
            Persona.DOG_ZAPPER -> "Dog Zapper"
        }
    }
}

class LabUsageError(message: String) : Exception(message)
