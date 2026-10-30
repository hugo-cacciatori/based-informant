package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.github.hugo_cacciatori.basedinformant.core.render.CardView
import io.github.hugo_cacciatori.basedinformant.core.render.DigestView
import io.github.hugo_cacciatori.basedinformant.core.render.DigestViews
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.viewer_less
import org.jetbrains.compose.resources.stringResource

/**
 * The digest in the app's look (03-rendering.md section 3): the same texts, sources, order and labels as the shared
 * mail, as glass cards over the theme's background. Used by Today and the History viewer; the caller scrolls.
 */
@Composable
fun DigestBody(view: DigestView, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(modifier.testTag("viewer"), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SectionLabel(view.headerLine, Modifier.weight(1f))
                trailing()
            }
            view.opening?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
        }
        if (view.briefing.isNotEmpty()) Briefing(view.briefing)
        view.emptyMessage?.let { GlassCard(Modifier.fillMaxWidth()) { Text(it) } }
        view.cards.forEach { DigestCard(it) }
        view.missingLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        view.closing?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        HorizontalDivider()
        view.footerLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The briefing (01-product.md section 4.6): a strong card, the three lines numbered. */
@Composable
private fun Briefing(lines: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("briefing"),
        shape = RoundedCornerShape(LocalLook.current.corner),
        color = scheme.primary,
        contentColor = scheme.onPrimary,
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SectionLabel(DigestViews.BRIEFING, color = scheme.onPrimary)
            lines.forEachIndexed { i, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** One card: title, summary, what's new and framing as tinted callouts, More, the trust line and the sources. */
@Composable
private fun DigestCard(card: CardView) {
    val uri = LocalUriHandler.current
    var more by rememberSaveable(card.title) { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth().animateContentSize()) {
        SectionLabel(card.matched)
        Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(card.summary, style = MaterialTheme.typography.bodyLarge)
        card.sinceLast?.let { Callout(DigestViews.SINCE_LAST, it, "since-last") }
        card.framing?.let { Callout(DigestViews.FRAMING, it, "framing") }
        card.detail?.let { detail ->
            AnimatedVisibility(more) { Text(detail, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { more = !more }, modifier = Modifier.testTag("more")) {
                Text(if (more) stringResource(Res.string.viewer_less) else DigestViews.MORE)
            }
        }
        // The trust layer (01-product.md section 4.6): how many outlets, and whether it was fact-checked.
        Text(
            listOfNotNull(card.outlets, card.checkLine).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.testTag("trust"),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            card.sources.forEach { source ->
                // Links: https only (the view keeps no other), opened in the system browser; without one, plain text.
                val url = source.url
                AssistChip(onClick = { url?.let(uri::openUri) }, enabled = url != null, label = { Text(source.label) })
            }
        }
    }
}

@Composable
private fun Callout(label: String, text: String, tag: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(LocalLook.current.corner / 2),
        color = scheme.primary.copy(alpha = 0.14f),
        contentColor = scheme.onSurface,
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
