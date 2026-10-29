package io.github.hugo_cacciatori.basedinformant.core.settings

import io.github.hugo_cacciatori.basedinformant.core.limits.LimitViolation
import io.github.hugo_cacciatori.basedinformant.core.prompt.TemplateConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun defaultsMatchTheSpec() {
        val settings = AppSettings()
        assertEquals(emptyList(), settings.tags)
        assertEquals(AttentionMode.BALANCED, settings.attentionMode)
        assertEquals(Persona.OFF, settings.persona)
        assertEquals(TemplateConfig.DEFAULT_MODEL, settings.model)
        assertEquals("plain", settings.theme)
        assertTrue(settings.animations)
        assertTrue(settings.violations().isEmpty())
    }

    @Test
    fun brokenLimitsAreReported() {
        val settings = AppSettings(tags = listOf(" ", "a".repeat(31)))
        assertEquals(listOf(LimitViolation.BlankTag(0), LimitViolation.TagTooLong(1, 31)), settings.violations())
    }

    @Test
    fun anUnofferedModelFallsBackToTheDefault() {
        assertEquals(TemplateConfig.DEFAULT_MODEL, AppSettings(model = "gone:model").withValidModel().model)
    }

    @Test
    fun generateBlocksInTheirOrder() {
        assertEquals(emptyList(), GenerateRules.blocks(hasKey = true, keywordCount = 5, runActive = false))
        assertEquals(
            listOf(GenerateBlock.RUN_ACTIVE, GenerateBlock.NO_KEY, GenerateBlock.TOO_FEW_KEYWORDS),
            GenerateRules.blocks(hasKey = false, keywordCount = 0, runActive = true),
        )
        // A digest takes exactly 5 keywords (owner, 2026-10-20).
        assertEquals(listOf(GenerateBlock.TOO_FEW_KEYWORDS), GenerateRules.blocks(hasKey = true, keywordCount = 4, runActive = false))
    }
}
