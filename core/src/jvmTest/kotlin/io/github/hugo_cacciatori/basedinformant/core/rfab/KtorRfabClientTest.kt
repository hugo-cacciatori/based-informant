package io.github.hugo_cacciatori.basedinformant.core.rfab

import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatMessage
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** No network: every response comes from a mock engine. The key is a synthetic placeholder. */
class KtorRfabClientTest {
    private val request = ChatRequest("free:test-model", listOf(ChatMessage("user", "hi")), "system", 100, 0.2)

    private val before = """{"success":true,"tokenBalance":120174,"recentTransactions":[{"id":"t1","type":"USAGE","amount":-1440}]}"""
    private val charged =
        """{"success":true,"tokenBalance":118734,"recentTransactions":[{"id":"t2","type":"USAGE","amount":-1440,"description":"Chat interaction using free:test-model"},{"id":"t1","type":"USAGE","amount":-1440}]}"""
    private val credited =
        """{"success":true,"tokenBalance":145174,"recentTransactions":[{"id":"t3","type":"ADMIN_CREDIT","amount":25000,"description":"Daily login reward"},{"id":"t1","type":"USAGE","amount":-1440}]}"""

    /** Answers each endpoint from its own list, in order; the last answer repeats. */
    private class Server(
        private val chat: List<Pair<Int, String>>,
        private val balance: List<Pair<Int, String>>,
        private val models: Pair<Int, String> = 404 to "{}",
        maxAttempts: Int = KtorRfabClient.MAX_ATTEMPTS,
    ) {
        var chatCalls = 0
        var balanceCalls = 0
        var lastChatBody = ""
        val client = KtorRfabClient(
            rfabHttpClient(
                MockEngine { req ->
                    val (status, body) = when (req.url.encodedPath) {
                        "/api/tokens/balance" -> balance[minOf(balanceCalls++, balance.size - 1)]
                        "/api/chat/completions/models" -> models
                        else -> {
                            lastChatBody = String(req.body.toByteArray())
                            chat[minOf(chatCalls++, chat.size - 1)]
                        }
                    }
                    respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
                },
            ),
            "placeholder-key",
            retryDelayMillis = { 0 },
            maxAttempts = maxAttempts,
        )
    }

    private fun server(vararg chat: Pair<Int, String>, balance: List<Pair<Int, String>> = listOf(200 to before)) =
        Server(chat.toList(), balance)

    @Test
    fun chatSuccessKeepsTheRawBody() = runBlocking {
        val body = """{"success":true,"model":"free:test-model","content":"{}","usage":{"inputTokens":147,"outputTokens":5},"tokensBilled":0,"extra":1}"""
        val server = server(200 to body)
        val ok = assertIs<RfabResult.Ok<ChatResponse>>(server.client.chat(request))
        assertEquals("{}", ok.value.content)
        assertEquals(0L, ok.value.tokensBilled)
        assertEquals(147L, ok.value.usage?.inputTokens)
        assertEquals(body, ok.raw)
        assertEquals(
            """{"model":"free:test-model","messages":[{"role":"user","content":"hi"}],"systemPrompt":"system","maxTokens":100,"temperature":0.2}""",
            server.lastChatBody,
        )
        assertEquals(1, server.balanceCalls, "the transaction ids are read once, before the request leaves")
    }

    @Test
    fun retriesOnlyTheDocumentedStatusesAtMostThreeTimes() = runBlocking {
        val failing = server(502 to """{"success":false,"code":"PROVIDER_ERROR"}""")
        val error = assertIs<RfabResult.Error>(failing.client.chat(request))
        assertEquals(RfabError.UNAVAILABLE, error.kind)
        assertEquals(3, failing.chatCalls)

        val recovering = server(429 to "{}", 200 to """{"success":true,"content":"x"}""")
        assertIs<RfabResult.Ok<ChatResponse>>(recovering.client.chat(request))
        assertEquals(2, recovering.chatCalls)
        assertEquals(1, recovering.balanceCalls, "a 429 is refused before it runs: no charge check")
    }

    @Test
    fun theInputCheckClientNeverRetries() = runBlocking {
        val limited = Server(listOf(429 to """{"success":false,"code":"RATE_LIMITED"}"""), listOf(200 to before), maxAttempts = 1)
        val error = assertIs<RfabResult.Error>(limited.client.chat(request))
        assertEquals(RfabError.UNAVAILABLE, error.kind)
        assertEquals(1, limited.chatCalls)
    }

    @Test
    fun aServerErrorWithNoNewChargeIsRetried() = runBlocking {
        val server = server(500 to """{"success":false,"code":"INTERNAL_ERROR"}""", 200 to """{"success":true,"content":"x"}""")
        assertIs<RfabResult.Ok<ChatResponse>>(server.client.chat(request))
        assertEquals(2, server.chatCalls)
        assertEquals(2, server.balanceCalls)
    }

    @Test
    fun aChargeAfterAServerErrorStopsTheRetry() = runBlocking {
        val server = server(502 to """{"success":false,"code":"PROVIDER_ERROR"}""", balance = listOf(200 to before, 200 to charged))
        val error = assertIs<RfabResult.Error>(server.client.chat(request))
        assertEquals(RfabError.FAILED_BILLED, error.kind)
        assertEquals(1440L, error.tokensBilled)
        assertEquals(1, server.chatCalls)
    }

    @Test
    fun aDailyCreditIsNotACharge() = runBlocking {
        val server = server(
            500 to """{"success":false,"code":"INTERNAL_ERROR"}""",
            200 to """{"success":true,"content":"x"}""",
            balance = listOf(200 to before, 200 to credited),
        )
        assertIs<RfabResult.Ok<ChatResponse>>(server.client.chat(request))
        assertEquals(2, server.chatCalls)
    }

    @Test
    fun anUncheckableChargeIsNeverRetried() = runBlocking {
        val serverError = 500 to """{"success":false,"code":"INTERNAL_ERROR"}"""
        val noBaseline = server(serverError, balance = listOf(401 to """{"success":false,"code":"INVALID_API_KEY"}""", 200 to before))
        assertEquals(RfabError.FAILED_MAYBE_BILLED, assertIs<RfabResult.Error>(noBaseline.client.chat(request)).kind)
        assertEquals(1, noBaseline.chatCalls)

        val noCheck = server(serverError, balance = listOf(200 to before, 401 to """{"success":false,"code":"INVALID_API_KEY"}"""))
        assertEquals(RfabError.FAILED_MAYBE_BILLED, assertIs<RfabResult.Error>(noCheck.client.chat(request)).kind)
        assertEquals(1, noCheck.chatCalls)
    }

    @Test
    fun errorsMapToTheirKindsWithoutRetry() = runBlocking {
        val cases = listOf(
            401 to """{"success":false,"code":"INVALID_API_KEY"}""" to RfabError.KEY_REFUSED,
            402 to """{"success":false,"code":"INSUFFICIENT_TOKENS"}""" to RfabError.INSUFFICIENT_TOKENS,
            403 to """{"success":false,"code":"INSUFFICIENT_TOKENS"}""" to RfabError.INSUFFICIENT_TOKENS,
            403 to """{"success":false,"code":"OTHER"}""" to RfabError.FORBIDDEN,
            404 to "not json" to RfabError.UNEXPECTED_STATUS,
        )
        for ((response, kind) in cases) {
            val server = server(response)
            assertEquals(kind, assertIs<RfabResult.Error>(server.client.chat(request)).kind)
            assertEquals(1, server.chatCalls)
        }
    }

    @Test
    fun aNetworkFailureIsNeverRetried() = runBlocking {
        var chatCalls = 0
        val engine = MockEngine { req ->
            if (req.url.encodedPath == "/api/chat/completions") chatCalls++
            throw IOException("connection reset")
        }
        val client = KtorRfabClient(rfabHttpClient(engine), "placeholder-key", retryDelayMillis = { 0 })
        assertEquals(RfabError.NETWORK, assertIs<RfabResult.Error>(client.chat(request)).kind)
        assertEquals(1, chatCalls)
    }

    @Test
    fun badSuccessBodyIsKeptRaw() = runBlocking {
        val server = server(200 to "<html>oops</html>")
        val error = assertIs<RfabResult.Error>(server.client.chat(request))
        assertEquals(RfabError.BAD_RESPONSE, error.kind)
        assertEquals("<html>oops</html>", error.raw)
    }

    @Test
    fun balanceAndModels() = runBlocking {
        val balanceServer = server(balance = listOf(200 to """{"success":true,"tokenBalance":916083,"recentTransactions":[{"id":"t1","type":"USAGE","amount":-3000}]}"""))
        val balance = assertIs<RfabResult.Ok<Balance>>(balanceServer.client.balance()).value
        assertEquals(916083L, balance.tokenBalance)
        assertEquals(-3000L, balance.recentTransactions.single().amount)

        val modelsServer = Server(emptyList(), emptyList(), models = 200 to """{"success":true,"count":1,"models":[{"model":"free:a","rateMultiplier":0}]}""")
        assertEquals(listOf("free:a"), assertIs<RfabResult.Ok<ModelCatalog>>(modelsServer.client.models()).value.freeModels())
    }
}

class ModelCatalogTest {
    private fun catalog(json: String) = Json { ignoreUnknownKeys = true }.decodeFromString(ModelCatalog.serializer(), json)

    @Test
    fun theSavedCatalogParses() {
        val text = requireNotNull(javaClass.getResource("/fixtures/models-2026-10-18.json")).readText()
        val catalog = catalog(text)
        assertEquals(170, catalog.models.size)
        assertEquals(0.36, catalog.multiplierOf("openrouter:perplexity/sonar"))
        assertEquals(1.08, catalog.multiplierOf("openrouter:perplexity/sonar-pro"))
        assertEquals(1.63, catalog.multiplierOf("gemini:gemini-3-flash-preview-search"))
        assertEquals(37, catalog.freeModels().size)
        assertEquals(0.0, catalog.multiplierOf("openrouter:meta-llama/llama-3.3-70b-instruct"))
    }

    @Test
    fun missingOrDuplicatedModelsAreUnknown() {
        assertNull(catalog("""{"models":[]}""").multiplierOf("a"))
        assertNull(catalog("""{"models":[{"model":"a","rateMultiplier":0},{"model":"a","rateMultiplier":0}]}""").multiplierOf("a"))
    }
}
