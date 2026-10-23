package io.github.hugo_cacciatori.basedinformant.core.rfab

import kotlinx.serialization.Serializable

/**
 * `GET /api/chat/completions/models` (free). Shape seen 2026-10-18:
 * `{"success": true, "count": 170, "models": [{"model": "provider:model", "rateMultiplier": 0.36}, ...]}`.
 */
@Serializable
data class ModelCatalog(val success: Boolean = false, val count: Int? = null, val models: List<ModelRate> = emptyList()) {
    /** The billing multiplier of [modelId], or null when it is missing or listed more than once (treat as paid). */
    fun multiplierOf(modelId: String): Double? = models.filter { it.model == modelId }.singleOrNull()?.rateMultiplier

    fun freeModels(): List<String> = models.filter { it.rateMultiplier == 0.0 }.map { it.model }
}

@Serializable
data class ModelRate(val model: String, val rateMultiplier: Double)
