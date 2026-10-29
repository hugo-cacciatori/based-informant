package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/** The desktop reads Windows' "Animation effects" with the Windows app (04-platforms.md section 6). */
@Composable
actual fun systemMotionAllowed(): Boolean = true
