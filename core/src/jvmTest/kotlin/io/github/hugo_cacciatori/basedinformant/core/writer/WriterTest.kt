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
        // A persona keeps its voice and its own worldview, reacting to the right-wing sources (owner, 2026-10-30).
        val persona = prompt(based = true, persona = Persona.ROACH_KING)
        assertTrue(files.getValue("voices/roach-king.txt").trim() in persona)
        assertTrue(files.getValue(Writer.BASED_PERSONA_FILE).trim() in persona)
        assertTrue(outlook !in persona)
        assertTrue(
            persona.endsWith(files.getValue(Writer.PERSONA_CHECK_FILE).trim() + "\n\n" + files.getValue(Writer.FILL_CHECK_FILE).trim()),
            "the persona check, then the fill check, come last",
        )
        assertTrue("BEFORE YOU REPLY" !in prompt(based = true, persona = Persona.OFF))
        // Persona Off: the Based voice replaces the neutral one.
        val off = prompt(based = true, persona = Persona.OFF)
        assertTrue(files.getValue(Writer.BASED_VOICE_FILE).trim() in off)
        assertTrue(outlook in off)
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
    fun `every persona voice lists its real names with replacements and Off has none`() {
        val names = Writer.personaNames(Persona.DOG_ZAPPER)
        assertEquals(listOf("Hasan Piker", "HasanAbi", "Hasan", "Piker", "Kaya", "Cenk Uygur", "Cenk"), names.map { it.name })
        assertEquals(listOf("my spoiled princess of a dog", "the best-cared-for dog on Earth"), names.single { it.name == "Kaya" }.replacements)
        Persona.entries.filter { it != Persona.OFF }.forEach { p -> assertTrue(Writer.personaNames(p).let { it.isNotEmpty() && it.all { n -> n.replacements.isNotEmpty() } }, "$p") }
        assertTrue(Writer.personaNames(Persona.OFF).isEmpty())
    }

    @Test
    fun `the persona's names are replaced in its voice but stay on a card about the person`() {
        // Story 2's sources are about the person: its card keeps the names they carry.
        val stories = synthetic.stories.map { s ->
            if (s.story != 2) s else s.copy(sources = s.sources.map { it.copy(url = "https://news.example.com/hasan-piker-sues") })
        }
        val answer = synthetic.copy(stories = stories)
        val naming = written.copy(
            opening = "What's good, chat. Hasan here, and Kaya says hi! Let's go.",
            closing = "Feed Kaya, tax the rich.",
            topics = listOf(
                topic(1, "A plasma lasted 40 minutes. Even Kaya's bowl is warmer.", "Hasan approves."),
                topic(2, "Hasan Piker sued a pundit. Kaya slept through it.").copy(title = "Kaya watches Hasan sue"),
            ),
        )
        val (cleaned, replaced) = Writer.replaceNames(naming, answer, Writer.personaNames(Persona.DOG_ZAPPER))
        // Replacements rotate, start a sentence capitalized and keep a possessive (owner, 2026-10-30: replace, never just remove).
        assertEquals("What's good, chat. Your favorite comrade here, and my spoiled princess of a dog says hi! Let's go.", cleaned.opening)
        assertEquals("Feed the best-cared-for dog on Earth, tax the rich.", cleaned.closing)
        assertEquals("A plasma lasted 40 minutes. Even my spoiled princess of a dog's bowl is warmer.", cleaned.topics[0].summary)
        assertEquals("Big bro approves.", cleaned.topics[0].detail)
        assertEquals("The best-cared-for dog on Earth watches Hasan sue", cleaned.topics[1].title)
        assertEquals("Hasan Piker sued a pundit. My spoiled princess of a dog slept through it.", cleaned.topics[1].summary)
        assertEquals(7, kinds(replaced).count { it == Kind.PERSONA_NAME_REPLACED })
        assertEquals(naming, Writer.replaceNames(naming, answer, Writer.personaNames(Persona.OFF)).first)
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
    fun `the voice pass gets the cards and the persona, and keeps what it leaves out`() {
        val request = VoicePass.request("m", written, input.copy(persona = Persona.DOCTOR_WIFE, based = true))
        assertTrue("A plasma lasted 40 minutes." in request.messages.single().content)
        assertTrue(PromptFiles.all.getValue("voices/doctor-wife.txt").trim() in request.systemPrompt)
        assertTrue(PromptFiles.all.getValue(Writer.BASED_PERSONA_FILE).trim() in request.systemPrompt)
        val answer = VoicePass.parse("""Sure: {"cards": [{"card": 1, "title": "Logically, plasma", "summary": "  ", "detail": "Hypothetically, more."}, {"card": 2, "detail": "Invented detail."}]}""")!!
        val voiced = VoicePass.apply(written, answer)
        assertEquals("Logically, plasma", voiced.topics[0].title)
        assertEquals(written.topics[0].summary, voiced.topics[0].summary, "a blank text keeps the writer's")
        assertEquals("Hypothetically, more.", voiced.topics[0].detail)
        assertEquals(written.topics[1], voiced.topics[1], "a card with no detail gets none")
        assertNull(VoicePass.parse("no json"))
    }

    @Test
    fun `sentences never split after initials or a title`() {
        assertEquals(
            listOf("Protesters gathered outside the U.S. Embassy in Wandsworth.", "Dr. Smith spoke first.", "Quantum Computing Inc. announced more."),
            Writer.sentences("Protesters gathered outside the U.S. Embassy in Wandsworth. Dr. Smith spoke first. Quantum Computing Inc. announced more."),
        )
    }

    @Test
    fun `a summary that lost its lead takes it from the detail, and the voice pass never lengthens a text`() {
        val card = written.copy(topics = listOf(topic(1, "A plasma lasted 40 minutes. Ha, cool.", "The lab plans more. It cost 2 billion.", listOf(1))))
        val checked = FactCheck.apply(card, withPages, check(FactCheckAnswer.Card(1, unsupported = listOf(FactCheckAnswer.Unsupported(1))))).first
        assertEquals("The lab plans more. Ha, cool.", checked.topics.single().summary, "never a summary with only the joke")
        assertEquals("It cost 2 billion.", checked.topics.single().detail)

        val long = VoicePassAnswer(listOf(VoicePassAnswer.Card(1, summary = "A plasma lasted 40 minutes, which logically, hypothetically and for the sake of argument is long. Ha, cool, and then some more words.")))
        assertEquals(card.topics.single().summary, VoicePass.apply(card, long).topics.single().summary)
    }

    @Test
    fun `a title telling another event drops the card, and an old answer without the field keeps it`() {
        val off = FactCheck.apply(written, withPages, check(FactCheckAnswer.Card(1, titleOff = true), FactCheckAnswer.Card(2)))
        assertEquals(listOf("Title 2"), off.first.topics.map { it.title })
        assertEquals(listOf(Kind.TOPIC_DROPPED_TITLE_OFF), kinds(off.second))
        val parsed = FactCheck.parse("""{"cards": [{"card": 1, "unsupported": []}, {"card": 2, "title_off": true}]}""")!!
        assertEquals(listOf(false, true), parsed.cards.map { it.titleOff })
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
