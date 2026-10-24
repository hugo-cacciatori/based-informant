plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Turns the plain-text prompt files in `core/prompt/` into Kotlin constants (02-ai-call.md section 5), so the
 * Android app, the Windows app and the lab all use exactly the same text.
 */
abstract class GeneratePromptFiles : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val promptDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = promptDir.get().asFile
        val files = root.walkTopDown()
            .filter { it.isFile && (it.extension == "txt" || it.extension == "json") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        val out = outputDir.get().asFile.resolve("io/github/hugo_cacciatori/basedinformant/core/prompt")
        outputDir.get().asFile.deleteRecursively()
        out.mkdirs()
        val entries = files.joinToString("\n") { file ->
            // Line endings are normalized so a Windows checkout builds the same prompt as CI.
            val text = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
            "        \"${file.relativeTo(root).invariantSeparatorsPath}\" to \"${escape(text)}\","
        }
        out.resolve("PromptFiles.kt").writeText(
            """
            |// Generated from core/prompt/ by :core:generatePromptFiles. Do not edit.
            |package io.github.hugo_cacciatori.basedinformant.core.prompt
            |
            |object PromptFiles {
            |    /** File contents by path relative to `core/prompt/`. */
            |    val all: Map<String, String> = mapOf(
            |$entries
            |    )
            |}
            |""".trimMargin(),
            Charsets.UTF_8,
        )
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '$' -> append("\\$")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.code < 0x20 || c.code > 0x7e -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}

val generatePromptFiles = tasks.register<GeneratePromptFiles>("generatePromptFiles") {
    promptDir.set(layout.projectDirectory.dir("prompt"))
    outputDir.set(layout.buildDirectory.dir("generated/prompt/commonMain/kotlin"))
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    jvm()

    android {
        namespace = "io.github.hugo_cacciatori.basedinformant.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Runs the common tests on the Android target too, on the host JVM.
        withHostTest {}
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatePromptFiles.flatMap { it.outputDir })
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
