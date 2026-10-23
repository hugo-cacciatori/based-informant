package io.github.hugo_cacciatori.basedinformant.data

import okio.FileSystem
import okio.Path

/**
 * The raw responses, one file per run (`raw/<runId>.json`, platforms.md section 7), written before any parsing.
 * Each write goes to a temporary file first and is then renamed, so a crash never leaves half an answer.
 */
class RawFiles(private val fileSystem: FileSystem, private val dir: Path) {
    fun save(runId: Long, content: String) {
        fileSystem.createDirectories(dir)
        val target = file(runId)
        val temporary = dir.resolve("$runId.json.tmp")
        fileSystem.write(temporary) { writeUtf8(content) }
        fileSystem.atomicMove(temporary, target)
    }

    fun read(runId: Long): String? {
        val file = file(runId)
        return if (fileSystem.exists(file)) fileSystem.read(file) { readUtf8() } else null
    }

    private fun file(runId: Long) = dir.resolve("$runId.json")

    companion object {
        const val DIR_NAME = "raw"
    }
}
