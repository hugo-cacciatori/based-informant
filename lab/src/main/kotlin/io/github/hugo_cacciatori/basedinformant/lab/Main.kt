package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.digest.HttpLinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.linkCheckHttpClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.KtorRfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.rfabHttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val code = try {
        val command = LabCommand.parse(args.toList())
        val http = lazy { rfabHttpClient(OkHttp.create()) }
        val linkHttp = lazy { linkCheckHttpClient(OkHttp.create()) }
        val lab = Lab(
            root = File("").absoluteFile,
            out = System.out,
            clientFor = { key -> KtorRfabClient(http.value, key) },
            linkChecker = { HttpLinkChecker(linkHttp.value) },
        )
        runBlocking { lab.run(command) }.also {
            if (http.isInitialized()) http.value.close()
            if (linkHttp.isInitialized()) linkHttp.value.close()
        }
    } catch (e: LabUsageError) {
        System.err.println("lab: ${e.message}")
        System.err.println("Run with --help for the modes.")
        2
    }
    exitProcess(code)
}
