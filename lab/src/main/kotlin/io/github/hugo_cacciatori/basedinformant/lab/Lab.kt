package io.github.hugo_cacciatori.basedinformant.lab

import io.github.hugo_cacciatori.basedinformant.core.check.InputCheck
import io.github.hugo_cacciatori.basedinformant.core.digest.Digest
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkChecker
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkResult
import io.github.hugo_cacciatori.basedinformant.core.digest.LinkStatus
import io.github.hugo_cacciatori.basedinformant.core.digest.Selection
import io.github.hugo_cacciatori.basedinformant.core.digest.ValidationContext
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatMessage
import io.github.hugo_cacciatori.basedinformant.core.prompt.ChatRequest
import io.github.hugo_cacciatori.basedinformant.core.prompt.PromptInput
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.render.DigestRenderer
import io.github.hugo_cacciatori.basedinformant.core.render.RenderMeta
import io.github.hugo_cacciatori.basedinformant.core.rfab.ChatResponse
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import io.github.hugo_cacciatori.basedinformant.core.search.Repair
import io.github.hugo_cacciatori.basedinformant.core.search.BasedFeeds
import io.github.hugo_cacciatori.basedinformant.core.search.FeedFetcher
import io.github.hugo_cacciatori.basedinformant.core.search.OutletCheck
import io.github.hugo_cacciatori.basedinformant.core.search.SearchAnswer
import io.github.hugo_cacciatori.basedinformant.core.search.SearchCheck
import io.github.hugo_cacciatori.basedinformant.core.search.SearchLinks
import io.github.hugo_cacciatori.basedinformant.core.search.SearchParser
import io.github.hugo_cacciatori.basedinformant.core.search.SearchRequest
import io.github.hugo_cacciatori.basedinformant.core.settings.Persona
import io.github.hugo_cacciatori.basedinformant.core.writer.FactCheck
import io.github.hugo_cacciatori.basedinformant.core.writer.Finding
import io.github.hugo_cacciatori.basedinformant.core.writer.VoicePass
import io.github.hugo_cacciatori.basedinformant.core.writer.Writer
import io.github.hugo_cacciatori.basedinformant.core.writer.WriterAnswer
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import io.github.hugo_cacciatori.basedinformant.core.check.FreeModels
import io.github.hugo_cacciatori.basedinformant.core.digest.Issue

/** The parsed command line (02-ai-call.md section 11). */
sealed interface LabCommand {
    /** Asks for one article to see whether [model] searches the web (Model plan step 3). [paid] needs the owner's OK. */
    data class Probe(val model: String, val keyword: String?, val input: String?, val paid: Boolean) : LabCommand

    /** The free writer of the call redesign (02-ai-call.md section 12) on a saved search answer, or a v1 answer turned into one. */
    data class Write(
        val model: String,
        val searchAnswer: String,
        val input: String?,
        val persona: String?,
        val fetchArticles: Boolean = false,
        val articles: String? = null,
    ) : LabCommand

    /**
     * The new paid search (02-ai-call.md section 12, redesign step 3): `search.txt`, and the persona research only with
     * [research]. [paid] spends tokens, only after the owner's OK in chat; otherwise a dry run that sends nothing.
     */
    data class SearchCall(
        val input: String?,
        val persona: String?,
        val model: String?,
        val paid: Boolean,
        val research: Boolean = false,
    ) : LabCommand

    /** The free fact check (02-ai-call.md section 12, items 7 and 8) on the writer answer saved in a --write run folder. */
    data class FactCheck(val model: String, val run: String, val input: String?, val persona: String?, val answer: String? = null) : LabCommand

    /** The free repair (02-ai-call.md section 12, item 10) of an unreadable search answer saved in [broken]. */
    data class Repair(val model: String, val broken: String) : LabCommand

    /** Based's free feeds (02-ai-call.md section 4): what they give today, as stories for the keywords of [input]. */
    data class Feeds(val input: String?) : LabCommand

    /** The free outlet check of Based (02-ai-call.md section 4) on a saved search answer. */
    data class OutletCheck(val model: String, val searchAnswer: String) : LabCommand

    /** The free input check (02-ai-call.md section 12) on a fixed list, once per free model in [models]. */
    data class CheckInput(val models: List<String>, val list: String?) : LabCommand
    data object Models : LabCommand
    data object Balance : LabCommand
    data object Help : LabCommand

    companion object {
        fun parse(args: List<String>): LabCommand {
            val flags = mutableMapOf<String, String?>()
            var i = 0
            while (i < args.size) {
                val name = args[i]
                if (!name.startsWith("--")) throw LabUsageError("unexpected argument \"$name\"")
                val takesValue = name in VALUED
                val value = if (takesValue) args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }
                    ?: throw LabUsageError("$name needs a value") else null
                if (name !in VALUED && name !in SWITCHES) throw LabUsageError("unknown option $name")
                flags[name] = value
                i += if (takesValue) 2 else 1
            }
            val modes = MODES.filter { it in flags }
            return when {
                flags.isEmpty() || "--help" in flags -> Help
                modes.size != 1 -> throw LabUsageError("choose exactly one mode: ${MODES.joinToString(", ")}")
                "--probe" in flags -> Probe(
                    flags["--model"] ?: throw LabUsageError("--probe needs --model <id>"),
                    flags["--keyword"],
                    flags["--input"],
                    when {
                        "--dry-run" in flags && "--confirm-paid" in flags -> throw LabUsageError("choose --dry-run or --confirm-paid, not both")
                        "--dry-run" in flags -> false
                        "--confirm-paid" in flags -> true
                        else -> throw LabUsageError("--probe needs --dry-run (free) or --confirm-paid (spends tokens, only after the owner's OK)")
                    },
                )
                "--write" in flags -> Write(
                    flags.getValue("--write")!!,
                    flags["--search-answer"] ?: throw LabUsageError("--write needs --search-answer <file>"),
                    flags["--input"],
                    flags["--persona"],
                    "--fetch-articles" in flags,
                    flags["--articles"],
                )
                "--search-call" in flags -> SearchCall(
                    flags["--input"],
                    flags["--persona"],
                    flags["--model"],
                    when {
                        "--dry-run" in flags && "--confirm-paid" in flags -> throw LabUsageError("choose --dry-run or --confirm-paid, not both")
                        "--dry-run" in flags -> false
                        "--confirm-paid" in flags -> true
                        else -> throw LabUsageError("--search-call needs --dry-run (free, sends nothing) or --confirm-paid (spends tokens, only after the owner's OK)")
                    },
                    "--persona-research" in flags,
                )
                "--fact-check" in flags -> FactCheck(
                    flags.getValue("--fact-check")!!,
                    flags["--run"] ?: throw LabUsageError("--fact-check needs --run <a --write run folder>"),
                    flags["--input"],
                    flags["--persona"],
                    flags["--answer"],
                )
                "--repair" in flags -> Repair(
                    flags.getValue("--repair")!!,
                    flags["--broken"] ?: throw LabUsageError("--repair needs --broken <file>"),
                )
                "--feeds" in flags -> Feeds(flags["--input"])
                "--outlet-check" in flags -> OutletCheck(
                    flags.getValue("--outlet-check")!!,
                    flags["--search-answer"] ?: throw LabUsageError("--outlet-check needs --search-answer <file>"),
                )
                "--check-input" in flags -> CheckInput(
                    flags["--model"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                        ?: throw LabUsageError("--check-input needs --model <id>[,<id>...] (free models only)"),
                    flags["--list"],
                )
                "--models" in flags -> Models
                else -> Balance
            }
        }

        private val MODES = listOf("--write", "--search-call", "--fact-check", "--repair", "--outlet-check", "--feeds", "--probe", "--check-input", "--models", "--balance")
        private val VALUED = setOf(
            "--write", "--search-answer", "--articles", "--input", "--persona", "--model", "--keyword", "--list", "--fact-check", "--run", "--answer", "--repair", "--broken", "--outlet-check",
        )
        private val SWITCHES = setOf(
            "--search-call", "--feeds", "--persona-research", "--probe", "--check-input", "--dry-run", "--confirm-paid", "--fetch-articles", "--models", "--balance", "--help",
        )

        val USAGE = """
            |Based Informant lab. Writes every run to lab-out/<timestamp>-<mode>/ (git-ignored).
            |
            |  --write <model> --search-answer FILE [--input FILE] [--persona P] [--fetch-articles | --articles FILE]
            |                                           free: the writer on a search answer (or a saved template-v1
            |                                           answer turned into one), then selection and the email files;
            |                                           --fetch-articles downloads each source
            |                                           page's text (no tokens) and saves it as articles.json, reusable
            |                                           with --articles; refuses unless the model is at 0x
            |  --search-call [--input FILE] [--persona P] [--model ID] [--persona-research] --dry-run | --confirm-paid
            |                                           the paid search (search.txt; the persona research, off for
            |                                           now, only with --persona-research), then the free source and
            |                                           link checks; --dry-run builds and saves the request, sending
            |                                           nothing; --confirm-paid SPENDS TOKENS, only after the owner's OK
            |  --fact-check <model> --run DIR [--input FILE] [--persona P] [--answer FILE]
            |                                           free: the fact check on the writer answer and article texts
            |                                           of a --write run folder; refuses unless the model is at 0x;
            |                                           --answer re-reads a saved fact-check raw.json, sending nothing
            |  --repair <model> --broken FILE           free: rebuild an unreadable search answer; accepted only when
            |                                           every URL is in the original; refuses unless the model is at 0x
            |  --feeds [--input FILE]                   free, no key: reads Based's feeds (GB News, Fox News politics,
            |                                           Trump's Truth Social posts) and shows their stories for the
            |                                           input's keywords
            |  --outlet-check <model> --search-answer FILE
            |                                           free: Based's outlet check on a saved search answer: which
            |                                           outlets the model calls right-wing, which sources would go;
            |                                           refuses unless the model is at 0x
            |  --probe --model ID [--keyword K] [--input FILE] --dry-run | --confirm-paid
            |                                           does the model search the web? asks for one article from the
            |                                           window on one keyword (default: the input's first) and checks
            |                                           its page; --confirm-paid SPENDS TOKENS, only after the owner's OK
            |  --check-input --model ID[,ID...] [--list FILE]
            |                                           free: the input check on a fixed list of good and bad entries
            |                                           (default lab/inputs/input-check.json), groups of 10, once per
            |                                           model; skips any model not at 0x
            |  --models                                 free: save the models list
            |  --balance                                free: show the token balance
            |
            |--input defaults to lab/inputs/synthetic.json. Put real keywords only in a file under lab-out/.
            |Personas: off, roach-king, doctor-wife, dog-zapper.
            """.trimMargin()
    }
}

/** One paid or free call of a run: its keyword group and the request built for it. */
data class Call(val keywords: List<String>, val request: ChatRequest)

/** Runs one command. Everything external (files, key, client, clock) is injected so tests stay offline. */
class Lab(
    private val root: File,
    private val out: PrintStream,
    private val loadKey: () -> String? = { KeyLoader(repoRoot = root).load() },
    private val clientFor: (String) -> RfabClient,
    private val linkChecker: () -> LinkChecker = { LinkChecker { LinkResult(LinkStatus.UNKNOWN) } },
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val fetchPage: (String) -> String? = ArticleText::fetch,
    private val feedFetcher: () -> FeedFetcher = { FeedFetcher { null } },
) {
    /** Returns the process exit code: 0 done, 1 the run failed or was refused, 2 usage error. */
    suspend fun run(command: LabCommand): Int = when (command) {
        LabCommand.Help -> 0.also { out.println(LabCommand.USAGE) }
        is LabCommand.Write -> write(command)
        is LabCommand.SearchCall -> searchCall(command)
        is LabCommand.FactCheck -> factCheck(command)
        is LabCommand.Repair -> repair(command)
        is LabCommand.OutletCheck -> outletCheck(command)
        is LabCommand.Feeds -> feeds(command)
        is LabCommand.Probe -> probe(command)
        is LabCommand.CheckInput -> checkInput(command)
        LabCommand.Models -> models()
        LabCommand.Balance -> balance()
    }

    /**
     * The free writer (02-ai-call.md section 12) on a search answer, with the article texts when asked: one free model
     * writes, copied quotes are removed, and the topics are joined with the answer's sources, selected and rendered.
     */
    private suspend fun write(command: LabCommand.Write): Int {
        val file = root.resolve(command.searchAnswer).takeIf { it.isFile } ?: File(command.searchAnswer)
        if (!file.isFile) throw LabUsageError("no search answer ${command.searchAnswer}")
        var answer = LegacyAnswer.read(file.readText())?.numbered() ?: throw LabUsageError("cannot read ${file.name} as a search answer")
        val input = input(command.input)
        val persona = LabInput.parsePersona(command.persona ?: input.persona)

        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("write")
        val report = Report(dir).line("mode: write ${command.model}, persona ${persona.name.lowercase()}, answer ${file.name}")
        notFree(client, command.model)?.let { return refuse(report, it) }

        val pages: Map<String, String> = when {
            command.articles != null -> {
                val saved = root.resolve(command.articles).takeIf { it.isFile } ?: File(command.articles)
                if (!saved.isFile) throw LabUsageError("no articles file ${command.articles}")
                Json.decodeFromString(pagesSerializer, saved.readText())
            }
            command.fetchArticles -> answer.urls().mapNotNull { url -> fetchPage(url)?.let { url to it } }.toMap()
            else -> emptyMap()
        }
        if (pages.isNotEmpty() || command.fetchArticles) {
            dir.resolve("articles.json").writeText(pretty.encodeToString(pagesSerializer, pages))
            report.line("article texts: ${pages.size} of ${answer.urls().size} pages (${pages.values.sumOf { it.length }} chars)")
        }
        answer = answer.withPages(pages)
        dir.resolve("search-answer.json").writeText(pretty.encodeToString(SearchAnswer.serializer(), answer))

        val prompt = input.toPromptInput(TemplateConfig.DEFAULT_MODEL, persona).copy(today = LocalDate.parse(answer.searchedAt))
        val request = Writer.request(command.model, prompt, answer)
        val content = send(client, listOf(Call(input.keywords, request)), dir, report, paid = false, command.model, "free writer test")
            .singleOrNull() ?: return refuse(report, "no answer from the writer")
        val raw = Writer.parse(content) ?: return refuse(report, "unreadable writer answer: the raw file is kept")
        val (uncopied, copies) = Writer.removeCopies(raw, answer)
        // The voice pass, as the app runs it with a persona, on the same model; its files go to voice-pass/.
        val voiced = if (persona == Persona.OFF) {
            uncopied
        } else {
            val passDir = dir.resolve("voice-pass").apply { mkdirs() }
            val pass = send(client, listOf(Call(input.keywords, VoicePass.request(command.model, uncopied, prompt))), passDir, report, paid = false, command.model, "free voice pass test")
                .singleOrNull()?.let(VoicePass::parse)
            report.line(if (pass == null) "voice pass: no readable answer, cards as written" else "voice pass: ${pass.cards.size} cards rewritten")
            pass?.let { VoicePass.apply(uncopied, it) } ?: uncopied
        }
        val (written, named) = Writer.removeNames(voiced, answer, Writer.personaNames(persona))
        val deleted = copies + named
        dir.resolve(WRITER_ANSWER).writeText(pretty.encodeToString(WriterAnswer.serializer(), written))
        report.line("subject: ${written.subject}").line("card summary: ${written.cardSummary}")
        written.topics.forEach { report.line("topic: story ${it.story}, tag \"${Writer.cutTag(it.topicTag)}\", sources ${it.sources}") }
        deleted.forEach { report.line(finding(it)) }
        if (copies.isEmpty()) report.line("copied quotes: none")
        if (named.isEmpty()) report.line("persona names: none")
        return renderDigest(written, answer, prompt, persona, command.model, dir, report)
    }

    /** Joins the written topics with the answer's sources, selects them as the app does and writes the digest files. */
    private fun renderDigest(written: WriterAnswer, answer: SearchAnswer, prompt: PromptInput, persona: Persona, model: String, dir: File, report: Report): Int {
        val (digest, findings) = Writer.toDigest(written, answer, prompt)
        findings.forEach { report.line(finding(it)) }
        val selected = digest.copy(topics = Selection.select(digest.topics))
        dir.resolve("digest.json").writeText(pretty.encodeToString(Digest.serializer(), selected))
        val rendered = DigestRenderer.render(selected, RenderMeta(LabInput.displayName(persona), model))
        dir.resolve("digest.html").writeText(rendered.html)
        dir.resolve("digest.txt").writeText(rendered.text)
        report.line("topics: ${digest.topics.size} written; selected: ${selected.topics.size}")
            .line("written: digest.json, digest.html, digest.txt").write(out)
        return 0
    }

    /** A finding for the report: its kind and positions, then the model text it concerns (lab only, never stored). */
    private fun finding(f: Finding): String {
        val i = f.issue
        val at = listOfNotNull(i.story?.let { "story $it" }, i.topic?.let { "card ${it + 1}" }, i.source?.let { "source $it" }, i.sentence?.let { "sentence $it" })
        return "finding: ${i.kind}${at.joinToString(", ", " (", ")").takeIf { at.isNotEmpty() } ?: ""}${f.text?.let { ": $it" } ?: ""}"
    }

    /**
     * The paid search (02-ai-call.md section 12): one call, never retried or re-sent. The answer is saved before anything
     * reads it; a readable one goes through the app's free source and link checks, and the checked copy with the pages'
     * text (search-checked.json) feeds free --write runs. An unreadable one is kept for the parser and --repair.
     */
    private suspend fun searchCall(command: LabCommand.SearchCall): Int {
        val input = input(command.input)
        val persona = LabInput.parsePersona(command.persona ?: input.persona)
        val model = command.model ?: TemplateConfig.DEFAULT_MODEL
        val prompt = input.toPromptInput(model, persona)
        val request = when (val built = SearchRequest.build(prompt, command.research)) {
            is SearchRequest.Result.Built -> built.request
            is SearchRequest.Result.OverLimit -> throw LabUsageError("over the input limits, nothing built: ${built.violations}")
        }
        val head = "${prompt.mode.name.lowercase()} mode, persona ${persona.name.lowercase()}, research ${if (command.research) "on" else "off"}, today ${prompt.today}"
        if (!command.paid) {
            val dir = outDir("search-dry-run")
            dir.resolve("request.json").writeText(pretty.encodeToString(ChatRequest.serializer(), request))
            Report(dir).line("mode: search-call dry-run (nothing sent), $model, $head")
                .line("keywords: ${input.keywords.joinToString("; ")}").sizes(request).write(out)
            return 0
        }
        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("search-call")
        val report = Report(dir).line("mode: search-call, PAID, $model, $head")
        val models = client.models()
        if (models !is RfabResult.Ok) return refuse(report, "could not read the models list (${(models as RfabResult.Error).kind}); nothing sent")
        val rate = models.value.multiplierOf(model) ?: return refuse(report, "$model is not in rfab's models list; nothing sent")
        report.line("rate: ${rate}x")
        val content = send(client, listOf(Call(input.keywords, request)), dir, report, paid = true, model, "paid search")
            .singleOrNull() ?: return refuse(report, "no answer: nothing to read. Never re-sent.")
        val answer = SearchParser.parse(content)?.numbered()
            ?: return refuse(report, "unreadable search answer: raw.json is kept for the parser and --repair, never re-sent")
        dir.resolve("search-answer.json").writeText(pretty.encodeToString(SearchAnswer.serializer(), answer))
        report.line("stories: ${answer.stories.size}; sources: ${answer.urls().size}; persona notes: ${answer.stories.sumOf { it.personaNotes.size }}")
        answer.stories.forEach { s ->
            report.line("story ${s.story}: [${s.keywords.joinToString("; ")}] ${s.whatHappened ?: "-"} (attention ${s.attention ?: "-"})")
            s.sources.forEach { src -> report.line("  source ${src.n}: ${src.publisher} | ${src.published} | ${src.facts.size} facts | ${src.url}") }
        }
        // Based: the free feeds join the paid answer, as the app does (owner, 2026-10-30).
        val (fed, feedIssues) = if (prompt.based) BasedFeeds.stories(feedFetcher(), prompt.keywords, prompt.today) else emptyList<SearchAnswer.Story>() to emptyList()
        if (prompt.based) report.line("feeds: ${fed.size} stories (${fed.count { !it.trending }} on the keywords)")
        feedIssues.forEach { report.line(finding(Finding(it))) }
        val (checked, issues) = SearchCheck.apply(answer.copy(stories = answer.stories + fed), ValidationContext(prompt.keywords, prompt.today))
        // Based: the free outlet check, as the app runs it (owner, 2026-10-30).
        val (sourced, outletIssues) = if (!prompt.based || checked.stories.isEmpty()) checked to emptyList<Issue>() else {
            val verdict = FreeModels.first(client, models.value, FreeModels.CHECKER, { OutletCheck.request(it, checked) }, OutletCheck::parse)
            report.line("outlet check: ${verdict.model ?: "no free model answered"}")
            verdict.value?.let { OutletCheck.apply(checked, it) } ?: (checked to listOf(Issue(Issue.Kind.OUTLETS_NOT_CHECKED)))
        }
        val linked = SearchLinks.apply(sourced, linkChecker(), prompt.today)
        dir.resolve("search-checked.json").writeText(pretty.encodeToString(SearchAnswer.serializer(), linked.answer))
        (issues + outletIssues + linked.issues).forEach { report.line(finding(Finding(it))) }
        val l = linked.report
        report.line("links: ${l.alive} alive, ${l.unknown} unknown (kept), ${l.dead} dead; page dates: ${l.datesFound} found, ${l.datesCorrected} corrected, ${l.datesInFuture} in the future")
            .line("after the checks: ${linked.answer.stories.size} stories, ${linked.answer.urls().size} sources, ${linked.answer.stories.sumOf { s -> s.sources.count { it.pageText != null } }} with page text")
        val found = linked.answer.stories.flatMap { it.keywords }.toSet()
        report.line("keywords with no story: ${prompt.keywords.filter { it !in found }.ifEmpty { listOf("none") }.joinToString("; ")}")
        report.write(out)
        return 0
    }

    /**
     * The fact check (02-ai-call.md section 12, items 7 and 8): one free call reads every card of a --write run next to its
     * article texts; the cards are cut as the app would, then selected and rendered. An unreadable check leaves the cards
     * as written, as in the app.
     */
    private suspend fun factCheck(command: LabCommand.FactCheck): Int {
        val run = root.resolve(command.run).takeIf { it.isDirectory } ?: File(command.run)
        if (!run.isDirectory) throw LabUsageError("no run folder ${command.run}")
        val answer = run.resolve("search-answer.json").takeIf { it.isFile }?.let { LegacyAnswer.read(it.readText()) }
            ?: throw LabUsageError("no search-answer.json in ${command.run}")
        val written = run.resolve(WRITER_ANSWER).takeIf { it.isFile }?.let { Writer.parse(it.readText()) }
            ?: run.resolve("raw.json").takeIf { it.isFile }
                ?.let { lenient.decodeFromString(ChatResponse.serializer(), it.readText()).content }
                ?.let(Writer::parse)?.let { Writer.removeCopies(it, answer).first }
            ?: throw LabUsageError("no readable writer answer in ${command.run}")
        val input = input(command.input)
        val persona = LabInput.parsePersona(command.persona ?: input.persona)
        val prompt = input.toPromptInput(TemplateConfig.DEFAULT_MODEL, persona).copy(today = LocalDate.parse(answer.searchedAt))

        val pages = answer.stories.sumOf { s -> s.sources.count { it.pageText != null } }
        val saved = command.answer?.let { p -> root.resolve(p).takeIf { it.isFile } ?: File(p) }
        val dir = outDir("fact-check")
        val report = Report(dir).line("mode: fact-check ${command.model}, run ${run.name}${saved?.let { ", saved answer ${it.path}" } ?: ""}")
        report.line("cards: ${written.topics.size}; article texts: $pages of ${answer.urls().size} pages")
        val content = if (saved != null) {
            if (!saved.isFile) throw LabUsageError("no saved answer ${command.answer}")
            lenient.decodeFromString(ChatResponse.serializer(), saved.readText()).content ?: return refuse(report, "the saved answer has no content")
        } else {
            val key = loadKey() ?: return noKey()
            out.println(KeyLoader.describe(key))
            val client = clientFor(key)
            notFree(client, command.model)?.let { return refuse(report, it) }
            send(client, listOf(Call(input.keywords, FactCheck.request(command.model, written, answer))), dir, report, paid = false, command.model, "free fact check test")
                .singleOrNull() ?: return refuse(report, "no answer from the fact check: the cards stay as written")
        }
        val check = FactCheck.parse(content) ?: return refuse(report, "unreadable fact check: the cards stay as written; the raw file is kept")
        val (checked, findings) = FactCheck.apply(written, answer, check)
        findings.forEach { report.line(finding(it)) }
        if (findings.isEmpty()) report.line("findings: none")
        report.line("cards kept: ${checked.topics.size} of ${written.topics.size}")
        dir.resolve(WRITER_ANSWER).writeText(pretty.encodeToString(WriterAnswer.serializer(), checked))
        return renderDigest(checked, answer, prompt, persona, command.model, dir, report)
    }

    /**
     * The repair (02-ai-call.md section 12, item 10): one free model rebuilds an unreadable search answer; the app keeps
     * it only when every URL in it is in the original (owner, 2026-10-29), otherwise Send to support is offered.
     */
    private suspend fun repair(command: LabCommand.Repair): Int {
        val file = root.resolve(command.broken).takeIf { it.isFile } ?: File(command.broken)
        if (!file.isFile) throw LabUsageError("no broken answer ${command.broken}")
        val broken = file.readText()

        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("repair")
        val report = Report(dir).line("mode: repair ${command.model}, answer ${file.name}")
        notFree(client, command.model)?.let { return refuse(report, it) }
        val content = send(client, listOf(Call(emptyList(), Repair.request(command.model, broken))), dir, report, paid = false, command.model, "free repair test")
            .singleOrNull() ?: return refuse(report, "no answer from the repair: Send to support would be offered")
        val repaired = Repair.accept(broken, content)
        if (repaired == null) {
            val parsed = SearchParser.parse(content)
            val reason = when {
                parsed == null -> "unreadable repair"
                parsed.stories.isEmpty() -> "the repair holds no story"
                else -> "URLs not in the original answer: " + parsed.urls().filter { it !in broken.replace("\\/", "/") }.joinToString(", ")
            }
            report.line("REJECTED: $reason; Send to support would be offered")
        } else {
            dir.resolve("search-answer.json").writeText(pretty.encodeToString(SearchAnswer.serializer(), repaired))
            report.line("ACCEPTED: ${repaired.stories.size} stories, ${repaired.urls().size} URLs, all in the original")
            repaired.stories.forEachIndexed { i, s ->
                report.line("story ${i + 1}: ${s.whatHappened ?: "-"} | ${s.sources.size} sources, ${s.sources.sumOf { it.facts.size }} facts, ${s.personaNotes.size} notes")
            }
        }
        report.write(out)
        return 0
    }

    /** Based's feeds today: no key, no tokens; each feed's stories for the input's keywords. */
    private suspend fun feeds(command: LabCommand.Feeds): Int {
        val input = command.input?.let { input(it) }
        val keywords = input?.keywords.orEmpty()
        val today = input?.todayDate() ?: LocalDate.parse(now().toLocalDate().toString())
        val dir = outDir("feeds")
        val report = Report(dir).line("mode: feeds, keywords ${keywords.ifEmpty { listOf("none") }.joinToString("; ")}, today $today")
        val (stories, issues) = BasedFeeds.stories(feedFetcher(), keywords, today)
        issues.forEach { report.line(finding(Finding(it))) }
        stories.forEach { s ->
            val src = s.sources.single()
            report.line("${if (s.trending) "trending" else "[" + s.keywords.joinToString("; ") + "]"} | ${src.publisher} | ${src.published} | ${s.whatHappened} | ${src.url}")
        }
        dir.resolve("feeds.json").writeText(pretty.encodeToString(SearchAnswer.serializer(), SearchAnswer(searchedAt = today.toString(), stories = stories)))
        report.line("stories: ${stories.size} (${stories.count { !it.trending }} on the keywords)")
        report.write(out)
        return 0
    }

    /** Based's outlet check on a saved answer: one free call, the verdict per outlet and the sources it would drop. */
    private suspend fun outletCheck(command: LabCommand.OutletCheck): Int {
        val file = root.resolve(command.searchAnswer).takeIf { it.isFile } ?: File(command.searchAnswer)
        if (!file.isFile) throw LabUsageError("no search answer ${command.searchAnswer}")
        val answer = SearchAnswer.decode(file.readText()).numbered()
        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("outlet-check")
        val report = Report(dir).line("mode: outlet-check ${command.model}, answer ${file.name}")
        notFree(client, command.model)?.let { return refuse(report, it) }
        val content = send(client, listOf(Call(emptyList(), OutletCheck.request(command.model, answer))), dir, report, paid = false, command.model, "free outlet check")
            .singleOrNull() ?: return refuse(report, "no answer from the outlet check")
        val rightWing = OutletCheck.parse(content) ?: return refuse(report, "unreadable outlet check: raw.json is kept")
        OutletCheck.outlets(answer).forEachIndexed { i, o ->
            report.line("${if ((i + 1) in rightWing) "RIGHT-WING" else "dropped   "} ${o.publisher} (${o.domain})")
        }
        val (kept, issues) = OutletCheck.apply(answer, rightWing)
        issues.forEach { report.line(finding(Finding(it))) }
        report.line("kept: ${kept.stories.size} of ${answer.stories.size} stories, ${kept.urls().size} of ${answer.urls().size} sources")
        report.write(out)
        return 0
    }

    /** Null when [model] shows exactly 0x in the models list; otherwise why nothing is sent. */
    private suspend fun notFree(client: RfabClient, model: String): String? {
        val models = client.models()
        if (models !is RfabResult.Ok) return "could not read the models list (${(models as RfabResult.Error).kind}); nothing sent"
        val multiplier = models.value.multiplierOf(model)
        return if (multiplier == 0.0) null else "could not confirm that $model is free (0x): rate ${multiplier ?: "not found"}; nothing sent"
    }

    /**
     * Sends [calls] one at a time, saving each raw answer before anything reads it (02-ai-call.md section 8). Stops at
     * the first failed call, and for paid calls also after one over the stop rule. Returns the answers received, null
     * for a call that gave no content; calls never sent are left out.
     */
    private suspend fun send(
        client: RfabClient,
        calls: List<Call>,
        dir: File,
        report: Report,
        paid: Boolean,
        model: String,
        purpose: String,
    ): List<String?> {
        val contents = mutableListOf<String?>()
        var total = 0L
        for ((i, call) in calls.withIndex()) {
            val request = call.request
            val label = if (calls.size > 1) "call ${i + 1}/${calls.size}: " else ""
            dir.resolve(fileName("request", i, calls.size)).writeText(pretty.encodeToString(ChatRequest.serializer(), request))
            if (calls.size > 1) report.line("$label${call.keywords.joinToString("; ")}")
            report.sizes(request)
            val before = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance
            val started = clockMillis()
            val response = client.chat(request)
            val seconds = (clockMillis() - started) / 1000.0
            val after = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance
            report.line("${label}latency: ${"%.1f".format(seconds)} s; balance before -> after: ${before ?: "?"} -> ${after ?: "?"}")
            val ok = when (response) {
                is RfabResult.Error -> {
                    response.raw?.let { dir.resolve(fileName("raw", i, calls.size)).writeText(it) }
                    report.line("${label}call failed: ${response.kind} (HTTP ${response.httpStatus ?: "-"}, ${response.code ?: "no code"})${charged(response)}. Never re-sent automatically.")
                    if (i < calls.size - 1) report.line("stopped: the remaining ${calls.size - 1 - i} calls were not sent")
                    contents += null
                    break
                }
                is RfabResult.Ok -> response
            }
            dir.resolve(fileName("raw", i, calls.size)).writeText(ok.raw)
            val billed = ok.value.tokensBilled ?: if (paid) before?.let { b -> after?.let { b - it } } else null
            report.line("${label}tokensBilled: ${ok.value.tokensBilled ?: "not in the response"}; usage: ${ok.value.usage}")
            ok.value.servedVia?.let { report.line("servedVia: $it, rerouted: ${ok.value.rerouted}") }
            if (paid) {
                report.line("for docs/api-spend-log.md:").line(spendLogLine(purpose, model, call.keywords.size, request, billed ?: -1, before, after))
            } else if (billed != null && billed != 0L) {
                report.line("WARNING: a free model billed $billed tokens. Log it in docs/api-spend-log.md:")
                    .line(spendLogLine(purpose, model, call.keywords.size, request, billed, before, after))
            }
            if (ok.value.content == null) report.line("${label}the response has no content")
            contents += ok.value.content
            total += billed ?: 0
            if (paid && billed != null && billed > STOP_RULE_TOKENS) {
                report.line("STOP RULE: this call billed $billed tokens, over $STOP_RULE_TOKENS. Stop and discuss with the owner before any other paid call.")
                if (i < calls.size - 1) report.line("stopped: the remaining ${calls.size - 1 - i} calls were not sent")
                break
            }
        }
        if (calls.size > 1 && paid) report.line("total billed: $total tokens over ${contents.size} calls")
        return contents
    }

    /**
     * The search probe (02-ai-call.md section 11, Model plan step 3): one tiny request, then the app checks the returned
     * page. Paid only with [LabCommand.Probe.paid], after the owner's OK; never retried or re-sent.
     */
    private suspend fun probe(command: LabCommand.Probe): Int {
        val input = input(command.input)
        val keyword = command.keyword ?: input.keywords.firstOrNull() ?: throw LabUsageError("no keyword: pass --keyword")
        val window = ProbeWindow(input.todayDate().minus(DatePeriod(days = ProbeWindow.DAYS)), input.todayDate())
        val request = ChatRequest(
            model = command.model,
            messages = listOf(ChatMessage("user", probeMessage(keyword, window))),
            systemPrompt = PROBE_SYSTEM,
            maxTokens = PROBE_MAX_TOKENS,
            temperature = 0.2,
        )
        if (!command.paid) {
            val dir = outDir("probe-dry-run")
            val body = pretty.encodeToString(ChatRequest.serializer(), request)
            dir.resolve("request.json").writeText(body)
            out.println(body)
            Report(dir).line("mode: probe dry-run (nothing sent), ${command.model}").sizes(request).write(out)
            return 0
        }

        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("probe")
        val report = Report(dir).line("mode: probe, PAID, ${command.model}, keyword \"$keyword\"")
        val models = client.models()
        if (models !is RfabResult.Ok) return refuse(report, "could not read the models list (${(models as RfabResult.Error).kind}); nothing sent")
        val rate = models.value.multiplierOf(command.model) ?: return refuse(report, "${command.model} is not in rfab's models list; nothing sent")
        report.line("rate: ${rate}x")
        dir.resolve("request.json").writeText(pretty.encodeToString(ChatRequest.serializer(), request))
        report.sizes(request)

        val before = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance
        val started = clockMillis()
        val response = client.chat(request)
        val seconds = (clockMillis() - started) / 1000.0
        val after = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance
        report.line("latency: ${"%.1f".format(seconds)} s; balance before -> after: ${before ?: "?"} -> ${after ?: "?"}")
        val ok = when (response) {
            is RfabResult.Error -> {
                response.raw?.let { dir.resolve("raw.json").writeText(it) }
                return refuse(
                    report,
                    "call failed: ${response.kind} (HTTP ${response.httpStatus ?: "-"}, ${response.code ?: "no code"})${charged(response)}. Never re-sent automatically.",
                )
            }
            is RfabResult.Ok -> response
        }
        // The raw answer is saved before anything reads it (02-ai-call.md section 8).
        dir.resolve("raw.json").writeText(ok.raw)
        val billed = ok.value.tokensBilled ?: before?.let { b -> after?.let { b - it } }
        report.line("tokensBilled: ${ok.value.tokensBilled ?: "not in the response"}; usage: ${ok.value.usage}")
        if (billed != null && billed > STOP_RULE_TOKENS) {
            report.line("STOP RULE: this call billed $billed tokens, over $STOP_RULE_TOKENS. Stop and discuss with the owner before any other paid call.")
        }
        report.line("for docs/api-spend-log.md:")
            .line(spendLogLine("paid search probe", command.model, 1, request, billed ?: -1, before, after))

        val article = ok.value.content?.let(::probeArticle)
        val url = article?.get("url")?.jsonPrimitive?.contentOrNull
        if (article == null || url == null) {
            report.line("answer: ${ok.value.content?.take(300) ?: "no content"}")
            report.line("verdict: NO SEARCH (no article URL in the answer)").write(out)
            return 0
        }
        fun field(name: String) = article[name]?.jsonPrimitive?.contentOrNull
        report.line("article: ${field("title")} | ${field("publisher")} | $url | published (model): ${field("published")}")
        val link = linkChecker().check(url)
        val pageDate = link.pageDate
        report.line("link: ${link.status} (${link.reason}); page date: ${pageDate ?: "none found"}")
        val verdict = when {
            link.status == LinkStatus.DEAD -> "NO SEARCH (dead or made-up URL)"
            pageDate == null -> "UNCLEAR (no machine-readable page date: open the page and check by hand)"
            pageDate < window.start || pageDate > window.end.plus(DatePeriod(days = 1)) ->
                "SEARCHES, WRONG DATE (a live page dated $pageDate, outside the window ${window.start} to ${window.end})"
            else -> "SEARCHES (a live article page dated inside the window)"
        }
        report.line("verdict: $verdict").write(out)
        return 0
    }

    private fun probeMessage(keyword: String, window: ProbeWindow): String =
        "Search the web for one news article published from ${window.start} to ${window.end} (both inclusive) about " +
            "\"$keyword\". Reply with one JSON object and nothing else: " +
            "{\"title\": \"...\", \"publisher\": \"...\", \"url\": \"the article's direct https URL\", \"published\": \"YYYY-MM-DD\"}. " +
            "If you cannot search the web or find no such article, reply {\"url\": null}. Never invent a URL."

    /** The first JSON object in the answer, fences or text around it allowed; null when there is none. */
    private fun probeArticle(content: String): JsonObject? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            Json.parseToJsonElement(content.substring(start, end + 1)) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Free only: every model must show exactly 0x in the models list, and the balance is compared before and after.
     * One request at a time; a failed or unreadable group is reported, never re-sent.
     */
    private suspend fun checkInput(command: LabCommand.CheckInput): Int {
        val file = command.list?.let { p -> root.resolve(p).takeIf { it.isFile } ?: File(p) } ?: root.resolve(CHECK_LIST)
        if (!file.isFile) throw LabUsageError("no list file ${command.list ?: CHECK_LIST}")
        val items = try {
            lenient.decodeFromString(CheckList.serializer(), file.readText()).entries
        } catch (e: IllegalArgumentException) {
            throw LabUsageError("cannot read ${file.name}: ${e.message?.lineSequence()?.firstOrNull()}")
        }.shuffled(Random(CHECK_SEED))
        val groups = items.chunked(CHECK_GROUP)

        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val client = clientFor(key)
        val dir = outDir("check-input")
        val report = Report(dir).line("mode: check-input, ${items.size} entries in ${groups.size} groups, list ${file.name}")
        val models = client.models()
        if (models !is RfabResult.Ok) return refuse(report, "could not read the models list (${(models as RfabResult.Error).kind}); nothing sent")
        val before = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance

        for ((m, model) in command.models.withIndex()) {
            val rate = models.value.multiplierOf(model)
            if (rate != 0.0) {
                report.line("$model: SKIPPED, rate ${rate ?: "not found"} (free models only)")
                continue
            }
            val slug = model.substringAfterLast('/').replace(Regex("[^A-Za-z0-9.-]"), "_")
            var caught = 0
            var bad = 0
            val wrong = mutableListOf<String>()
            val missed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            var seconds = 0.0
            for ((g, group) in groups.withIndex()) {
                val name = "${m + 1}-$slug-${g + 1}"
                val entries = InputCheck.entries(group.map { it.text }, emptyList())
                val request = InputCheck.request(model, entries)
                dir.resolve("$name-request.json").writeText(pretty.encodeToString(ChatRequest.serializer(), request))
                val started = clockMillis()
                val response = client.chat(request)
                seconds += (clockMillis() - started) / 1000.0
                val ok = when (response) {
                    is RfabResult.Error -> {
                        response.raw?.let { dir.resolve("$name-raw.json").writeText(it) }
                        failed += "group ${g + 1}: ${response.kind} (HTTP ${response.httpStatus ?: "-"}, ${response.code ?: "no code"})${charged(response)}"
                        continue
                    }
                    is RfabResult.Ok -> response
                }
                dir.resolve("$name-raw.json").writeText(ok.raw)
                ok.value.tokensBilled?.takeIf { it != 0L }?.let {
                    report.line("WARNING: $model billed $it tokens on group ${g + 1}: log it in docs/api-spend-log.md")
                }
                val refused = ok.value.content?.let { InputCheck.parse(it, entries) }
                if (refused == null) {
                    failed += "group ${g + 1}: unreadable answer: ${ok.value.content?.take(120)?.replace('\n', ' ') ?: "no content"}"
                    continue
                }
                val reasons = refused.associate { it.entry.index to it.reason }
                group.forEachIndexed { i, item ->
                    val isRefused = i in reasons
                    when (item.expect) {
                        "refuse" -> {
                            bad++
                            if (isRefused) caught++ else missed += item.text
                        }
                        "ok" -> if (isRefused) wrong += "${item.text} (${reasons[i] ?: "no reason"})"
                        else -> report.line("$model: not scored: \"${item.text}\" ${if (isRefused) "refused (${reasons[i] ?: "no reason"})" else "accepted"}")
                    }
                }
            }
            val good = items.count { it.expect == "ok" }
            report.line(
                "$model: nonsense caught $caught / $bad; real entries refused ${wrong.size} / $good; " +
                    "groups failed ${failed.size} / ${groups.size}; ${"%.1f".format(seconds)} s in all",
            )
            missed.forEach { report.line("  missed: $it") }
            wrong.forEach { report.line("  wrongly refused: $it") }
            failed.forEach { report.line("  failed: $it") }
        }
        val after = (client.balance() as? RfabResult.Ok)?.value?.tokenBalance
        val changed = if (before != null && after != null && before != after) " (CHANGED: check the transactions and log it)" else ""
        report.line("balance before -> after: ${before ?: "?"} -> ${after ?: "?"}$changed")
        report.write(out)
        return 0
    }

    @Serializable
    private data class CheckList(val entries: List<CheckItem>)

    /** [expect]: "ok", "refuse", or "any" (reported, not scored). */
    @Serializable
    private data class CheckItem(val text: String, val expect: String, val note: String? = null)

    private suspend fun models(): Int {
        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        val dir = outDir("models")
        return when (val result = clientFor(key).models()) {
            is RfabResult.Ok -> 0.also {
                dir.resolve("models.json").writeText(result.raw)
                out.println("${result.value.models.size} models; free (0x): ${result.value.freeModels().joinToString(", ")}")
                out.println("models list saved to ${dir.relativeTo(root).path}/models.json")
            }
            is RfabResult.Error -> 1.also { out.println("could not read the models list: ${result.kind}") }
        }
    }

    private suspend fun balance(): Int {
        val key = loadKey() ?: return noKey()
        out.println(KeyLoader.describe(key))
        return when (val result = clientFor(key).balance()) {
            is RfabResult.Ok -> 0.also { out.println("token balance: ${result.value.tokenBalance}") }
            is RfabResult.Error -> 1.also { out.println("could not read the balance: ${result.kind}") }
        }
    }

    private fun fileName(base: String, index: Int, count: Int) = if (count == 1) "$base.json" else "$base-${index + 1}.json"

    private fun spendLogLine(purpose: String, model: String, keywords: Int, request: ChatRequest, billed: Long, before: Long?, after: Long?): String {
        val chars = request.systemPrompt.length + request.messages.sumOf { it.content.length }
        return "| ${now().toLocalDate()} | $purpose | $model | $keywords / $chars / ${request.maxTokens} | " +
            "$billed | ${before ?: "?"} → ${after ?: "?"} | |"
    }

    private fun input(path: String?): LabInput {
        val file = path?.let { p -> root.resolve(p).takeIf { it.isFile } ?: File(p) } ?: root.resolve(DEFAULT_INPUT)
        if (!file.isFile) throw LabUsageError("no input file ${path ?: DEFAULT_INPUT}")
        return try {
            LabInput.read(file)
        } catch (e: IllegalArgumentException) {
            throw LabUsageError("cannot read ${file.name}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
    }

    private fun outDir(mode: String): File {
        val base = "${now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-$mode"
        var dir = root.resolve("lab-out/$base")
        var n = 2
        while (dir.exists()) dir = root.resolve("lab-out/$base-${n++}")
        return dir.apply { mkdirs() }
    }

    private fun noKey(): Int {
        out.println("no rfab key found. Run sh scripts/set-rfab-key in Git Bash, restart the terminal, then ./gradlew --stop.")
        return 1
    }

    private fun refuse(report: Report, message: String): Int = 1.also { report.line(message).write(out) }

    /** A failed call the balance check found charged still goes in docs/api-spend-log.md (AGENTS.md section 1). */
    private fun charged(error: RfabResult.Error): String =
        error.tokensBilled?.let { ", charged $it tokens: log it in docs/api-spend-log.md" } ?: ""

    /** Collects report.txt; printed at the end of the run. */
    private inner class Report(private val dir: File) {
        private val lines = mutableListOf<String>()

        fun line(text: String) = apply { lines += text }

        fun sizes(request: ChatRequest) = apply {
            val body = Json.encodeToString(ChatRequest.serializer(), request)
            val promptChars = request.systemPrompt.length + request.messages.sumOf { it.content.length }
            line("payload: ${body.length} chars, ${body.toByteArray(Charsets.UTF_8).size} bytes")
            line("estimate: input about ${promptChars / 4} tokens (chars / 4), output at most ${request.maxTokens} tokens")
            line("billed = provider usage x the model's multiplier, plus per-source fees on web-search models (not capped by maxTokens)")
        }

        fun write(out: PrintStream) {
            dir.resolve("report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
            lines.forEach(out::println)
            out.println("run folder: ${dir.relativeTo(root).path}")
        }
    }

    companion object {
        const val DEFAULT_INPUT = "lab/inputs/synthetic.json"

        /** Owner's stop rule (2026-10-18): a paid test call billing more than this stops the session for a talk. */
        const val STOP_RULE_TOKENS = 15_000L
        const val PROBE_MAX_TOKENS = 300
        const val CHECK_LIST = "lab/inputs/input-check.json"

        /** The writer answer a --write run saves, after copied quotes are removed; read back by --fact-check. */
        const val WRITER_ANSWER = "writer-answer.json"

        /** About what the app sends: 5 keywords and a few tags. */
        const val CHECK_GROUP = 10
        private const val CHECK_SEED = 7
        private val lenient = Json { ignoreUnknownKeys = true }
        private const val PROBE_SYSTEM = "You find news articles on the web and answer with JSON only."
        private val pretty = Json { prettyPrint = true }
        private val pagesSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}

/** The search probe's period (Model plan step 3): the last week, lab only; the app has no date window since 2026-10-29. */
data class ProbeWindow(val start: LocalDate, val end: LocalDate) {
    companion object {
        const val DAYS = 7
    }
}
