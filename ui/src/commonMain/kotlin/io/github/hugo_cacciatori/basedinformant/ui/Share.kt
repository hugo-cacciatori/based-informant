package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.hugo_cacciatori.basedinformant.ui.resources.Res
import io.github.hugo_cacciatori.basedinformant.ui.resources.share
import io.github.hugo_cacciatori.basedinformant.ui.resources.share_email
import org.jetbrains.compose.resources.stringResource

/**
 * Share (01-product.md section 8, owner 2026-10-29): always a menu of share types; today only Email, which opens the
 * device's mail app with the digest filled in. [asText] draws a text button (the run popup), else an icon (top bar).
 */
@Composable
fun ShareMenu(onEmail: () -> Unit, asText: Boolean = false, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        if (asText) {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("share")) { Text(stringResource(Res.string.share)) }
        } else {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag("share")) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(Res.string.share))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.share_email)) },
                onClick = {
                    open = false
                    onEmail()
                },
                modifier = Modifier.testTag("share-email"),
            )
        }
    }
}
