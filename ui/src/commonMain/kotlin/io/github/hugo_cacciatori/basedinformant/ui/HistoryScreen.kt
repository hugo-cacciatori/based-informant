package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.core.render.CardView
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryCard
import io.github.hugo_cacciatori.basedinformant.core.run.HistoryState
import io.github.hugo_cacciatori.basedinformant.core.run.OpenDigest
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.back
import io.github.hugo_cacciatori.basedinformant.ui.resources.cancel
import io.github.hugo_cacciatori.basedinformant.ui.resources.delete
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_empty
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_missing
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_one_topic
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_topics
import io.github.hugo_cacciatori.basedinformant.ui.resources.viewer_delete
import io.github.hugo_cacciatori.basedinformant.ui.resources.viewer_delete_body
import io.github.hugo_cacciatori.basedinformant.ui.resources.viewer_delete_title
import io.github.hugo_cacciatori.basedinformant.ui.resources.viewer_less
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** What the History tab can ask for; [HistoryViewModel] does it. */
interface HistoryActions {
    fun load()
    fun open(id: Long)
    fun close()
    fun delete(id: Long)
    fun dismissMissing()
}

/**
 * The History tab (01-product.md section 5): the list, newest first, and the viewer drawn natively from the same
 * view as the email (03-rendering.md section 3). Material colors until the theme engine (stage 8). Share sits in the
 * viewer's top bar (section 8).
 */
@Composable
fun HistoryScreen(
    state: HistoryState,
    actions: HistoryActions,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    onShareEmail: (Long) -> Unit = {},
) {
    LaunchedEffect(Unit) { actions.load() }
    LaunchedEffect(state.missing) {
        if (!state.missing) return@LaunchedEffect
        snackbar.showSnackbar(getString(Res.string.history_missing))
        actions.dismissMissing()
    }
    val open = state.open
    // Back from the viewer goes to the list, as the notification's back stack asks (01-product.md section 7).
    PlatformBackHandler(enabled = open != null) { actions.close() }
    if (open != null) {
        DigestViewer(open, actions, onShareEmail, modifier)
        return
    }
    if (state.loaded && state.cards.isEmpty()) {
        Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.history_empty))
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("history"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.cards, key = { it.id }) { card -> HistoryCardRow(card) { actions.open(card.id) } }
    }
}

@Composable
private fun HistoryCardRow(card: HistoryCard, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("history-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMillis(card.at), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    if (card.topicCount == 1) stringResource(Res.string.history_one_topic) else stringResource(Res.string.history_topics, card.topicCount),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(card.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DigestViewer(open: OpenDigest, actions: HistoryActions, onShareEmail: (Long) -> Unit, modifier: Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        // Top bar: date and subject.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::close) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(formatMillis(open.stored.generatedAt), style = MaterialTheme.typography.labelMedium)
                Text(open.stored.subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("viewer-subject"))
            }
            ShareMenu(onEmail = { onShareEmail(open.id) })
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.viewer_delete))
            }
        }
        NativeDigest(open, Modifier.weight(1f).fillMaxWidth())
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.viewer_delete_title)) },
            text = { Text(stringResource(Res.string.viewer_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.delete(open.id) }, modifier = Modifier.testTag("delete-confirm")) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** The digest in the app's look: the same texts, sources, order and labels as the shared mail. */
@Composable
private fun NativeDigest(open: OpenDigest, modifier: Modifier) {
    val view = open.view
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("viewer"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(view.title, style = MaterialTheme.typography.headlineSmall)
        Text(view.headerLine, style = MaterialTheme.typography.labelMedium)
        view.opening?.let { Text(it) }
        if (view.briefing.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().testTag("briefing")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(DigestViews.BRIEFING, style = MaterialTheme.typography.titleSmall)
                    view.briefing.forEach { Text("• $it") }
                }
            }
        }
        view.emptyMessage?.let { Text(it) }
        view.cards.forEach { DigestCard(it) }
        view.missingLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        view.closing?.let { Text(it) }
        HorizontalDivider()
        view.footerLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DigestCard(card: CardView) {
    val uri = LocalUriHandler.current
    var more by rememberSaveable(card.title) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text(card.summary)
            card.sinceLast?.let { Text(DigestViews.SINCE_LAST + ": " + it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("since-last")) }
            card.framing?.let { Text(DigestViews.FRAMING + ": " + it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("framing")) }
            card.detail?.let { detail ->
                TextButton(onClick = { more = !more }) {
                    Text(if (more) stringResource(Res.string.viewer_less) else DigestViews.MORE)
                }
                if (more) Text(detail)
            }
            // The trust layer (01-product.md section 4.6): why it was picked, how many outlets, whether it was fact-checked.
            Text(listOfNotNull(card.matched, card.outlets, card.checkLine).joinToString(" · "), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("trust"))
            card.sources.forEach { source ->
                // Links: https only (the view keeps no other), opened in the system browser; without one, plain text.
                val url = source.url
                AssistChip(
                    onClick = { url?.let(uri::openUri) },
                    enabled = url != null,
                    label = { Text(source.label) },
                )
            }
        }
    }
}

