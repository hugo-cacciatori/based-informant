package io.github.hugo_cacciatori.basedinformant.core.check

import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult

/**
 * One free job tried on a list of models, best first (02-ai-call.md section 12): the input check, the writer, the fact
 * check, the repair. Never spends tokens: a model is used only when [catalog], read just before, shows it at exactly
 * 0x. One request at a time, at most one per model: the client must not retry (`KtorRfabClient(maxAttempts = 1)`), so a
 * model at its usage limit passes to the next at once. A refused key stops the cycle. An answer rfab rerouted to another
 * model is not used: the owner chose which models do each job (2026-10-29), and a rerouted fact check judged badly.
 */
object FreeModels {
    data class Result<T>(val value: T?, val model: String?, val attempts: List<CheckAttempt>)

    suspend fun <T> first(
        client: RfabClient,
        catalog: ModelCatalog,
        models: List<String>,
        request: (String) -> ChatRequest,
        parse: (String) -> T?,
    ): Result<T> {
        val attempts = mutableListOf<CheckAttempt>()
        for (model in models) {
            if (catalog.multiplierOf(model) != 0.0) {
                attempts += CheckAttempt(model, CheckAttempt.Result.NOT_FREE)
                continue
            }
            when (val response = client.chat(request(model))) {
                is RfabResult.Error -> {
                    attempts += CheckAttempt(model, CheckAttempt.Result.FAILED, response.kind, response.tokensBilled)
                    if (response.kind == RfabError.KEY_REFUSED) break
                }
                is RfabResult.Ok -> {
                    val billed = response.value.tokensBilled
                    if (response.value.rerouted == true) {
                        attempts += CheckAttempt(model, CheckAttempt.Result.REROUTED, tokensBilled = billed)
                        continue
                    }
                    val value = response.value.content?.let(parse)
                    if (value == null) {
                        attempts += CheckAttempt(model, CheckAttempt.Result.UNREADABLE, tokensBilled = billed)
                        continue
                    }
                    attempts += CheckAttempt(model, CheckAttempt.Result.ANSWERED, tokensBilled = billed)
                    return Result(value, model, attempts)
                }
            }
        }
        return Result(null, null, attempts)
    }

    /**
     * The writer's order (owner, 2026-10-23, performance first; 02-ai-call.md section 12): MiniMax M3, Kimi K2.6, then the
     * input check's tested models, DeepSeek V4 Flash after the faithful writers and Qwen 3.8 last among the tested.
     */
    val WRITER = listOf(
        "openrouter:minimax/minimax-m3",
        "openrouter:moonshotai/kimi-k2.6",
        "openrouter:z-ai/glm-4.7-flash",
        "openrouter:deepseek/deepseek-v4-flash",
        "openrouter:qwen/qwen3.8-27b",
    ) + InputChecker.ROTATION.filter {
        it !in setOf(
            "openrouter:minimax/minimax-m3", "openrouter:moonshotai/kimi-k2.6", "openrouter:z-ai/glm-4.7-flash",
            "openrouter:deepseek/deepseek-v4-flash", "openrouter:qwen/qwen3.8-27b",
        )
    }

    /** The fact check and the repair: the two models tested on them, and no other (owner, 2026-10-29). */
    val CHECKER = listOf("openrouter:minimax/minimax-m3", "openrouter:moonshotai/kimi-k2.6")
}
