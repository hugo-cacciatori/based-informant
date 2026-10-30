package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hugo_cacciatori.basedinformant.core.theme.BackgroundEngine
import io.github.hugo_cacciatori.basedinformant.core.theme.BuiltInThemes
import io.github.hugo_cacciatori.basedinformant.core.theme.Palette
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemeColor
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemeFont
import io.github.hugo_cacciatori.basedinformant.core.theme.ThemePack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The theme engine (01-product.md section 10, 04-platforms.md section 6): the pack's palette on every screen, light or
 * dark following the system, over its drawn background. [animations] is the Settings switch; the background also stays
 * still when the system asks for less motion or saves battery, and pauses while the app is not visible.
 */
@Composable
fun AppTheme(
    pack: ThemePack = BuiltInThemes.pack(BuiltInThemes.DEFAULT),
    animations: Boolean = true,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = pack.palette(dark)
    val look = Look(pack.shape.corner.dp, pack.shape.border.dp, pack.glow)
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(colorScheme = palette.colorScheme(dark), typography = typography(pack.font), shapes = look.shapes()) {
            Box(Modifier.fillMaxSize()) {
                ThemeBackground(pack, palette, moving = animations && systemMotionAllowed())
                content()
            }
        }
    }
}

/**
 * Material's roles from the pack. Text sits on [Palette.surface]: the bars and the screen body use it as is, over the
 * background; cards and dialogs use it opaque. The pack check verifies both against [Palette.onSurface].
 */
internal fun Palette.colorScheme(dark: Boolean): ColorScheme {
    val surface = color(surface)
    val solid = surface.copy(alpha = 1f)
    val onSurface = color(onSurface)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = color(primary),
        onPrimary = color(onPrimary),
        background = color(background),
        onBackground = color(onBackground),
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = solid,
        onSurfaceVariant = onSurface,
        surfaceContainerLowest = solid,
        surfaceContainerLow = solid,
        surfaceContainer = solid,
        surfaceContainerHigh = solid,
        surfaceContainerHighest = solid,
        outline = color(outline),
        outlineVariant = color(outline),
        // Tonal buttons and chips take a tint of the theme's primary over its surface, never the base scheme's grey.
        secondaryContainer = color(primary).copy(alpha = 0.22f).compositeOver(solid),
        onSecondaryContainer = onSurface,
    )
}

private fun typography(font: ThemeFont): Typography {
    val family = when (font) {
        ThemeFont.DEFAULT -> FontFamily.Default
        ThemeFont.SANS -> FontFamily.SansSerif
        ThemeFont.SERIF -> FontFamily.Serif
        ThemeFont.MONO -> FontFamily.Monospace
    }
    if (family == FontFamily.Default) return Typography()
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography().run {
        copy(
            displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
            headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
            titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
            bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
            labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
        )
    }
}

/** A color the pack check accepted. */
internal fun color(text: String): Color {
    val c = requireNotNull(ThemeColor.parse(text)) { "not a color: $text" }
    return Color(c.red, c.green, c.blue, c.alpha)
}

/** About 30 frames a second: enough for slow motion, kind to the battery. */
private const val FRAME_MS = 33L

/** The drawn background engine. Still, it draws the first frame (time 0). */
@Composable
internal fun ThemeBackground(pack: ThemePack, palette: Palette, moving: Boolean) {
    val sky = remember(palette) { palette.sky.map(::color) }
    val starColor = remember(palette) { color(palette.onBackground) }
    val bg = pack.background
    val stars = remember(bg.density) { Stars(bg.density) }
    val time = remember { mutableFloatStateOf(0f) }
    val animate = moving && bg.speed > 0f && bg.engine != BackgroundEngine.STILL
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate) {
        if (!animate) {
            time.floatValue = 0f
            return@LaunchedEffect
        }
        // Runs only while the app is visible; the clock picks up where it paused.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(FRAME_MS)
                time.floatValue += FRAME_MS / 1000f
            }
        }
    }
    // The time is read while drawing, so a frame redraws the canvas without recomposing the screens.
    Canvas(Modifier.fillMaxSize().testTag("theme-background")) {
        val t = time.floatValue * bg.speed
        when (bg.engine) {
            BackgroundEngine.STILL -> drawSky(sky)
            BackgroundEngine.GRADIENT -> drawDrift(sky, t)
            BackgroundEngine.STARFIELD -> {
                drawSky(sky)
                stars.draw(this, starColor, t)
            }
            BackgroundEngine.ART -> bg.art?.let { drawArt(it, sky, t) } ?: drawSky(sky)
        }
    }
}

private fun DrawScope.drawSky(sky: List<Color>) {
    if (sky.size == 1) drawRect(sky[0]) else drawRect(Brush.verticalGradient(sky))
}

/** Soft blobs of the sky colors on slow, different circles over the first one. */
private fun DrawScope.drawDrift(sky: List<Color>, t: Float) {
    drawRect(sky[0])
    val radius = size.maxDimension * 0.7f
    sky.drop(1).forEachIndexed { i, c ->
        val phase = i * 2.1f
        val center = Offset(
            size.width * (0.5f + 0.35f * cos(t * (0.6f + 0.15f * i) + phase)),
            size.height * (0.5f + 0.35f * sin(t * (0.45f + 0.1f * i) + phase)),
        )
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, radius), radius, center)
    }
}

/** Fixed star positions (the same at every start), each twinkling at its own pace. */
private class Stars(count: Int) {
    private val random = Random(8)
    private val x = FloatArray(count) { random.nextFloat() }
    private val y = FloatArray(count) { random.nextFloat() }
    private val r = FloatArray(count) { 0.6f + random.nextFloat() * 1.4f }
    private val pace = FloatArray(count) { 0.5f + random.nextFloat() * 1.5f }
    private val phase = FloatArray(count) { random.nextFloat() * 2f * PI.toFloat() }

    fun draw(scope: DrawScope, color: Color, t: Float) = with(scope) {
        for (i in x.indices) {
            val glow = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * pace[i] * 4f + phase[i]))
            drawCircle(color.copy(alpha = glow), r[i] * density, Offset(x[i] * size.width, y[i] * size.height))
        }
    }
}
