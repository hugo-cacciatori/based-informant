package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The desktop has no web view; until the Windows app opens the HTML in the default browser (03-rendering.md section 3,
 * proposed), it shows the HTML source, so the tests can see the stored page.
 */
@Composable
actual fun EmailedHtml(html: String, modifier: Modifier) {
    Text(html, modifier.verticalScroll(rememberScrollState()).testTag("emailed-html"))
}
