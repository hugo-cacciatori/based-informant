package io.github.hugo_cacciatori.basedinformant.core.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A theme pack (01-product.md section 10, 04-platforms.md section 6): the `theme.json` of `themes/<id>/`. Colors are
 * `#RRGGBB` or `#AARRGGBB` text, checked by [PackCheck]. Every pack has a light and a dark variant; the phone's setting
 * picks one.
 */
@Serializable
data class ThemePack(
    val id: String,
    val name: String,
    val font: ThemeFont = ThemeFont.DEFAULT,
    val light: Palette,
    val dark: Palette,
    val background: Background,
    /** Corners and edges of cards, buttons and chips (theme format v2, owner 2026-10-30). */
    val shape: ShapeStyle = ShapeStyle(),
    /** 0 none to 1 strong: the soft light around the main button (modern glass, owner 2026-10-30). */
    val glow: Float = 0f,
    /** How lively the interface moves (theme format v2): 0.5 slow and calm to 2 quick and snappy. */
    val motion: Float = 1f,
) {
    fun palette(dark: Boolean): Palette = if (dark) this.dark else light
}

/** [corner] radius in dp of cards and buttons; [border] the width in dp of the light edge of translucent cards (0: none). */
@Serializable
data class ShapeStyle(val corner: Int = 12, val border: Float = 0f)

/** System font families only: a pack ships no font files. */
@Serializable
enum class ThemeFont {
    @SerialName("default") DEFAULT,
    @SerialName("sans") SANS,
    @SerialName("serif") SERIF,
    @SerialName("mono") MONO,
}

/**
 * One variant's colors. Text sits on [surface] (cards, bars) or on [background]; [sky] are the colors the background
 * engine draws with, under the surfaces. A translucent [surface] is checked against each [sky] color it may cover.
 */
@Serializable
data class Palette(
    val primary: String,
    val onPrimary: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val outline: String,
    val sky: List<String>,
)

/**
 * The drawn background (04-platforms.md section 6). [speed] 0 is still; the still fallback (Animations off, battery
 * saver, reduce-animations) is always the engine's first frame. [density] is how many stars or particles.
 */
@Serializable
data class Background(
    val engine: BackgroundEngine,
    val speed: Float = 0f,
    val density: Int = 0,
    /** The layers of the [BackgroundEngine.ART] engine; ignored by the others. */
    val art: Art? = null,
)

/**
 * Procedural 2D art (01-product.md section 10, owner 2026-10-30): layers drawn in order over the first sky color, each
 * described by numbers only, so a free model can design it and the app draws it. [seed] places what is random.
 */
@Serializable
data class Art(val seed: Int = 0, val layers: List<ArtLayer> = emptyList())

/**
 * One layer: its [kind], the sky colors it uses (indices into the palette's sky), its [params] (each 0 to 1, their
 * meaning set by the kind, missing ones taking a middle value), how opaque and how fast it is.
 */
@Serializable
data class ArtLayer(
    val kind: ArtKind,
    val colors: List<Int> = listOf(1),
    val params: List<Float> = emptyList(),
    val opacity: Float = 0.6f,
    val speed: Float = 0.3f,
) {
    /** Param [i], or [default] when missing; always 0 to 1. */
    fun p(i: Int, default: Float = 0.5f): Float = params.getOrElse(i) { default }.coerceIn(0f, 1f)
}

@Serializable
enum class ArtKind {
    /** Rolling sine waves from the bottom: count, height, frequency, level. */
    @SerialName("waves") WAVES,

    /** Soft glowing blobs drifting on loops: count, size, spread. */
    @SerialName("blobs") BLOBS,

    /** Small dots drifting and wrapping: count, size, direction. */
    @SerialName("particles") PARTICLES,

    /** Concentric rings breathing: count, thickness, center x, center y. */
    @SerialName("rings") RINGS,

    /** Slanted bands sliding: count, angle, width. */
    @SerialName("stripes") STRIPES,

    /** A line grid, flat or in perspective: spacing, line width, perspective. */
    @SerialName("grid") GRID,

    /** Vertical light ribbons waving: count, height, waviness. */
    @SerialName("aurora") AURORA,
}

@Serializable
enum class BackgroundEngine {
    /** A flat fill of the first sky color, or a vertical gradient through all of them. */
    @SerialName("still") STILL,

    /** Soft blobs of the sky colors drifting slowly. */
    @SerialName("gradient") GRADIENT,

    /** Stars twinkling over a gradient of the sky colors. */
    @SerialName("starfield") STARFIELD,

    /** Procedural 2D art: the [Background.art] layers (owner, 2026-10-30). */
    @SerialName("art") ART,
}

object ThemePacks {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a `theme.json`; a missing field or a wrong type throws [ThemePackException]. */
    fun read(text: String): ThemePack =
        try {
            json.decodeFromString(ThemePack.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw ThemePackException("unreadable theme.json: ${e.message}", e)
        }
}

class ThemePackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The built-in themes, in the order Settings lists them (owner, 2026-10-29 and 2026-10-30), read from `core/themes/<id>/`. */
object BuiltInThemes {
    /** Modern glass is the default look (owner, 2026-10-30). */
    const val DEFAULT = "glass"
    val IDS = listOf(DEFAULT, "plain", "night", "drift")

    /** A theme the settings may hold: a built-in one, or a generated one ([ThemeStudio.ID_PREFIX], owner 2026-10-30). */
    fun isValidId(id: String) = id in IDS || id.startsWith(ThemeStudio.ID_PREFIX)

    /** The pack of [id]; an unknown id gives the default. */
    fun pack(id: String): ThemePack =
        ThemePacks.read(ThemeFiles.all.getValue("${if (id in IDS) id else DEFAULT}/theme.json"))
}
