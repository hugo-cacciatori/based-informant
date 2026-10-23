package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.history_empty
import org.jetbrains.compose.resources.stringResource

/** The History tab (product.md section 5) is built in stage 6; until then it says what will be there. */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.history_empty))
    }
}
