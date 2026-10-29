package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.runtime.Composable

/** The system Back (Android's gesture or button). The desktop has none: its screens use the top bar's arrow. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * False when the system asks for still screens: Android's animations turned off (animator scale 0) or battery saver
 * (01-product.md section 10). Read again each time the app comes back.
 */
@Composable
expect fun systemMotionAllowed(): Boolean
