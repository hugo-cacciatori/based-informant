package io.github.hugo_cacciatori.basedinformant.data

import io.github.hugo_cacciatori.basedinformant.core.check.CheckAttempt
import io.github.hugo_cacciatori.basedinformant.core.check.CheckEntry
import io.github.hugo_cacciatori.basedinformant.core.check.CheckOutcome
import io.github.hugo_cacciatori.basedinformant.core.check.Refusal
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.run.RunSnapshot
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON text columns. Field names are part of the stored format: renaming one needs a migration, like a column.
 * Unknown fields are ignored, so a newer build's rows still open in this one.
 */
internal object StoredJson {
    val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Snapshot(
        val keywords: List<String>,
        val tags: List<String>,
        val knownTopicTags: List<String> = emptyList(),
        val attentionMode: String,
        val persona: String,
        val today: LocalDate,
        val lastDigestDate: LocalDate?,
        val wideWindow: Boolean,
        val model: String,
        val personaDisplayName: String?,
        val emailOn: Boolean,
        val recipient: String?,
    )

    @Serializable
    data class Attempt(val model: String, val result: String, val error: String? = null, val tokensBilled: Long? = null)

    @Serializable
    data class Refused(val kind: String, val index: Int, val text: String, val reason: String? = null)

    fun encode(snapshot: RunSnapshot): String = with(snapshot.prompt) {
        json.encodeToString(
            Snapshot.serializer(),
            Snapshot(
                keywords, interestTags, knownTopicTags, attentionMode.name, persona.name, today, lastDigestDate,
                wideWindow, model, snapshot.personaDisplayName, snapshot.emailOn, snapshot.recipient,
            ),
        )
    }

    fun decodeSnapshot(text: String): RunSnapshot {
        val s = json.decodeFromString(Snapshot.serializer(), text)
        return RunSnapshot(
            PromptInput(
                keywords = s.keywords,
                interestTags = s.tags,
                attentionMode = AttentionMode.valueOf(s.attentionMode),
                persona = Persona.valueOf(s.persona),
                today = s.today,
                lastDigestDate = s.lastDigestDate,
                wideWindow = s.wideWindow,
                model = s.model,
                knownTopicTags = s.knownTopicTags,
            ),
            s.personaDisplayName,
            s.emailOn,
            s.recipient,
        )
    }

    fun strings(list: List<String>): String = json.encodeToString(list)

    fun counts(map: Map<String, Int>): String = json.encodeToString(map)

    fun attempts(outcome: CheckOutcome): String = json.encodeToString(
        outcome.attempts.map { Attempt(it.model, it.result.name, it.error?.name, it.tokensBilled) },
    )

    fun refusals(outcome: CheckOutcome): String = json.encodeToString(
        (outcome as? CheckOutcome.Refused)?.refusals.orEmpty().map { Refused(it.entry.kind.name, it.entry.index, it.entry.text, it.reason) },
    )

    fun decodeAttempts(text: String): List<CheckAttempt> = json.decodeFromString<List<Attempt>>(text).map {
        CheckAttempt(it.model, CheckAttempt.Result.valueOf(it.result), it.error?.let(RfabError::valueOf), it.tokensBilled)
    }

    fun decodeRefusals(text: String): List<Refusal> = json.decodeFromString<List<Refused>>(text).map {
        Refusal(CheckEntry(CheckEntry.Kind.valueOf(it.kind), it.index, it.text), it.reason)
    }
}
