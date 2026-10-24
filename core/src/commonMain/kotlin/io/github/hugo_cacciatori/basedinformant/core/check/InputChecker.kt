package io.github.hugo_cacciatori.basedinformant.core.check

import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult

/** What one model did in a check, for the call history. */
data class CheckAttempt(val model: String, val result: Result, val error: RfabError? = null, val tokensBilled: Long? = null) {
    enum class Result { ANSWERED, NOT_FREE, FAILED, UNREADABLE }
}

/** The check's outcome. Only [Refused] is shown to the owner; the rest lets the run go on (01-product.md section 4.4). */
sealed interface CheckOutcome {
    val attempts: List<CheckAttempt>

    data class Passed(override val attempts: List<CheckAttempt>) : CheckOutcome

    data class Refused(val refusals: List<Refusal>, override val attempts: List<CheckAttempt>) : CheckOutcome

    /** No free model answered, or the models list could not be read: the run goes on without a word. */
    data class NoAnswer(override val attempts: List<CheckAttempt>, val modelsListRead: Boolean = true) : CheckOutcome
}

/** One check, for the call history (01-product.md section 4.5). Never shown in the app. */
data class CheckRecord(val at: Long, val entries: Int, val outcome: CheckOutcome)

/**
 * Runs the free input check with model rotation (02-ai-call.md section 12). Never spends tokens: a model is used only
 * when the models list, read just before, shows it at exactly 0x. One request at a time, at most one per model:
 * [client] must not retry (`KtorRfabClient(maxAttempts = 1)`), so a model at its usage limit passes to the next at once.
 */
class InputChecker(private val client: RfabClient, private val models: List<String> = ROTATION) {
    suspend fun check(keywords: List<String>, tags: List<String>): CheckOutcome {
        val entries = InputCheck.entries(keywords, tags)
        if (entries.isEmpty()) return CheckOutcome.Passed(emptyList())
        val catalog = (client.models() as? RfabResult.Ok)?.value ?: return CheckOutcome.NoAnswer(emptyList(), modelsListRead = false)
        val attempts = mutableListOf<CheckAttempt>()
        for (model in models) {
            if (catalog.multiplierOf(model) != 0.0) {
                attempts += CheckAttempt(model, CheckAttempt.Result.NOT_FREE)
                continue
            }
            when (val response = client.chat(InputCheck.request(model, entries))) {
                is RfabResult.Error -> {
                    attempts += CheckAttempt(model, CheckAttempt.Result.FAILED, response.kind, response.tokensBilled)
                    if (response.kind == RfabError.KEY_REFUSED) break
                }
                is RfabResult.Ok -> {
                    val billed = response.value.tokensBilled
                    val refusals = response.value.content?.let { InputCheck.parse(it, entries) }
                    if (refusals == null) {
                        attempts += CheckAttempt(model, CheckAttempt.Result.UNREADABLE, tokensBilled = billed)
                        continue
                    }
                    attempts += CheckAttempt(model, CheckAttempt.Result.ANSWERED, tokensBilled = billed)
                    return if (refusals.isEmpty()) CheckOutcome.Passed(attempts) else CheckOutcome.Refused(refusals, attempts)
                }
            }
        }
        return CheckOutcome.NoAnswer(attempts)
    }

    companion object {
        /**
         * 02-ai-call.md section 12 (owner, 2026-10-20): the models that passed the lab list, best first, families
         * alternating; ids without `:free` first, the `:free` ones as a fallback.
         */
        val ROTATION = listOf(
            "openrouter:deepseek/deepseek-v4-flash",
            "openrouter:qwen/qwen3.8-27b",
            "openrouter:z-ai/glm-4.7-flash",
            "openrouter:moonshotai/kimi-k2.6",
            "openrouter:minimax/minimax-m3",
            "openrouter:deepseek/deepseek-v4-flash-0731",
            "openrouter:meta-llama/llama-3.3-70b-instruct",
            "openrouter:qwen/qwen3.7-flash",
            "openrouter:mistralai/mistral-medium-3.1",
            "openrouter:google/gemma-4-31b-it:free",
            "openrouter:nvidia/nemotron-3-super-120b-a12b:free",
            "openrouter:nvidia/nemotron-3-ultra-550b-a55b:free",
            "openrouter:minimax/minimax-m3:free",
        )
    }
}
