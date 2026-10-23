package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleUrlTest {
    @Test
    fun articlePagesPass() {
        for (url in listOf(
            "https://www.chess.com/news/view/2026-us-chess-championships-round-5",
            "https://www.esa.int/Science_Exploration/Space_Science/Mars_moon_lander_set_to_launch_on_19_October",
            "https://www.sciencedaily.com/releases/2026/10/261008005242.htm",
            "https://www.abc.net.au/news/2026-10-07/is-the-deep-sea-mining-gold-rush-viable/107130150",
            "https://news.example.com/2026/10/15/storm-hits-coast",
            "https://news.example.com/story?id=12",
            "https://news.example.com/news?id=12",
            "https://www.example.com/video/storm-hits-coast",
            "https://news.example.com/live-updates/storm-hits-coast",
        )) assertTrue(ArticleUrl.looksLikeArticle(url), url)
    }

    @Test
    fun listingPagesAreDropped() {
        for (url in listOf(
            "https://www.ans.org/news/topic-fusion/",
            "https://science.nasa.gov/blogs/mars/2026/10/",
            "https://www.umu.se/en/events/deep-sea-mining-in-norway-and-beyond_12197026/",
            "https://news.example.com/",
            "https://news.example.com",
            "https://news.example.com/tag/chess",
            "https://news.example.com/Category/Tech/",
            "https://news.example.com/calendar/2026-10-16",
            "https://news.example.com/archive/2026",
            "https://news.example.com/sport/2026/",
            "https://news.example.com/2026/10/15/",
            "https://news.example.com/search?q=chess",
            "https://events.reutersevents.com/nuclear/fusion-energy",
            "https://www.sciencedaily.com/news/top/technology/",
            "https://phys.org/page2.html",
            "https://news.example.com/science/page/2/",
            "https://www.formula1.com/en/video",
            "https://news.example.com/videos/",
            "https://news.example.com/news/",
            "https://news.example.com/sport/Latest",
            "https://www.usgs.gov/programs/VHP/volcano-updates",
            "https://news.example.com/live-updates/",
            "https://news.example.com/updates",
        )) assertFalse(ArticleUrl.looksLikeArticle(url), url)
    }
}
