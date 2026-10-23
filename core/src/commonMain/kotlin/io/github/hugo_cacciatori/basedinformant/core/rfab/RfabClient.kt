package io.github.hugo_cacciatori.basedinformant.core.rfab

import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Every rfab call goes through this interface (ai-call.md section 1), so an API change touches one place. */
interface RfabClient {
    /** Spends tokens unless the model is free (0x). The caller asks the owner first. */
    suspend fun chat(request: ChatRequest): RfabResult<ChatResponse>

    /** Free. */
    suspend fun balance(): RfabResult<Balance>

    /** Free. Model ids with their billing multipliers. */
    suspend fun models(): RfabResult<ModelCatalog>
}

sealed interface RfabResult<out T> {
    /** [raw] is the response body as received, for saving before anything else. */
    data class Ok<T>(val value: T, val raw: String) : RfabResult<T>

    /** [tokensBilled] is set for [RfabError.FAILED_BILLED]: the charge the balance check found. */
    data class Error(
        val kind: RfabError,
        val httpStatus: Int? = null,
        val code: String? = null,
        val raw: String? = null,
        val tokensBilled: Long? = null,
    ) : RfabResult<Nothing>
}

enum class RfabError {
    /** 401: nothing billed. */
    KEY_REFUSED,

    /** 402, or 403 with `INSUFFICIENT_TOKENS`: nothing billed. */
    INSUFFICIENT_TOKENS,

    /** Another 403. */
    FORBIDDEN,

    /** 429, or a 500 or 502 the balance check found unbilled, after the documented retries: nothing billed. */
    UNAVAILABLE,

    /** A chat 500 or 502 that the balance check found charged: not retried. */
    FAILED_BILLED,

    /** A chat 500 or 502 whose charge could not be checked: it may have been billed, and is never re-sent. */
    FAILED_MAYBE_BILLED,

    /** No answer after the request may have left: it may have been billed, and is never re-sent. */
    NETWORK,

    /** A 2xx whose body is not the documented shape. The raw body is kept. */
    BAD_RESPONSE,

    /** Any other status. */
    UNEXPECTED_STATUS,
}

@Serializable
data class ChatResponse(
    val success: Boolean = false,
    val model: String? = null,
    val content: String? = null,
    val usage: Usage? = null,
    val tokensBilled: Long? = null,
    val servedVia: String? = null,
    val rerouted: Boolean? = null,
)

@Serializable
data class Usage(val inputTokens: Long? = null, val outputTokens: Long? = null)

@Serializable
data class Balance(
    val tokenBalance: Long,
    val totalTokensUsed: Long? = null,
    val totalInteractions: Long? = null,
    val recentTransactions: List<Transaction> = emptyList(),
)

@Serializable
data class Transaction(
    val id: String,
    val type: String? = null,
    val amount: Long? = null,
    val description: String? = null,
    val createdAt: String? = null,
)

@Serializable
private data class ErrorBody(val code: String? = null)

/** An HTTP client with the timeouts of ai-call.md section 1: the client never gives up before rfab's 10-minute cut. */
fun rfabHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 11 * 60 * 1000L
        socketTimeoutMillis = 11 * 60 * 1000L
        connectTimeoutMillis = 30 * 1000L
    }
}

/**
 * The rfab API over Ktor. Retries only 429, 500 and 502, waiting 2-5 s, at most [maxAttempts] attempts in all; the
 * free input check uses 1, since it moves to the next model instead (ai-call.md section 12).
 * rfab keeps the charge for a call its provider ran, so a chat 500 or 502 is retried only when the free balance
 * check shows no new charge (ai-call.md section 1). A network failure is never retried.
 */
class KtorRfabClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = BASE_URL,
    private val retryDelayMillis: (attempt: Int) -> Long = { attempt -> 2_000L + 1_500L * (attempt - 1) },
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : RfabClient {
    override suspend fun chat(request: ChatRequest): RfabResult<ChatResponse> {
        // Read before the request leaves, so a charge it causes shows up as a new row.
        val knownIds = (balance() as? RfabResult.Ok)?.value?.recentTransactions?.mapTo(mutableSetOf()) { it.id }
        return call(ChatResponse.serializer(), chargeCheck = { chargeSince(knownIds) }) {
            http.post("$baseUrl/api/chat/completions") {
                header(KEY_HEADER, apiKey)
                setBody(TextContent(json.encodeToString(ChatRequest.serializer(), request), ContentType.Application.Json))
            }
        }
    }

    override suspend fun balance(): RfabResult<Balance> =
        call(Balance.serializer()) { http.get("$baseUrl/api/tokens/balance") { header(KEY_HEADER, apiKey) } }

    override suspend fun models(): RfabResult<ModelCatalog> =
        call(ModelCatalog.serializer()) { http.get("$baseUrl/api/chat/completions/models") { header(KEY_HEADER, apiKey) } }

    /**
     * Any new row with a charge counts, whatever it names (another device's call too): when in doubt, never re-send.
     * With no rows from before the request, or no balance now, the charge is unknown.
     */
    private suspend fun chargeSince(knownIds: Set<String>?): Charge {
        if (knownIds == null) return Charge.Unknown
        val now = (balance() as? RfabResult.Ok)?.value ?: return Charge.Unknown
        val charges = now.recentTransactions.filter { it.id !in knownIds && (it.type == "USAGE" || (it.amount ?: 0L) < 0L) }
        return if (charges.isEmpty()) Charge.None else Charge.Billed(-charges.sumOf { minOf(it.amount ?: 0L, 0L) })
    }

    private sealed interface Charge {
        data object None : Charge
        data class Billed(val tokens: Long) : Charge
        data object Unknown : Charge
    }

    /** [chargeCheck] is set for the paid call: it runs after a 500 or 502, following the usual wait. */
    private suspend fun <T> call(
        serializer: KSerializer<T>,
        chargeCheck: (suspend () -> Charge)? = null,
        send: suspend () -> HttpResponse,
    ): RfabResult<T> {
        var attempt = 1
        while (true) {
            val response = try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return RfabResult.Error(RfabError.NETWORK)
            }
            val status = response.status.value
            val body = try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return RfabResult.Error(RfabError.NETWORK, status)
            }
            if (status in 200..299) return decode(serializer, body, status)
            val code = errorCode(body)
            if (status in SERVER_ERRORS && chargeCheck != null) {
                // The wait comes first, so a charge row written a little late has time to appear.
                delay(retryDelayMillis(attempt))
                when (val charge = chargeCheck()) {
                    is Charge.Billed -> return RfabResult.Error(RfabError.FAILED_BILLED, status, code, body, charge.tokens)
                    Charge.Unknown -> return RfabResult.Error(RfabError.FAILED_MAYBE_BILLED, status, code, body)
                    Charge.None -> if (attempt < maxAttempts) {
                        attempt++
                        continue
                    }
                }
            } else if (status in RETRYABLE && attempt < maxAttempts) {
                delay(retryDelayMillis(attempt))
                attempt++
                continue
            }
            val kind = when {
                status == 401 -> RfabError.KEY_REFUSED
                status == 402 || (status == 403 && code == "INSUFFICIENT_TOKENS") -> RfabError.INSUFFICIENT_TOKENS
                status == 403 -> RfabError.FORBIDDEN
                status in RETRYABLE -> RfabError.UNAVAILABLE
                else -> RfabError.UNEXPECTED_STATUS
            }
            return RfabResult.Error(kind, status, code, body)
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, body: String, status: Int): RfabResult<T> =
        try {
            RfabResult.Ok(json.decodeFromString(serializer, body), body)
        } catch (_: SerializationException) {
            RfabResult.Error(RfabError.BAD_RESPONSE, status, raw = body)
        } catch (_: IllegalArgumentException) {
            RfabResult.Error(RfabError.BAD_RESPONSE, status, raw = body)
        }

    private fun errorCode(body: String): String? = try {
        json.decodeFromString(ErrorBody.serializer(), body).code
    } catch (_: Exception) {
        null
    }

    companion object {
        const val BASE_URL = "https://api.rfab.ai"
        const val KEY_HEADER = "X-API-Key"
        const val MAX_ATTEMPTS = 3
        private val RETRYABLE = setOf(429, 500, 502)
        private val SERVER_ERRORS = setOf(500, 502)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
