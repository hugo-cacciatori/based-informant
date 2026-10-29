package io.github.hugo_cacciatori.basedinformant.data

import okio.FileSystem
import okio.Path

/**
 * The raw responses, one file per run (`raw/<runId>.json`, 04-platforms.md section 7), written before any parsing, and
 * the checked search with the pages' text (`raw/<runId>-search.json`), kept for Write again (02-ai-call.md section 12).
 * Each write goes to a temporary file first and is then renamed, so a crash never leaves half an answer.
 */
class RawFiles(private val fileSystem: FileSystem, private val dir: Path) {
    fun save(runId: Long, content: String) = write("$runId.json", content)

    fun read(runId: Long): String? = read("$runId.json")

    fun saveSearch(runId: Long, content: String) = write("$runId-search.json", content)

    fun readSearch(runId: Long): String? = read("$runId-search.json")

    /** Both files of a run, when they exist. */
    fun delete(runId: Long) {
        fileSystem.delete(dir.resolve("$runId.json"), mustExist = false)
        fileSystem.delete(dir.resolve("$runId-search.json"), mustExist = false)
    }

    private fun write(name: String, content: String) {
        fileSystem.createDirectories(dir)
        val temporary = dir.resolve("$name.tmp")
        fileSystem.write(temporary) { writeUtf8(content) }
        fileSystem.atomicMove(temporary, dir.resolve(name))
    }

    private fun read(name: String): String? {
        val file = dir.resolve(name)
        return if (fileSystem.exists(file)) fileSystem.read(file) { readUtf8() } else null
    }

    companion object {
        const val DIR_NAME = "raw"
    }
}
