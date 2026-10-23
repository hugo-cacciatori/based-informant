package io.github.hugo_cacciatori.basedinformant.core.digest

import kotlinx.datetime.LocalDate

/**
 * Reads an article's own publication date from its page (owner, 2026-10-18: models misdate sources). In order:
 * the `article:published_time` meta tag, JSON-LD `datePublished`, other date meta tags, then an element labeled as the
 * publication date (an id, class or itemprop such as `date_posted` or `published`). Never loose dates in the text,
 * which also belong to related articles.
 */
object PageDate {
    /** Meta names and properties that carry the publication date, most specific first. */
    private val META_KEYS = listOf(
        "article:published_time",
        "datepublished",
        "parsely-pub-date",
        "sailthru.date",
        "pubdate",
        "publishdate",
        "publish-date",
        "publish_date",
        "dc.date.issued",
        "dcterms.created",
        "dc.date",
        "citation_publication_date",
        "date",
    )

    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([a-zA-Z:_.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val JSON_LD_PUBLISHED = Regex(""""datePublished"\s*:\s*"([^"]+)"""")
    private val LABELED_ELEMENT = Regex(
        """(?:id|class|itemprop)\s*=\s*["'][^"']*(?:date[-_]?posted|date[-_]?published|datepublished|published[-_]?date|pub[-_]?date|publish[-_]?date)[^"']*["'][^>]*>\s*([^<]{6,60})<""",
        RegexOption.IGNORE_CASE,
    )

    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val MONTH_DAY_YEAR = Regex("""\b([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})\b""")
    private val DAY_MONTH_YEAR = Regex("""\b(\d{1,2})\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})\b""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun extract(html: String): LocalDate? {
        val metas = META_TAG.findAll(html).mapNotNull { tag ->
            val attrs = ATTRIBUTE.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3] })
            }
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase() ?: return@mapNotNull null
            val content = attrs["content"] ?: return@mapNotNull null
            key to content
        }.toList()
        fun meta(keys: List<String>): LocalDate? {
            for (key in keys) metas.firstOrNull { it.first == key }?.let { (_, v) -> parse(v)?.let { return it } }
            return null
        }
        return meta(META_KEYS.take(1))
            ?: JSON_LD_PUBLISHED.find(html)?.let { parse(it.groupValues[1]) }
            ?: meta(META_KEYS.drop(1))
            ?: LABELED_ELEMENT.find(html)?.let { parse(it.groupValues[1].trim()) }
    }

    /**
     * The date in a value such as `2026-04-08T23:12:00+05:30`, `Apr 08, 2026, 11:09:00 PM IST`, `October 10, 2026`
     * or `10 October 2026`. Anything else is null.
     */
    internal fun parse(value: String): LocalDate? {
        ISO_DATE.find(value)?.let { m -> return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        MONTH_DAY_YEAR.find(value)?.let { m ->
            month(m.groupValues[1])?.let { return date(m.groupValues[3].toInt(), it, m.groupValues[2].toInt()) }
        }
        DAY_MONTH_YEAR.find(value)?.let { m ->
            month(m.groupValues[2])?.let { return date(m.groupValues[3].toInt(), it, m.groupValues[1].toInt()) }
        }
        return null
    }

    /** English month names and their 3-letter forms ("Sept" too); anything else is not a month. */
    private fun month(name: String): Int? {
        val lower = name.lowercase()
        val index = MONTHS.indexOf(lower.take(3))
        if (index < 0) return null
        val full = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")[index]
        return if (lower == lower.take(3) || full.startsWith(lower)) index + 1 else null
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate(year, month, day)
    } catch (_: IllegalArgumentException) {
        null
    }
}
