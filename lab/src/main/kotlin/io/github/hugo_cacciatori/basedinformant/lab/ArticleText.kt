package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.PageText
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Downloads an article page for lab writer runs (`--fetch-articles`) and extracts its text with the app's own
 * [PageText]. In the app the link check reads each page once and keeps the text.
 */
object ArticleText {
    private const val MAX_PAGE_BYTES = 300_000

    fun extract(html: String): String? = PageText.extract(html)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build()
    }

    /** Downloads [url] (free: the public page, no tokens) and extracts its text; null on any failure. */
    fun fetch(url: String): String? = try {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) BasedInformantLab/0.0.1")
            .header("Accept", "text/html").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            null
        } else {
            val bytes = response.body().use { it.readNBytes(MAX_PAGE_BYTES) }
            extract(String(bytes, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        null
    }
}
