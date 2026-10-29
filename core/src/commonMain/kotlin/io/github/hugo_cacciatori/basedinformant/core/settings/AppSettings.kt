package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.limits.InputChecks
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.LimitViolation
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig

/**
 * The settings that feed the call (01-product.md section 3), with their defaults. Email, theme and reminder join
 * in their own stages. The key is not here: it lives in the [SecretStore].
 */
data class AppSettings(
    val tags: List<String> = emptyList(),
    val attentionMode: AttentionMode = AttentionMode.BALANCED,
    val persona: Persona = Persona.OFF,
    val model: String = TemplateConfig.DEFAULT_MODEL,
) {
    /** Broken limits; settings with any cannot be saved. */
    fun violations(): List<LimitViolation> = InputChecks.tags(tags)

    /** A model that is no longer offered falls back to the default, so an update never leaves an unknown model. */
    fun withValidModel(): AppSettings =
        if (model in TemplateConfig.ALLOWED_MODELS) this else copy(model = TemplateConfig.DEFAULT_MODEL)
}

/** Why Generate is disabled (01-product.md section 3), in the order they are shown; the UI shows the first. */
enum class GenerateBlock { RUN_ACTIVE, NO_KEY, TOO_FEW_KEYWORDS, NO_TOPIC_CHOSEN }

object GenerateRules {
    fun blocks(hasKey: Boolean, keywordCount: Int, runActive: Boolean): List<GenerateBlock> = buildList {
        if (runActive) add(GenerateBlock.RUN_ACTIVE)
        if (!hasKey) add(GenerateBlock.NO_KEY)
        // A digest takes exactly InputLimits.KEYWORDS_MAX keywords; the list cannot hold more.
        if (keywordCount < InputLimits.KEYWORDS_MAX) add(GenerateBlock.TOO_FEW_KEYWORDS)
    }

    /** Topic mode: one to [InputLimits.KEYWORDS_MAX] chosen topic tags (01-product.md section 3). */
    fun topicBlocks(hasKey: Boolean, chosenCount: Int, runActive: Boolean): List<GenerateBlock> = buildList {
        if (runActive) add(GenerateBlock.RUN_ACTIVE)
        if (!hasKey) add(GenerateBlock.NO_KEY)
        if (chosenCount == 0) add(GenerateBlock.NO_TOPIC_CHOSEN)
    }
}
