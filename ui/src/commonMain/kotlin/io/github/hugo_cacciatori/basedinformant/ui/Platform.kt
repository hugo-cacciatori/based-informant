package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.runtime.Composable

/** The system Back (Android's gesture or button). The desktop has none: its screens use the top bar's arrow. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
