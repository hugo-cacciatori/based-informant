package io.github.hugo_cacciatori.basedinformant.core.search

import io.github.hugo_cacciatori.basedinformant.core.digest.Issue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Based's free outlet check (02-ai-call.md section 4, owner 2026-10-30). */
class OutletCheckTest {
    private fun source(n: Int, publisher: String, url: String) =
        SearchAnswer.SearchSource(n = n, title = "t", publisher = publisher, url = url, language = "en", published = "2026-10-29")

    private val answer = SearchAnswer(
        searchedAt = "2026-10-30",
        stories = listOf(
            SearchAnswer.Story(story = 1, keywords = listOf("free speech"), sources = listOf(
                source(1, "Fox News", "https://www.foxnews.com/politics/a"),
                source(2, "Reuters", "https://www.reuters.com/world/b"),
            )),
            SearchAnswer.Story(story = 2, trending = true, sources = listOf(source(3, "Reuters", "https://www.reuters.com/world/c"))),
            SearchAnswer.Story(story = 3, trending = true, sources = listOf(source(4, "X (Elon Musk)", "https://x.com/elonmusk/status/1"))),
        ),
    )

    @Test
    fun eachOutletIsAskedOnceByPublisherAndDomain() {
        assertEquals(
            listOf(OutletCheck.Outlet("Fox News", "foxnews.com"), OutletCheck.Outlet("Reuters", "reuters.com"), OutletCheck.Outlet("X (Elon Musk)", "x.com")),
            OutletCheck.outlets(answer),
        )
        val request = OutletCheck.request("m", answer)
        assertTrue("\"o\":2,\"publisher\":\"Reuters\",\"domain\":\"reuters.com\"" in request.messages.single().content)
        assertTrue("never what a story is about" in request.systemPrompt)
    }

    @Test
    fun sourcesOfOutletsNotCalledRightWingGoAndEmptyStoriesWithThem() {
        val verdict = OutletCheck.parse("""Here: {"outlets": [{"o": 1, "right_wing": true}, {"o": 2, "right_wing": false}, {"o": 3, "right_wing": true}]}""")
        assertEquals(setOf(1, 3), verdict)
        val (kept, issues) = OutletCheck.apply(answer, verdict!!)
        assertEquals(listOf(1, 3), kept.stories.map { it.story })
        assertEquals(listOf("Fox News"), kept.stories.first().sources.map { it.publisher })
        assertEquals(
            listOf(Issue.Kind.SOURCE_NOT_RIGHT_WING, Issue.Kind.SOURCE_NOT_RIGHT_WING, Issue.Kind.STORY_NO_SOURCE),
            issues.map { it.kind },
        )
    }

    @Test
    fun anOutletLeftOutCountsAsNotRightWingAndNonsenseIsUnreadable() {
        val (kept, _) = OutletCheck.apply(answer, OutletCheck.parse("""{"outlets": [{"o": 1, "right_wing": true}]}""")!!)
        assertEquals(listOf(1), kept.stories.map { it.story })
        assertNull(OutletCheck.parse("no idea"))
    }
}
