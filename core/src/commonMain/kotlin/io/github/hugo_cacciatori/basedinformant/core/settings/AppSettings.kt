package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.limits.InputChecks
import io.github.hugo_cacciatori.basedinformant.core.limits.InputLimits
import io.github.hugo_cacciatori.basedinformant.core.limits.LimitViolation
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import io.github.hugo_cacciatori.basedinformant.core.reminder.Reminder
import io.github.hugo_cacciatori.basedinformant.core.theme.BuiltInThemes

/**
 * The settings (01-product.md section 3), with their defaults: what feeds the call, the reminder (section 6) and the
 * look (section 10). The key is not here: it lives in the [SecretStore].
 */
data class AppSettings(
    val tags: List<String> = emptyList(),
    val attentionMode: AttentionMode = AttentionMode.BALANCED,
    val persona: Persona = Persona.OFF,
    val model: String = TemplateConfig.DEFAULT_MODEL,
    val reminder: Reminder = Reminder(),
    /** Right-wing sources only (01-product.md section 3, owner 2026-10-29). */
    val based: Boolean = false,
    /** A built-in theme or a kept generated one ([BuiltInThemes.isValidId]); a missing one falls back to the default. */
    val theme: String = BuiltInThemes.DEFAULT,
    val animations: Boolean = true,
) {
    /** Broken limits; settings with any cannot be saved. */
    fun violations(): List<LimitViolation> = InputChecks.tags(tags)

    /** A model that is no longer offered falls back to the default, so an update never leaves an unknown model. */
    fun withValidModel(): AppSettings =
        if (model in TemplateConfig.ALLOWED_MODELS) this else copy(model = TemplateConfig.DEFAULT_MODEL)
}

/** Why Generate is disabled (01-product.md section 3), in the order they are shown; the UI shows the first. */
enum class GenerateBlock { RUN_ACTIVE, NO_KEY, TOO_FEW_KEYWORDS }

object GenerateRules {
    fun blocks(hasKey: Boolean, keywordCount: Int, runActive: Boolean): List<GenerateBlock> = buildList {
        if (runActive) add(GenerateBlock.RUN_ACTIVE)
        if (!hasKey) add(GenerateBlock.NO_KEY)
        // 1 to InputLimits.KEYWORDS_MAX keywords (owner, 2026-10-29); the list cannot hold more.
        if (keywordCount < InputLimits.KEYWORDS_MIN) add(GenerateBlock.TOO_FEW_KEYWORDS)
    }

    /** Topic mode: 0 to [InputLimits.KEYWORDS_MAX] chosen topics; none gives an all-trending digest (owner, 2026-10-29). */
    fun topicBlocks(hasKey: Boolean, runActive: Boolean): List<GenerateBlock> = buildList {
        if (runActive) add(GenerateBlock.RUN_ACTIVE)
        if (!hasKey) add(GenerateBlock.NO_KEY)
    }
}
