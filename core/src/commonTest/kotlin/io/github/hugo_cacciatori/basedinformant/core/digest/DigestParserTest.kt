package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DigestParserTest {
    private fun failure(content: String) = assertIs<DigestParser.Result.Failed>(DigestParser.parse(content)).reason

    @Test
    fun emptyAndBlankAnswersFail() {
        assertEquals(DigestParser.Failure.EMPTY, failure(""))
        assertEquals(DigestParser.Failure.EMPTY, failure("   \n"))
        assertEquals(DigestParser.Failure.EMPTY, failure("```json\n```"))
    }

    @Test
    fun aTopLevelArrayIsNotADigest() {
        assertEquals(DigestParser.Failure.NOT_AN_OBJECT, failure("[1, 2]"))
    }

    @Test
    fun textCutBeforeTheTopicsFails() {
        assertEquals(DigestParser.Failure.TRUNCATED_BEFORE_TOPICS, failure("""{"template_version": 1, "generated_at": "2026-"""))
    }

    @Test
    fun proseBeforeTheObjectIsSkipped() {
        val parsed = assertIs<DigestParser.Result.Parsed>(DigestParser.parse("Sure! {\"topics\": []}"))
        assertFalse(parsed.truncated)
    }

    @Test
    fun cutInsideTheFirstTopicKeepsAnEmptyList() {
        val parsed = assertIs<DigestParser.Result.Parsed>(DigestParser.parse("""{"template_version": 1, "topics": [{"id": "a", "ti"""))
        assertTrue(parsed.truncated)
        assertEquals("[]", parsed.json["topics"].toString())
    }

    @Test
    fun fencesWithoutLanguageAreStripped() {
        assertEquals("{}", DigestParser.stripFences("```\n{}\n```"))
    }
}

class DigestValidatorTest {
    private val context = ValidationContext(listOf("chess"), RecencyWindow.of(LocalDate(2026, 10, 18), null))

    private fun validate(json: String): DigestValidator.Result {
        val parsed = assertIs<DigestParser.Result.Parsed>(DigestParser.parse(json))
        return DigestValidator.validate(parsed.json, context)
    }

    private fun failure(json: String) = assertIs<DigestValidator.Result.Invalid>(validate(json)).reason

    @Test
    fun headerProblemsFailTheWholeAnswer() {
        assertEquals(DigestValidator.Failure.MISSING_TEMPLATE_VERSION, failure("""{"generated_at": "2026-10-18", "topics": []}"""))
        assertEquals(DigestValidator.Failure.MISSING_TEMPLATE_VERSION, failure("""{"template_version": "1", "generated_at": "2026-10-18", "topics": []}"""))
        assertEquals(DigestValidator.Failure.UNSUPPORTED_TEMPLATE_VERSION, failure("""{"template_version": 2, "generated_at": "2026-10-18", "topics": []}"""))
        assertEquals(DigestValidator.Failure.BAD_GENERATED_AT, failure("""{"template_version": 1, "generated_at": "18/10/2026", "topics": []}"""))
        assertEquals(DigestValidator.Failure.MISSING_TOPICS, failure("""{"template_version": 1, "generated_at": "2026-10-18"}"""))
    }

    @Test
    fun scoresOutsideZeroToHundredDropTheTopic() {
        val topic = """{"id": "a", "title": "t", "summary": "s", "keywords": ["chess"], "score": 101,
            "sources": [{"title": "t", "publisher": "p", "url": "https://a.example.com/", "language": "en", "published": "2026-10-18"}]}"""
        val valid = assertIs<DigestValidator.Result.Valid>(validate("""{"template_version": 1, "generated_at": "2026-10-18", "topics": [$topic]}"""))
        assertTrue(valid.digest.topics.isEmpty())
        assertEquals(Issue.Kind.TOPIC_BAD_SCORE, valid.issues.single().kind)
    }

    private fun scored(score: String, parts: String?): DigestValidator.Result.Valid {
        val partsJson = parts?.let { ""","score_parts": $it""" } ?: ""
        val topic = """{"id": "a", "title": "t", "summary": "s", "keywords": ["chess"], "score": $score$partsJson,
            "sources": [{"title": "t", "publisher": "p", "url": "https://a.example.com/x", "language": "en", "published": "2026-10-18"}]}"""
        return assertIs<DigestValidator.Result.Valid>(validate("""{"template_version": 1, "generated_at": "2026-10-18", "topics": [$topic]}"""))
    }

    @Test
    fun theScoreIsTheSumOfItsParts() {
        val corrected = scored("84", """{"relevance": 50, "attention": 24, "repeat_penalty": 0}""")
        assertEquals(74, corrected.digest.topics.single().score)
        assertEquals(Issue.Kind.TOPIC_SCORE_CORRECTED, corrected.issues.single().kind)

        val agreed = scored("74", """{"relevance": 50, "attention": 24, "repeat_penalty": 0}""")
        assertTrue(agreed.issues.isEmpty())

        // Parts beyond their ranges are clamped (relevance 60, attention 40), and a missing total is computed.
        assertEquals(100, scored("null", """{"relevance": 90, "attention": 70, "repeat_penalty": 0}""").digest.topics.single().score)
        assertEquals(0, scored("5", """{"relevance": 10, "attention": 5, "repeat_penalty": 40}""").digest.topics.single().score)

        // Without parts, the model's total is used.
        assertEquals(66, scored("66", null).digest.topics.single().score)
    }

    @Test
    fun aSourceDatedTomorrowIsKeptButNotTheDayAfter() {
        fun digestWith(date: String): String {
            val source = """{"title": "t", "publisher": "p", "url": "https://a.example.com/news/story", "language": "en", "published": "$date"}"""
            return """{"template_version": 1, "generated_at": "2026-10-18", "topics": [
                {"id": "a", "title": "t", "summary": "s", "keywords": ["chess"], "score": 60, "sources": [$source]}]}"""
        }
        assertEquals(1, assertIs<DigestValidator.Result.Valid>(validate(digestWith("2026-10-19"))).digest.topics.size)
        assertEquals(0, assertIs<DigestValidator.Result.Valid>(validate(digestWith("2026-10-20"))).digest.topics.size)
    }

    @Test
    fun aTopicThatReadsLikeTwoStoriesIsDropped() {
        fun digestWith(summary: String, detail: String?): String {
            val source = """{"title": "t", "publisher": "p", "url": "https://a.example.com/news/story", "language": "en", "published": "2026-10-18"}"""
            val detailJson = detail?.let { "\"$it\"" } ?: "null"
            return """{"template_version": 1, "generated_at": "2026-10-18", "topics": [
                {"id": "a", "title": "t", "summary": "$summary", "detail": $detailJson, "keywords": ["chess"], "score": 60, "sources": [$source]}]}"""
        }
        for ((summary, detail) in listOf(
            "A panel found the tapes. Another report said the series returns." to null,
            "The alert dropped." to "A separate update said the range is quiet.",
            "Several quantum computing developments landed this week." to null,
            "The weekly round-up lists five items." to null,
            "Formula 1 coverage this week focused on the break." to null,
        )) {
            val valid = assertIs<DigestValidator.Result.Valid>(validate(digestWith(summary, detail)))
            assertTrue(valid.digest.topics.isEmpty(), summary)
            assertEquals(Issue.Kind.TOPIC_MIXED_STORIES, valid.issues.single().kind)
        }
        for (summary in listOf(
            "The federation will trial a shorter time control. Coaches were split.",
            "Meanwhile, opponents also warned about the plan.",
            "Several players withdrew before round one.",
        )) assertEquals(1, assertIs<DigestValidator.Result.Valid>(validate(digestWith(summary, null))).digest.topics.size, summary)
    }

    @Test
    fun httpsCheck() {
        assertTrue(DigestValidator.isHttpsUrl("https://example.com"))
        assertTrue(DigestValidator.isHttpsUrl("HTTPS://example.com/a?b=c"))
        assertFalse(DigestValidator.isHttpsUrl("http://example.com"))
        assertFalse(DigestValidator.isHttpsUrl("https://"))
        assertFalse(DigestValidator.isHttpsUrl("https://user@example.com/"))
        assertFalse(DigestValidator.isHttpsUrl("https://example.com/\nx"))
    }

    @Test
    fun urlNormalization() {
        assertEquals(
            DigestValidator.normalizeUrl("https://example.com/a"),
            DigestValidator.normalizeUrl("HTTPS://Example.COM/a/#frag"),
        )
        // Paths keep their case: they can be case-sensitive.
        assertFalse(DigestValidator.normalizeUrl("https://example.com/A") == DigestValidator.normalizeUrl("https://example.com/a"))
    }
}
