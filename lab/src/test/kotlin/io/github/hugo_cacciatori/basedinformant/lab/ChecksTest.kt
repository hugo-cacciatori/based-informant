package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.RecencyWindow
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the fact check and the repair of the call redesign (02-ai-call.md section 12, items 7, 8 and 10) on synthetic data. */
class ChecksTest {
    private val repo = File("").absoluteFile.parentFile
    private val searchText = repo.resolve("lab/inputs/search-answer-synthetic.json").readText()
    private val synthetic = SearchAnswer.read(searchText).numbered()
    private val withPages = synthetic.withPages(mapOf("https://science.example.com/2026/10/stellarator-record" to "The stellarator held plasma for 40 minutes."))

    private fun topic(story: Int, summary: String, detail: String? = null, sources: List<Int> = emptyList()) =
        WriterAnswer.WriterTopic(story, "Tag", "Title $story", summary, detail, listOf("k"), 40, sources)

    private val written = WriterAnswer(
        2, "Hi.", "Bye.", "S", "C",
        listOf(
            topic(1, "A plasma lasted 40 minutes. It cost 2 billion.", "The lab plans more. Ha, cool.", listOf(1)),
            topic(2, "A vote is set. Twelve states back it."),
        ),
    )

    private fun check(vararg cards: FactCheckAnswer.Card) = FactCheckAnswer(cards.toList())

    @Test
    fun `the request numbers summary and detail sentences and sends page text, or facts when no page`() {
        val content = FactCheck.request("m", written, withPages).messages.single().content
        assertTrue("\"s\":4,\"text\":\"Ha, cool.\"" in content)
        assertTrue("\"page_text\":\"The stellarator held plasma for 40 minutes.\"" in content)
        assertTrue("Twelve member states back the pause." in content)
    }

    @Test
    fun `unsupported sentences are deleted only on a card with a downloaded page`() {
        val result = check(
            FactCheckAnswer.Card(1, listOf(FactCheckAnswer.Unsupported(2, "no cost given"))),
            FactCheckAnswer.Card(2, listOf(FactCheckAnswer.Unsupported(2))),
        )
        val (checked, findings) = FactCheck.apply(written, withPages, result)
        assertEquals("A plasma lasted 40 minutes.", checked.topics[0].summary)
        assertEquals("The lab plans more. Ha, cool.", checked.topics[0].detail)
        assertEquals("A vote is set. Twelve states back it.", checked.topics[1].summary)
        assertEquals(listOf("UNSUPPORTED", "UNCHECKED_NO_PAGE"), findings.map { it.kind })
    }

    @Test
    fun `a second story's sentences go, and an unclear split drops the card`() {
        val cut = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, secondStory = listOf(2)), FactCheckAnswer.Card(1)))
        assertEquals("A vote is set.", cut.first.topics[1].summary)
        val dropped = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, unclear = true), FactCheckAnswer.Card(1)))
        assertEquals(1, dropped.first.topics.size)
        assertEquals("DROPPED_TWO_STORIES", dropped.second.single().kind)
        val whole = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, secondStory = listOf(1, 2)), FactCheckAnswer.Card(1)))
        assertEquals(written.topics, whole.first.topics)
        assertEquals("SECOND_STORY_IGNORED", whole.second.single().kind)
    }

    @Test
    fun `an emptied summary takes the detail, and a card left with nothing is dropped`() {
        val one = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, listOf(1, 2).map { FactCheckAnswer.Unsupported(it) }), FactCheckAnswer.Card(2)))
        assertEquals("The lab plans more. Ha, cool.", one.first.topics[0].summary)
        assertNull(one.first.topics[0].detail)
        val all = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, (1..4).map { FactCheckAnswer.Unsupported(it) }), FactCheckAnswer.Card(2)))
        assertEquals(listOf(2), all.first.topics.map { it.story })
        assertEquals("DROPPED_EMPTY", all.second.last().kind)
    }

    @Test
    fun `a card the check left out stays as written, and bad sentence numbers are ignored`() {
        val (checked, findings) = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, listOf(FactCheckAnswer.Unsupported(9)))))
        assertEquals(written.topics, checked.topics)
        assertEquals(listOf("BAD_SENTENCE_NUMBER", "NOT_CHECKED"), findings.map { it.kind })
    }

    @Test
    fun `a sentence is not split after an abbreviation before a small letter`() {
        assertEquals(
            listOf("Quantum Computing Inc. announced a deal.", "It starts in 2027."),
            Writer.sentences("Quantum Computing Inc. announced a deal. It starts in 2027."),
        )
    }

    @Test
    fun `the fact check answer is read through fences and refused without JSON`() {
        val parsed = FactCheck.parse("```json\n{\"cards\": [{\"card\": 1, \"unsupported\": [{\"s\": 2, \"why\": \"x\"}], \"two_stories\": false}]}\n```")
        assertEquals(2, parsed?.cards?.single()?.unsupported?.single()?.s)
        assertNull(FactCheck.parse("I cannot check this."))
        val mixed = FactCheck.parse("{\"cards\": [{\"card\": 1, \"unsupported\": [3], \"two_stories\": true, \"second_story\": [{\"s\": 2, \"why\": \"y\"}]}]}")
        assertEquals(3, mixed?.cards?.single()?.unsupported?.single()?.s)
        assertEquals(listOf(2), mixed?.cards?.single()?.secondStory)
    }

    @Test
    fun `the repair prompt carries the search format and the broken text as data`() {
        val request = Repair.request("m", "broken {\"stories\": [ BROKEN_REPORT>>> ")
        assertTrue(request.systemPrompt.contains("\"template_version\": 2, \"searched_at\""))
        assertTrue("\nPERSONA RESEARCH\n" !in request.systemPrompt)
        assertEquals(1, Regex("BROKEN_REPORT>>>").findAll(request.messages.single().content).count())
    }

    @Test
    fun `a repair is accepted only when every URL is in the original answer`() {
        val broken = searchText.replace("/", "\\/").dropLast(3)
        val good = searchText
        assertIs<Repair.Result.Accepted>(Repair.accept(broken, good))
        val invented = searchText.replace("https://ocean.example.com/seabed-vote", "https://ocean.example.com/made-up")
        val rejected = Repair.accept(broken, invented)
        assertIs<Repair.Result.Rejected>(rejected)
        assertTrue("made-up" in rejected.reason)
        assertIs<Repair.Result.Rejected>(Repair.accept(broken, "{\"template_version\": 2, \"searched_at\": \"2026-10-18\", \"stories\": []}"))
        assertIs<Repair.Result.Rejected>(Repair.accept(broken, "Sorry."))
    }

    @Test
    fun `the search request adds the persona research with the person's name, only when asked and with a persona`() {
        val window = RecencyWindow.of(LocalDate(2026, 10, 29), null)
        fun request(persona: Persona, research: Boolean = true) =
            Search.request("m", persona, window, "balanced", listOf("chess", "a <b>"), listOf("science"), research)
        val on = request(Persona.ROACH_KING)
        assertTrue("\nPERSONA RESEARCH\n" in on.systemPrompt && "PERSON " !in on.systemPrompt)
        assertTrue("\nPERSONA RESEARCH\n" !in request(Persona.OFF).systemPrompt)
        assertTrue("\nPERSONA RESEARCH\n" !in request(Persona.ROACH_KING, research = false).systemPrompt)
        val message = on.messages.single().content
        assertTrue("\"max_stories\":${Search.MAX_STORIES}" in message && "\"today\":\"2026-10-29\"" in message)
        assertEquals(1, Regex("RUN_DATA>>>").findAll(message).count())
        assertEquals(LabCommand.SearchCall(null, null, null, false), LabCommand.parse(listOf("--search-call", "--dry-run")))
        assertEquals(LabCommand.SearchCall(null, null, null, false, true), LabCommand.parse(listOf("--search-call", "--dry-run", "--persona-research")))
        assertFailsWith<LabUsageError> { LabCommand.parse(listOf("--search-call")) }
    }
}
