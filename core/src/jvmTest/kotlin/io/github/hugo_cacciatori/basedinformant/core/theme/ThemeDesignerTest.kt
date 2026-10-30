package io.github.hugo_cacciatori.basedinformant.core.theme

import io.github.hugo_cacciatori.basedinformant.core.fakes.FakeSecretStore
import io.github.hugo_cacciatori.basedinformant.core.fakes.ScriptedRfab
import io.github.hugo_cacciatori.basedinformant.core.fakes.okResponse
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline: the AI theme chain (01-product.md section 10) on made-up model answers. */
class ThemeDesignerTest {
    /** A design with unreadable text, wild numbers and a sky index out of range: what code must repair. */
    private val wild = """Sure! {"name": "Neon Night Extra Long Name For A Theme", "font": "mono",
        "light": {"primary": "#FFEE00", "onPrimary": "#FFFFFF", "background": "#FAFAFA", "onBackground": "#EEEEEE",
          "surface": "#10FFFFFF", "onSurface": "#F0F0F0", "outline": "#999999", "sky": ["#FFFFFF", "#FF00AA", "#00E5FF"]},
        "dark": {"primary": "#202020", "onPrimary": "#101010", "background": "#000000", "onBackground": "#111111",
          "surface": "#20000000", "onSurface": "#222222", "outline": "#555555", "sky": ["#000000", "#FF00AA", "#00E5FF", "not a color"]},
        "shape": {"corner": 90, "border": 9}, "glow": 4, "motion": 7,
        "background": {"engine": "art", "speed": 5, "art": {"layers": [
          {"kind": "grid", "colors": [9], "params": [2, -1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5], "opacity": 3, "speed": 2},
          {"kind": "particles", "colors": [1, 2], "params": [0.8]}]}}}"""

    @Test
    fun aWildDesignIsClampedAndRepairedUntilThePackCheckPasses() {
        val parsed = assertNotNull(ThemeDesigner.parse(wild, "gen-1", seed = 42))
        assertEquals(42, parsed.background.art?.seed)
        val (pack, problems) = ThemeDesigner.repair(ThemeDesigner.clamp(parsed))
        assertEquals(emptyList(), problems)
        assertEquals(emptyList(), PackCheck.problems(pack, "gen-1", emptyMap()))
        assertEquals(32, pack.shape.corner)
        assertEquals(1f, pack.glow)
        assertEquals(2f, pack.motion)
        val grid = pack.background.art!!.layers.first()
        assertEquals(listOf(2), grid.colors, "a color index past the sky is brought back")
        assertEquals(6, grid.params.size)
        assertTrue(grid.params.all { it in 0f..1f })
        assertEquals(3, pack.dark.sky.size, "an invalid color is dropped")
    }

    @Test
    fun aMissingPaletteIsNoTheme() {
        assertNull(ThemeDesigner.parse("""{"name": "Half", "light": {"primary": "#000000"}}""", "gen-1", 1))
        assertNull(ThemeDesigner.parse("no json here", "gen-1", 1))
    }

    @Test
    fun theBriefPicksAMoodNumbersAndASeed() {
        val brief = ThemeDesigner.brief("  calm ocean at dawn  ", Random(3))
        assertTrue(brief.mood in ThemeDesigner.MOODS.map { it.name })
        assertEquals("calm ocean at dawn", brief.idea)
        assertEquals(6, brief.numbers.size)
        assertTrue(brief.seed > 0)
        assertTrue("<<<BRIEF" in ThemeDesigner.designRequest("m", brief).messages.single().content)
    }

    @Test
    fun theStudioPreviewsKeepsAndSelectsATheme() = runBlocking {
        val store = MemoryThemes()
        var selected: String? = null
        // The designer answers; the critic's answer is unreadable, so the designer's design is kept.
        val client = ScriptedRfab(okResponse("{}", 0)) { _, request -> if ("<<<BRIEF" in request.messages.single().content) wild else "no" }
        val studio = ThemeStudio(store, FakeSecretStore("rfab_key"), { client }, select = { selected = it }, newId = { "gen-7" }, generator = { ThemeGenerator(it, Random(1)) })
        studio.generate("")
        val preview = assertIs<StudioStep.Preview>(studio.state.value.step)
        assertEquals("gen-7", preview.pack.id)
        studio.keep("  Night Neon  ")
        assertEquals("gen-7", selected)
        assertEquals(listOf("Night Neon"), studio.state.value.themes.map { it.name })
        assertIs<StudioStep.Idle>(studio.state.value.step)
        studio.delete("gen-7")
        assertTrue(studio.state.value.themes.isEmpty())
    }

    @Test
    fun withoutAKeyOrAFreeModelTheStudioSaysWhy() = runBlocking {
        val noKey = ThemeStudio(MemoryThemes(), FakeSecretStore(null), { error("no client") }, select = {}, newId = { "gen-1" })
        noKey.generate("")
        assertEquals(StudioStep.Reason.NO_KEY, assertIs<StudioStep.Failed>(noKey.state.value.step).reason)
        val silent = ScriptedRfab(okResponse("{}", 0)) { _, _ -> null }
        val noModel = ThemeStudio(MemoryThemes(), FakeSecretStore("rfab_key"), { silent }, select = {}, newId = { "gen-1" })
        noModel.generate("")
        assertEquals(StudioStep.Reason.NO_FREE_MODEL, assertIs<StudioStep.Failed>(noModel.state.value.step).reason)
    }

    @Test
    fun theArtRulesOfThePackCheck() {
        val pack = BuiltInThemes.pack("drift").copy(background = Background(BackgroundEngine.ART, 0.3f, art = Art(layers = emptyList())))
        assertTrue(PackCheck.problems(pack, "drift", emptyMap()).any { "at least one layer" in it })
        assertTrue(BuiltInThemes.isValidId("gen-123") && BuiltInThemes.isValidId("glass") && !BuiltInThemes.isValidId("other"))
    }

    private class MemoryThemes : ThemeStore {
        val packs = mutableListOf<ThemePack>()
        override suspend fun all() = packs.toList()
        override suspend fun save(pack: ThemePack) { packs.removeAll { it.id == pack.id }; packs.add(0, pack) }
        override suspend fun delete(id: String) { packs.removeAll { it.id == id } }
    }
}
