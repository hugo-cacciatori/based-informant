package io.github.hugo_cacciatori.basedinformant.core.writer

import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptFiles
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue.Kind
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.search.SearchParser
import io.github.hugo_cacciatori.basedinformant.core.settings.AttentionMode
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the free writer and the fact check (02-ai-call.md section 12) on the synthetic search answer. */
class WriterTest {
    private val searchText = requireNotNull(javaClass.getResource("/fixtures/search-synthetic.json")).readText()
    private val synthetic = SearchParser.parse(searchText)!!.numbered()
    private val withPages = synthetic.withPages(mapOf("https://science.example.com/2026/10/stellarator-record" to "The stellarator held plasma for 40 minutes."))
    private val input = PromptInput(
        listOf("fusion energy", "deep-sea mining", "chess"), emptyList(), AttentionMode.BALANCED, Persona.OFF, LocalDate(2026, 10, 18),
        knownTopicTags = listOf("Chess rules"),
    )

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

    private fun kinds(findings: List<Finding>) = findings.map { it.issue.kind }

    @Test
    fun `persona notes go only to a persona voice`() {
        fun request(persona: Persona) = Writer.request("m", input.copy(persona = persona), synthetic).messages.single().content
        assertTrue("electricity bill" in request(Persona.ROACH_KING))
        assertTrue("electricity bill" !in request(Persona.OFF))
        assertTrue("\"known_topic_tags\":[\"Chess rules\"]" in request(Persona.OFF))
    }

    @Test
    fun `Based adds the outlook and gives Persona Off the Based voice`() {
        fun prompt(based: Boolean, persona: Persona) = Writer.request("m", input.copy(based = based, persona = persona), synthetic).systemPrompt
        val files = PromptFiles.all
        val outlook = files.getValue(Writer.BASED_OUTLOOK_FILE).trim()
        // A persona keeps its voice and gets the outlook.
        val persona = prompt(based = true, persona = Persona.ROACH_KING)
        assertTrue(files.getValue("voices/roach-king.txt").trim() in persona)
        assertTrue(outlook in persona)
        assertTrue(persona.endsWith(files.getValue(Writer.PERSONA_CHECK_FILE).trim()), "the persona check comes last")
        assertTrue("BEFORE YOU REPLY" !in prompt(based = true, persona = Persona.OFF))
        // Persona Off: the Based voice replaces the neutral one.
        val off = prompt(based = true, persona = Persona.OFF)
        assertTrue(files.getValue(Writer.BASED_VOICE_FILE).trim() in off)
        assertTrue(files.getValue("voices/neutral.txt").trim() !in off)
        // Based off: the prompt is what it was before Based.
        assertTrue("BASED OUTLOOK" !in prompt(based = false, persona = Persona.ROACH_KING))
        assertTrue(files.getValue("voices/neutral.txt").trim() in prompt(based = false, persona = Persona.OFF))
    }

    @Test
    fun `five words in a row from a persona note count as a copied quote`() {
        val notes = listOf("Forty minutes of plasma is cool and all, but call me when my electricity bill goes down, chat.")
        assertEquals(
            listOf("call me when my electricity", "me when my electricity bill"),
            Writer.copiedQuotes(listOf("Sure, call me when my electricity bill drops."), notes),
        )
        assertTrue(Writer.copiedQuotes(listOf("Forty minutes of plasma, and the bill stays the same."), notes).isEmpty())
    }

    @Test
    fun `a sentence copying a persona note is deleted and the rest kept`() {
        val copying = written.copy(
            topics = listOf(topic(1, "A plasma lasted 40 minutes. Call me when my electricity bill goes down, honestly.", "Forty minutes of plasma is cool and all! The lab plans more.")),
        )
        val (cleaned, deleted) = Writer.removeCopies(copying, synthetic)
        assertEquals("A plasma lasted 40 minutes.", cleaned.topics.single().summary)
        assertEquals("The lab plans more.", cleaned.topics.single().detail)
        assertEquals(listOf(Kind.QUOTE_DELETED, Kind.QUOTE_DELETED), kinds(deleted))
        assertEquals(copying, Writer.removeCopies(copying, synthetic.withoutPersonaNotes()).first)
    }

    @Test
    fun `a long topic tag is cut at a word`() {
        assertEquals("Deep-sea mining rules", Writer.cutTag(" Deep-sea mining rules "))
        assertEquals("International seabed authority", Writer.cutTag("International seabed authority moratorium vote on mining"))
    }

    @Test
    fun `the writer's topics take their sources from the search answer`() {
        val answer = Writer.parse(
            """Here: {"template_version": 2, "opening": "Hi.", "closing": "Bye.", "subject": "S", "card_summary": "C", "topics": [
               {"story": 2, "topic_tag": "Deep-sea mining rules", "title": "Vote set", "summary": "A vote is set.", "detail": null,
                "keywords": ["Deep-Sea Mining"], "relevance": 50, "sources": [3, 1]},
               {"story": 9, "topic_tag": "x", "title": "t", "summary": "s", "keywords": ["chess"], "relevance": 10, "sources": [5]}]}""",
        )
        assertNotNull(answer)
        val (digest, findings) = Writer.toDigest(answer, synthetic, input)
        val topic = digest.topics.single()
        assertEquals(listOf("https://ocean.example.com/seabed-vote"), topic.sources.map { it.url })
        assertEquals(listOf("deep-sea mining"), topic.keywords, "the owner's spelling")
        assertEquals(80, topic.score)
        assertEquals("Deep-sea mining rules", topic.topicTag)
        assertEquals("S" to "C", digest.subject to digest.cardSummary)
        assertEquals(setOf(Kind.TOPIC_UNKNOWN_STORY, Kind.TOPIC_FOREIGN_SOURCE), kinds(findings).toSet())
        assertNull(Writer.parse("Sorry, I cannot help."))
    }

    @Test
    fun `the fact check request numbers sentences and sends page text, or facts when no page`() {
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
        assertEquals(listOf(Kind.FACT_UNSUPPORTED, Kind.FACT_UNCHECKED_NO_PAGE), kinds(findings))
    }

    @Test
    fun `a second story's sentences go, an unclear split drops the card, and a whole-card verdict is ignored`() {
        val cut = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, secondStory = listOf(2)), FactCheckAnswer.Card(1)))
        assertEquals("A vote is set.", cut.first.topics[1].summary)
        val dropped = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, unclear = true), FactCheckAnswer.Card(1)))
        assertEquals(1, dropped.first.topics.size)
        assertEquals(listOf(Kind.TOPIC_DROPPED_TWO_STORIES), kinds(dropped.second))
        val whole = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(2, twoStories = true, secondStory = listOf(1, 2)), FactCheckAnswer.Card(1)))
        assertEquals(written.topics, whole.first.topics)
        assertEquals(listOf(Kind.FACT_SECOND_STORY_IGNORED), kinds(whole.second))
    }

    @Test
    fun `an emptied summary takes the detail, and a card left with nothing is dropped`() {
        val one = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, listOf(1, 2).map { FactCheckAnswer.Unsupported(it) }), FactCheckAnswer.Card(2)))
        assertEquals("The lab plans more. Ha, cool.", one.first.topics[0].summary)
        assertNull(one.first.topics[0].detail)
        val all = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, (1..4).map { FactCheckAnswer.Unsupported(it) }), FactCheckAnswer.Card(2)))
        assertEquals(listOf(2), all.first.topics.map { it.story })
        assertEquals(Kind.TOPIC_DROPPED_EMPTY, all.second.last().issue.kind)
    }

    @Test
    fun `a card the check left out stays as written, and bad sentence numbers are ignored`() {
        val (checked, findings) = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, listOf(FactCheckAnswer.Unsupported(9)))))
        assertEquals(written.topics, checked.topics)
        assertEquals(listOf(Kind.FACT_BAD_SENTENCE_NUMBER, Kind.FACT_NOT_CHECKED), kinds(findings))
    }

    @Test
    fun `a sentence is not split after an abbreviation before a small letter`() {
        assertEquals(
            listOf("Quantum Computing Inc. announced a deal.", "It starts in 2027."),
            Writer.sentences("Quantum Computing Inc. announced a deal. It starts in 2027."),
        )
    }

    @Test
    fun `the fact check answer is read through fences, in both sentence shapes, and refused without JSON`() {
        val parsed = FactCheck.parse("```json\n{\"cards\": [{\"card\": 1, \"unsupported\": [{\"s\": 2, \"why\": \"x\"}], \"two_stories\": false}]}\n```")
        assertEquals(2, parsed?.cards?.single()?.unsupported?.single()?.s)
        assertNull(FactCheck.parse("I cannot check this."))
        val mixed = FactCheck.parse("{\"cards\": [{\"card\": 1, \"unsupported\": [3], \"two_stories\": true, \"second_story\": [{\"s\": 2, \"why\": \"y\"}]}]}")
        assertEquals(3, mixed?.cards?.single()?.unsupported?.single()?.s)
        assertEquals(listOf(2), mixed?.cards?.single()?.secondStory)
    }
}
