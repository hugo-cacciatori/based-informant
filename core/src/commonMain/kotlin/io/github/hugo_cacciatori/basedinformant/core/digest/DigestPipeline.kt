package io.github.hugo_cacciatori.basedinformant.core.digest

/**
 * Local post-processing of one answer (ai-call.md section 8), all free: parse, validate (which drops
 * non-`https` sources, topics left with no source, duplicate ids and URLs), check links, then select.
 */
object DigestPipeline {
    sealed interface Result {
        /** [digest] holds the selected topics only; it may have none. [links] is null when links were not checked. */
        data class Processed(
            val digest: Digest,
            val issues: List<Issue>,
            val validTopicCount: Int,
            val links: LinkReport? = null,
        ) : Result

        data class Unreadable(val parseFailure: DigestParser.Failure?, val validationFailure: DigestValidator.Failure?) : Result
    }

    /** Without the link check: for fixtures and free-model tests, whose URLs are made up. */
    fun process(content: String, context: ValidationContext): Result {
        val valid = when (val v = validate(content, context)) {
            is Validated.Ok -> v
            is Validated.Failed -> return v.result
        }
        return Result.Processed(valid.digest.copy(topics = Selection.select(valid.digest.topics)), valid.issues, valid.digest.topics.size)
    }

    /** With the link check, for real answers. Links are checked before selection, as the spec orders it. */
    suspend fun process(content: String, context: ValidationContext, checker: LinkChecker): Result {
        val valid = when (val v = validate(content, context)) {
            is Validated.Ok -> v
            is Validated.Failed -> return v.result
        }
        val (checked, linkIssues, report) = LinkStage.apply(valid.digest, checker, context.window)
        return Result.Processed(
            checked.copy(topics = Selection.select(checked.topics)),
            valid.issues + linkIssues,
            checked.topics.size,
            report,
        )
    }

    /**
     * A split run (ai-call.md section 11 "Coverage plan"): one answer per keyword group, null when a call gave none.
     * Readable answers are merged (a later topic with the same id or a shared source URL is dropped), then checked
     * and selected once. Unreadable only when no answer is readable; [Result.Processed.issues] lists the failed calls.
     */
    fun processAll(contents: List<String?>, context: ValidationContext): Result {
        val (digest, issues) = when (val m = merge(contents, context)) {
            is Validated.Ok -> m
            is Validated.Failed -> return m.result
        }
        return Result.Processed(digest.copy(topics = Selection.select(digest.topics)), issues, digest.topics.size)
    }

    /** [processAll] with the link check, for real answers. */
    suspend fun processAll(contents: List<String?>, context: ValidationContext, checker: LinkChecker): Result {
        val (digest, issues) = when (val m = merge(contents, context)) {
            is Validated.Ok -> m
            is Validated.Failed -> return m.result
        }
        val (checked, linkIssues, report) = LinkStage.apply(digest, checker, context.window)
        return Result.Processed(checked.copy(topics = Selection.select(checked.topics)), issues + linkIssues, checked.topics.size, report)
    }

    private fun merge(contents: List<String?>, context: ValidationContext): Validated {
        val issues = mutableListOf<Issue>()
        val readable = mutableListOf<Pair<Int, Digest>>()
        var firstFailure: Result.Unreadable? = null
        contents.forEachIndexed { call, content ->
            when (val v = content?.let { validate(it, context) }) {
                is Validated.Ok -> {
                    readable += call to v.digest
                    issues += v.issues.map { it.copy(call = call) }
                }
                else -> {
                    if (firstFailure == null) firstFailure = (v as? Validated.Failed)?.result ?: Result.Unreadable(null, null)
                    issues += Issue(Issue.Kind.CALL_UNREADABLE, call = call)
                }
            }
        }
        if (readable.isEmpty()) return Validated.Failed(firstFailure ?: Result.Unreadable(null, null))

        val ids = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val topics = mutableListOf<Topic>()
        for ((call, digest) in readable) {
            digest.topics.forEachIndexed { index, topic ->
                val topicUrls = topic.sources.map { DigestValidator.normalizeUrl(it.url) }
                if (topic.id in ids || topicUrls.any { it in urls }) {
                    issues += Issue(Issue.Kind.TOPIC_DUPLICATE_STORY, index, call = call)
                } else {
                    ids += topic.id
                    urls += topicUrls
                    topics += topic
                }
            }
        }
        return Validated.Ok(readable.first().second.copy(topics = topics), issues)
    }

    private sealed interface Validated {
        data class Ok(val digest: Digest, val issues: List<Issue>) : Validated
        data class Failed(val result: Result.Unreadable) : Validated
    }

    private fun validate(content: String, context: ValidationContext): Validated {
        val parsed = when (val p = DigestParser.parse(content)) {
            is DigestParser.Result.Failed -> return Validated.Failed(Result.Unreadable(p.reason, null))
            is DigestParser.Result.Parsed -> p
        }
        val valid = when (val v = DigestValidator.validate(parsed.json, context)) {
            is DigestValidator.Result.Invalid -> return Validated.Failed(Result.Unreadable(null, v.reason))
            is DigestValidator.Result.Valid -> v
        }
        val issues = if (parsed.truncated) listOf(Issue(Issue.Kind.TRUNCATED)) + valid.issues else valid.issues
        return Validated.Ok(valid.digest, issues)
    }
}
