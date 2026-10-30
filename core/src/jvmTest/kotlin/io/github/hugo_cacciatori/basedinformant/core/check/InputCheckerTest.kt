package io.github.hugo_cacciatori.basedinformant.core.check

import io.github.hugo_cacciatori.basedinformant.core.check.CheckAttempt.Result
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.rfab.Balance
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelRate
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabError
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class InputCheckerTest {
    private val models = listOf("free:a", "free:b", "free:c")

    /** Answers each model from [answers]; a model with no scripted answer, or asked twice, fails the test. */
    private class ScriptedRfab(
        private val answers: Map<String, RfabResult<ChatResponse>>,
        private val catalog: RfabResult<ModelCatalog> = RfabResult.Ok(
            ModelCatalog(true, 3, listOf("free:a", "free:b", "free:c").map { ModelRate(it, 0.0) }),
            "{}",
        ),
    ) : RfabClient {
        val asked = mutableListOf<String>()

        override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
            if (request.model in asked) fail("${request.model} asked twice")
            asked += request.model
            return answers[request.model] ?: fail("${request.model} was not expected")
        }

        override suspend fun balance(): RfabResult<Balance> = fail("no balance call in a check")

        override suspend fun models(): RfabResult<ModelCatalog> = catalog
    }

    private fun answer(content: String?) = RfabResult.Ok(ChatResponse(success = true, content = content, tokensBilled = 0), "{}")

    private fun error(kind: RfabError) = RfabResult.Error(kind)

    private fun check(rfab: ScriptedRfab) = runBlocking { InputChecker(rfab, models).check(listOf("chess", "xqzvbt"), listOf("ocean")) }

    @Test
    fun theFirstModelAnswers() {
        val rfab = ScriptedRfab(mapOf("free:a" to answer("{\"refused\": []}")))
        val outcome = check(rfab)
        assertIs<CheckOutcome.Passed>(outcome)
        assertEquals(listOf("free:a"), rfab.asked)
        assertEquals(listOf(CheckAttempt("free:a", Result.ANSWERED, tokensBilled = 0)), outcome.attempts)
    }

    @Test
    fun refusalsNameTheEntry() {
        val rfab = ScriptedRfab(mapOf("free:a" to answer("{\"refused\": [{\"n\": 2, \"reason\": \"random letters\"}]}")))
        val outcome = assertIs<CheckOutcome.Refused>(check(rfab))
        assertEquals(listOf(Refusal(CheckEntry(CheckEntry.Kind.KEYWORD, 1, "xqzvbt"), "random letters")), outcome.refusals)
    }

    @Test
    fun aRefusingOrUnreadableModelPassesToTheNext() {
        val rfab = ScriptedRfab(
            mapOf(
                "free:a" to error(RfabError.UNAVAILABLE),
                "free:b" to answer("I cannot help with that."),
                "free:c" to answer("{\"refused\": [{\"n\": 3}]}"),
            ),
        )
        val outcome = assertIs<CheckOutcome.Refused>(check(rfab))
        assertEquals(CheckEntry(CheckEntry.Kind.TAG, 0, "ocean"), outcome.refusals.single().entry)
        assertEquals(listOf(Result.FAILED, Result.UNREADABLE, Result.ANSWERED), outcome.attempts.map { it.result })
        assertEquals(RfabError.UNAVAILABLE, outcome.attempts[0].error)
    }

    @Test
    fun noModelAnswersSoTheRunGoesOn() {
        val rfab = ScriptedRfab(models.associateWith { error(RfabError.UNAVAILABLE) })
        val outcome = assertIs<CheckOutcome.NoAnswer>(check(rfab))
        assertEquals(3, outcome.attempts.size)
        assertEquals(models, rfab.asked)
    }

    @Test
    fun aModelNotAtZeroIsNeverAsked() {
        val catalog = ModelCatalog(true, 3, listOf(ModelRate("free:a", 0.36), ModelRate("free:c", 0.0)))
        val rfab = ScriptedRfab(mapOf("free:c" to answer("{\"refused\": []}")), RfabResult.Ok(catalog, "{}"))
        val outcome = assertIs<CheckOutcome.Passed>(check(rfab))
        assertEquals(listOf("free:c"), rfab.asked)
        assertEquals(listOf(Result.NOT_FREE, Result.NOT_FREE, Result.ANSWERED), outcome.attempts.map { it.result })
    }

    @Test
    fun anAnswerRfabReroutedIsNotUsed() {
        // 2026-10-30: rfab served a fact check from another, weaker model; the next chosen model is asked instead.
        val rerouted = RfabResult.Ok(ChatResponse(success = true, content = "{\"refused\": []}", tokensBilled = 0, servedVia = "other:model", rerouted = true), "{}")
        val rfab = ScriptedRfab(mapOf(models[0] to rerouted, models[1] to answer("{\"refused\": []}")))
        val outcome = assertIs<CheckOutcome.Passed>(check(rfab))
        assertEquals(listOf(Result.REROUTED, Result.ANSWERED), outcome.attempts.map { it.result })
    }

    @Test
    fun noModelsListNoCheck() {
        val rfab = ScriptedRfab(emptyMap(), error(RfabError.NETWORK))
        val outcome = assertIs<CheckOutcome.NoAnswer>(check(rfab))
        assertEquals(false, outcome.modelsListRead)
        assertEquals(emptyList(), rfab.asked)
    }

    @Test
    fun aRefusedKeyStopsTheCheck() {
        val rfab = ScriptedRfab(mapOf("free:a" to error(RfabError.KEY_REFUSED)))
        assertIs<CheckOutcome.NoAnswer>(check(rfab))
        assertEquals(listOf("free:a"), rfab.asked)
    }

    @Test
    fun nothingToCheckSendsNothing() {
        val rfab = ScriptedRfab(emptyMap(), error(RfabError.NETWORK))
        assertIs<CheckOutcome.Passed>(runBlocking { InputChecker(rfab, models).check(emptyList(), emptyList()) })
    }
}
