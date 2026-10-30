package io.github.hugo_cacciatori.basedinformant.core.theme

import io.github.hugo_cacciatori.basedinformant.core.check.FreeModels
import io.github.hugo_cacciatori.basedinformant.core.rfab.ModelCatalog
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabClient
import io.github.hugo_cacciatori.basedinformant.core.rfab.RfabResult
import kotlin.random.Random

/**
 * The AI theme chain (01-product.md section 10, owner 2026-10-30), free models only: brief → free designer → free critic
 * (its improvement kept only when it parses) → code clamp → code repair → [PackCheck]. Each free call goes through
 * [FreeModels.first], which never uses a model that is not at 0x.
 */
class ThemeGenerator(private val client: RfabClient, private val random: Random = Random.Default) {
    /** Where the chain stands, for the progress line. */
    enum class Stage { DESIGNING, REVIEWING, CHECKING }

    sealed interface Outcome {
        /** A theme that passed every check: shown as a preview, kept only when the owner says so. */
        data class Designed(val pack: ThemePack, val brief: ThemeDesigner.Brief, val reviewed: Boolean) : Outcome

        /** No free model answered, or the models list could not be read. */
        data object NoFreeModel : Outcome

        /** The design could not be made readable: shown as an error, never applied. */
        data class Refused(val problems: List<String>) : Outcome
    }

    suspend fun generate(idea: String, id: String, onStage: (Stage) -> Unit = {}): Outcome {
        val catalog = (client.models() as? RfabResult.Ok<ModelCatalog>)?.value ?: return Outcome.NoFreeModel
        val brief = ThemeDesigner.brief(idea, random)
        onStage(Stage.DESIGNING)
        val draft = FreeModels.first(client, catalog, MODELS, { ThemeDesigner.designRequest(it, brief) }) { ThemeDesigner.parse(it, id, brief.seed) }.value
            ?: return Outcome.NoFreeModel
        onStage(Stage.REVIEWING)
        val reviewed = FreeModels.first(client, catalog, MODELS, { ThemeDesigner.critiqueRequest(it, brief, draft) }) { ThemeDesigner.parse(it, id, brief.seed) }.value
        onStage(Stage.CHECKING)
        val (pack, problems) = ThemeDesigner.repair(ThemeDesigner.clamp(reviewed ?: draft))
        return if (problems.isEmpty()) Outcome.Designed(pack, brief, reviewed != null) else Outcome.Refused(problems)
    }

    companion object {
        /** The writer's order: the strongest free models first (02-ai-call.md section 12). */
        val MODELS = FreeModels.WRITER
    }
}
