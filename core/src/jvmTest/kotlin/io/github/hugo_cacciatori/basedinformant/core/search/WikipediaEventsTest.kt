package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Offline: Wikipedia's Current events (02-ai-call.md section 4) on a made-up day page shaped like the real one. */
class WikipediaEventsTest {
    private val today = LocalDate(2026, 10, 30)

    private val page = """
        <ul class="current-events-navbar"><li><a class="external text" href="https://en.wikipedia.org/w/index.php?title=x&amp;action=edit">edit</a></li></ul>
        <p><b>Armed conflicts and attacks</b></p>
        <ul><li><a href="/wiki/Some_war">Some war</a>
        <ul><li>Twelve people are hurt when a <a href="/wiki/Drone">drone</a> hits a station in the capital of Examplestan. <a rel="nofollow" class="external text" href="https://news.example.com/world/2026/oct/30/drone-station">(<i>The Example</i>)</a> <a rel="nofollow" class="external text" href="https://wire.example.org/article/drone-hits-station">(Example Wire)</a></li></ul></li>
        <li>A new chess world champion is crowned after a tiebreak in Example City. <a rel="nofollow" class="external text" href="https://sport.example.net/chess-champion-crowned">(Sport Example)</a></li>
        <li>An event with no citation at all, which the page should not list this way.</li></ul>
    """.trimIndent()

    private fun answer(html: String) = Json.encodeToString(buildJsonObject { put("parse", buildJsonObject { put("text", JsonPrimitive(html)) }) })

    @Test
    fun eachCitedEventBecomesAStoryAndAKeywordMakesItAKeywordStory() = runBlocking {
        val read = mutableListOf<String>()
        val (stories, issues) = WikipediaEvents.stories({ url -> read += url; if ("October_30" in url) answer(page) else answer("") }, listOf("chess"), today)
        assertEquals(
            listOf(
                "https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text&page=Portal:Current_events/2026_October_30",
                "https://en.wikipedia.org/w/api.php?action=parse&format=json&formatversion=2&prop=text&page=Portal:Current_events/2026_October_29",
            ),
            read,
        )
        assertEquals(2, stories.size, "the navbar and the uncited item are no events")
        val drone = stories[0]
        assertTrue(drone.trending && drone.keywords.isEmpty())
        assertEquals("Twelve people are hurt when a drone hits a station in the capital of Examplestan.", drone.whatHappened)
        assertEquals(listOf("The Example", "Example Wire"), drone.sources.map { it.publisher })
        assertEquals(listOf("2026-10-30", "2026-10-30"), drone.sources.map { it.published })
        val chess = stories[1]
        assertEquals(listOf("chess"), chess.keywords)
        assertTrue(!chess.trending)
        // Yesterday's page held nothing: reported; today's may still be empty early in the day.
        assertEquals(listOf(Issue.Kind.FEED_UNREADABLE), issues.map { it.kind })
    }

    @Test
    fun atMostTenEventsNewestPageFirst() = runBlocking {
        val many = (1..8).joinToString("") { "<li>Event number $it happens somewhere in Example County today. <a class=\"external text\" href=\"https://news.example.com/e$it\">(News)</a></li>" }
        val (stories, _) = WikipediaEvents.stories({ url -> answer(if ("October_30" in url) many else many.replace("Event number", "Older event")) }, emptyList(), today)
        assertEquals(WikipediaEvents.MAX_EVENTS, stories.size)
        assertEquals(8, stories.count { it.whatHappened!!.startsWith("Event number") })
    }
}
