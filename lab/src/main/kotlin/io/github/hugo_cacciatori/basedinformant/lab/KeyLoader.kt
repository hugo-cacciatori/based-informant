package io.github.hugo_cacciatori.basedinformant.lab

import java.io.File

/**
 * The only place that reads the rfab key (05-security.md section 3): the `RFAB_API_KEY` environment variable, then
 * `~/.config/based-informant/rfab-api-key`, then the git-ignored `.env` at the repo root. The key is never printed;
 * [describe] gives at most its last 4 characters.
 */
class KeyLoader(
    private val env: (String) -> String? = System::getenv,
    private val home: File = File(System.getProperty("user.home")),
    private val repoRoot: File,
) {
    fun load(): String? =
        env(VARIABLE)?.clean()
            ?: home.resolve(".config/based-informant/rfab-api-key").takeIf { it.isFile }?.readText()?.clean()
            ?: repoRoot.resolve(".env").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.trim().startsWith("$VARIABLE=") }
                ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.clean()

    private fun String.clean(): String? = trim().takeIf { it.startsWith("rfab_") && it.length >= 25 }

    companion object {
        const val VARIABLE = "RFAB_API_KEY"

        fun describe(key: String) = "key found, ends with ${key.takeLast(4)}"
    }
}
