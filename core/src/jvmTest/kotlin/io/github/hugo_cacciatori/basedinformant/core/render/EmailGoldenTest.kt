package io.github.hugo_cacciatori.basedinformant.core.render

import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * 07-testing.md: every change to the email shows up in the diff of these files and is reviewed on purpose.
 * On a difference, the new output goes to build/golden-actual/; copy it to src/jvmTest/resources/golden/ if intended.
 */
class EmailGoldenTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val meta = RenderMeta(personaDisplayName = null, model = "perplexity:sonar")

    private fun fixture(name: String): Digest =
        json.decodeFromString(Digest.serializer(), requireNotNull(javaClass.getResource("/fixtures/$name")) { name }.readText())

    /** Hostile model text: markup, a script, quotes and a non-https link, on a single-source topic. */
    private fun hostile(): Digest {
        val good = fixture("good.json")
        val first = good.topics.first()
        return good.copy(
            opening = "<b>Bold</b> & \"quoted\" 'text'",
            topics = listOf(
                first.copy(
                    title = "<script>alert('x')</script> & co",
                    summary = "<img src=x onerror=alert(1)>",
                    detail = "</details><a href=\"javascript:alert(1)\">click</a>",
                    sources = listOf(first.sources.first().copy(url = "javascript:alert(1)", publisher = "<i>Wire</i>")),
                ),
            ),
        )
    }

    private val cases: Map<String, Pair<Digest, RenderMeta>> by lazy {
        mapOf(
            "good" to (fixture("good.json") to meta),
            "persona" to (fixture("persona-roach-king.json") to meta.copy(personaDisplayName = "Roach King")),
            "empty" to (fixture("empty.json") to meta),
            "hostile" to (hostile() to meta),
        )
    }

    @Test
    fun goldenHtmlAndText() {
        val differ = mutableListOf<String>()
        for ((name, case) in cases) {
            val rendered = DigestRenderer.render(case.first, case.second)
            compare("email-$name.html", rendered.html, differ)
            compare("email-$name.txt", "Subject: ${rendered.subject}\n\n${rendered.text}", differ)
        }
        if (differ.isNotEmpty()) fail("Golden email files differ: $differ. Review build/golden-actual/.")
    }


    private fun compare(file: String, actual: String, differ: MutableList<String>) {
        val expected = javaClass.getResource("/golden/$file")?.readText()?.replace("\r\n", "\n")
        if (actual != expected) {
            File("build/golden-actual/$file").apply { parentFile.mkdirs(); writeText(actual) }
            differ += file
        }
    }
}
