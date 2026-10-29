package io.github.hugo_cacciatori.basedinformant.core.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pack test (04-platforms.md section 6) over the packs that ship, read from `core/themes/` on disk. */
class BuiltInThemesTest {
    private val root = File("themes")

    @Test
    fun everyFolderIsListedAndEveryListedPackExists() {
        val folders = root.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.toSet()
        assertEquals(BuiltInThemes.IDS.toSet(), folders)
    }

    @Test
    fun everyPackPassesTheCheck() {
        for (id in BuiltInThemes.IDS) {
            val folder = File(root, id)
            val sizes = folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).path to it.length() }
            val pack = ThemePacks.read(File(folder, "theme.json").readText())
            assertEquals(emptyList(), PackCheck.problems(pack, id, sizes), "pack $id")
            // The embedded copy the app reads is the same pack.
            assertEquals(pack, BuiltInThemes.pack(id))
        }
    }

    @Test
    fun anUnknownIdGivesTheDefault() {
        assertEquals(BuiltInThemes.DEFAULT, BuiltInThemes.pack("removed").id)
    }
}
