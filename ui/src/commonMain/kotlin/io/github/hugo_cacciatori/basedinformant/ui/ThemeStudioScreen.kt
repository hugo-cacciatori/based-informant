package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hugo_cacciatori.basedinformant.core.theme.StudioState
import io.github.hugo_cacciatori.basedinformant.core.theme.StudioStep
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemeDesigner
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemeGenerator
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemePack
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemeStudio
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_again
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_close
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_designing
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_checking
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_discard
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_failed_key
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_failed_model
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_failed_unreadable
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_generate
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_idea
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_keep
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_name
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_preview_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_preview_button
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_reviewing
import io.github.hugo_cacciatori.basedinformant.ui.resources.studio_title
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

interface StudioActions {
    fun load()
    fun generate(idea: String)
    fun keep(name: String)
    fun discard()
    fun delete(id: String)
}

class ThemeStudioViewModel(private val studio: ThemeStudio) : ViewModel(), StudioActions {
    val state: StateFlow<StudioState> = studio.state

    override fun load() {
        viewModelScope.launch { studio.load() }
    }

    override fun generate(idea: String) {
        viewModelScope.launch { studio.generate(idea) }
    }

    override fun keep(name: String) {
        viewModelScope.launch { studio.keep(name) }
    }

    override fun discard() = studio.discard()

    override fun delete(id: String) {
        viewModelScope.launch { studio.delete(id) }
    }
}

/**
 * Settings → Themes → Generate (01-product.md section 10, owner 2026-10-30): the optional idea, the free chain's
 * progress, then a live preview of the design in its own theme, to keep under a name, try again or discard.
 */
@Composable
fun ThemeStudioDialog(state: StudioState, actions: StudioActions, onClose: () -> Unit) {
    var idea by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val step = state.step
    AlertDialog(
        onDismissRequest = { if (step !is StudioStep.Working) { actions.discard(); onClose() } },
        title = { Text(stringResource(Res.string.studio_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                when (step) {
                    is StudioStep.Working -> {
                        Text(
                            stringResource(
                                when (step.stage) {
                                    ThemeGenerator.Stage.DESIGNING -> Res.string.studio_designing
                                    ThemeGenerator.Stage.REVIEWING -> Res.string.studio_reviewing
                                    ThemeGenerator.Stage.CHECKING -> Res.string.studio_checking
                                },
                            ),
                            modifier = Modifier.testTag("studio-stage"),
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is StudioStep.Preview -> {
                        ThemePreview(step.pack)
                        OutlinedTextField(
                            value = name.ifEmpty { step.pack.name },
                            onValueChange = { name = it.take(ThemeStudio.NAME_MAX) },
                            label = { Text(stringResource(Res.string.studio_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("studio-name"),
                        )
                    }
                    else -> {
                        Text(stringResource(Res.string.studio_body))
                        OutlinedTextField(
                            value = idea,
                            onValueChange = { idea = it.take(ThemeDesigner.MAX_IDEA_CHARS) },
                            label = { Text(stringResource(Res.string.studio_idea)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("studio-idea"),
                        )
                        (step as? StudioStep.Failed)?.let { failed ->
                            Text(
                                stringResource(
                                    when (failed.reason) {
                                        StudioStep.Reason.NO_KEY -> Res.string.studio_failed_key
                                        StudioStep.Reason.NO_FREE_MODEL -> Res.string.studio_failed_model
                                        StudioStep.Reason.UNREADABLE -> Res.string.studio_failed_unreadable
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("studio-failed"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                is StudioStep.Preview -> TextButton(onClick = { actions.keep(name); name = ""; onClose() }, modifier = Modifier.testTag("studio-keep")) {
                    Text(stringResource(Res.string.studio_keep))
                }
                is StudioStep.Working -> Unit
                else -> TextButton(onClick = { actions.generate(idea) }, modifier = Modifier.testTag("studio-generate")) {
                    Text(stringResource(Res.string.studio_generate))
                }
            }
        },
        dismissButton = {
            when (step) {
                is StudioStep.Preview -> Row {
                    TextButton(onClick = { name = ""; actions.generate(idea) }, modifier = Modifier.testTag("studio-again")) { Text(stringResource(Res.string.studio_again)) }
                    TextButton(onClick = { name = ""; actions.discard() }, modifier = Modifier.testTag("studio-discard")) { Text(stringResource(Res.string.studio_discard)) }
                }
                is StudioStep.Working -> Unit
                else -> TextButton(onClick = { actions.discard(); onClose() }) { Text(stringResource(Res.string.studio_close)) }
            }
        },
    )
}

/** The design drawn in its own theme: its art, a frosted card and its glowing button. */
@Composable
private fun ThemePreview(pack: ThemePack) {
    Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).testTag("studio-preview")) {
        AppTheme(pack, animations = true) {
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(pack.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Trending")
                    Text(stringResource(Res.string.studio_preview_body), style = MaterialTheme.typography.bodyMedium)
                }
                GlowButton(stringResource(Res.string.studio_preview_button), onClick = {})
            }
        }
    }
}
