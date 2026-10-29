package io.github.hugo_cacciatori.basedinformant.core.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackCheckTest {
    private val good = """
        {
          "id": "sample",
          "name": "Sample",
          "font": "serif",
          "light": {
            "primary": "#1F4E8C", "onPrimary": "#FFFFFF",
            "background": "#F4F1EA", "onBackground": "#1A1A1A",
            "surface": "#E6FFFFFF", "onSurface": "#1A1A1A", "outline": "#8A8A8A",
            "sky": ["#F4F1EA", "#DDE6F0"]
          },
          "dark": {
            "primary": "#9CC2FF", "onPrimary": "#0B1B33",
            "background": "#0E1116", "onBackground": "#E8E8E8",
            "surface": "#CC1A1F27", "onSurface": "#E8E8E8", "outline": "#5A6270",
            "sky": ["#0E1116", "#1B2433"]
          },
          "background": { "engine": "starfield", "speed": 0.3, "density": 120 }
        }
    """.trimIndent()

    private val files = mapOf("theme.json" to 900L)

    @Test
    fun aGoodPackReadsAndPasses() {
        val pack = ThemePacks.read(good)
        assertEquals(ThemeFont.SERIF, pack.font)
        assertEquals(BackgroundEngine.STARFIELD, pack.background.engine)
        assertEquals("#0E1116", pack.palette(dark = true).background)
        assertEquals(emptyList(), PackCheck.problems(pack, "sample", files))
    }

    @Test
    fun aMissingVariantIsUnreadable() {
        val noDark = """{"id":"x","name":"X","light":${lightJson()},"background":{"engine":"still"}}"""
        assertFailsWith<ThemePackException> { ThemePacks.read(noDark) }
        assertFailsWith<ThemePackException> { ThemePacks.read("not json") }
    }

    @Test
    fun theFolderNameAndSizeAreChecked() {
        val pack = ThemePacks.read(good)
        val problems = PackCheck.problems(pack, "other", mapOf("theme.json" to 900L, "big.bin" to PackCheck.MAX_PACK_BYTES))
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems[0].startsWith("id 'sample' does not match"))
        assertTrue(problems[1].startsWith("pack is"))
    }

    @Test
    fun badColorsAndLowContrastAreReported() {
        val pack = ThemePacks.read(good)
        val unreadable = pack.copy(light = pack.light.copy(primary = "blue", sky = emptyList()))
        assertEquals(
            listOf("light.primary is not a #RRGGBB or #AARRGGBB color", "light.sky is empty"),
            PackCheck.problems(unreadable, "sample", files),
        )
        // Grey on grey: readable by nobody.
        val faint = pack.copy(dark = pack.dark.copy(onBackground = "#333333"))
        val problems = PackCheck.problems(faint, "sample", files)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].startsWith("dark: onBackground on background has contrast"))
    }

    @Test
    fun aTranslucentSurfaceIsCheckedOverEverySkyColor() {
        val pack = ThemePacks.read(good)
        // Mostly see-through: readable over the dark sky, not over the light one.
        val glass = pack.copy(dark = pack.dark.copy(surface = "#201A1F27", sky = listOf("#0E1116", "#C8C8C8")))
        val problems = PackCheck.problems(glass, "sample", files)
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].startsWith("dark: onSurface on surface over sky[1]"))
    }

    @Test
    fun textColorsMustBeOpaque() {
        val pack = ThemePacks.read(good)
        val problems = PackCheck.problems(pack.copy(light = pack.light.copy(onSurface = "#801A1A1A")), "sample", files)
        assertEquals(listOf("light.onSurface must be opaque"), problems)
    }

    @Test
    fun aStarfieldNeedsStarsAndLimitsHold() {
        val pack = ThemePacks.read(good)
        val problems = PackCheck.problems(pack.copy(background = Background(BackgroundEngine.STARFIELD, speed = 2f)), "sample", files)
        assertEquals(2, problems.size, problems.toString())
    }

    @Test
    fun colorsParseAndContrastMatchesWcag() {
        assertEquals(ThemeColor(255, 255, 255, 255), ThemeColor.parse("#FFFFFF"))
        assertEquals(ThemeColor(0x80, 0x10, 0x20, 0x30), ThemeColor.parse("#80102030"))
        assertNull(ThemeColor.parse("#FFF"))
        assertNull(ThemeColor.parse("FFFFFF"))
        val black = ThemeColor.parse("#000000")!!
        val white = ThemeColor.parse("#FFFFFF")!!
        assertEquals(21.0, ThemeColor.contrast(black, white), 0.001)
        assertEquals(1.0, ThemeColor.contrast(white, white), 0.001)
        assertEquals(ThemeColor(255, 128, 128, 128), ThemeColor(128, 255, 255, 255).over(black))
    }

    private fun lightJson() = good.substringAfter("\"light\": ").substringBefore(",\n  \"dark\"")
}
