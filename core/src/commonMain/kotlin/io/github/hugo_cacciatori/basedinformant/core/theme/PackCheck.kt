package io.github.hugo_cacciatori.basedinformant.core.theme

import kotlin.math.pow

/**
 * The pack test (04-platforms.md section 6): required fields, both variants, the size budget and text contrast at
 * WCAG AA. An empty list means the pack may ship.
 */
object PackCheck {
    /** About 3 MB per pack (owner, 2026-10-29). */
    const val MAX_PACK_BYTES = 3L * 1024 * 1024

    /** WCAG AA for normal text. */
    const val MIN_CONTRAST = 4.5

    const val MAX_SPEED = 1f
    const val MAX_DENSITY = 400

    /** [folder] is the pack's folder name; [fileSizes] the size in bytes of every file in it. */
    fun problems(pack: ThemePack, folder: String, fileSizes: Map<String, Long>): List<String> = buildList {
        if (pack.id != folder) add("id '${pack.id}' does not match its folder '$folder'")
        if (pack.name.isBlank()) add("name is blank")
        val total = fileSizes.values.sum()
        if (total > MAX_PACK_BYTES) add("pack is $total bytes, over $MAX_PACK_BYTES")
        if (pack.background.speed !in 0f..MAX_SPEED) add("background speed ${pack.background.speed} is outside 0..$MAX_SPEED")
        if (pack.background.density !in 0..MAX_DENSITY) add("background density ${pack.background.density} is outside 0..$MAX_DENSITY")
        if (pack.background.engine == BackgroundEngine.STARFIELD && pack.background.density == 0) add("a starfield needs a density")
        addAll(paletteProblems("light", pack.light))
        addAll(paletteProblems("dark", pack.dark))
    }

    private fun paletteProblems(variant: String, palette: Palette): List<String> = buildList {
        val named = listOf(
            "primary" to palette.primary, "onPrimary" to palette.onPrimary,
            "background" to palette.background, "onBackground" to palette.onBackground,
            "surface" to palette.surface, "onSurface" to palette.onSurface, "outline" to palette.outline,
        ) + palette.sky.mapIndexed { i, c -> "sky[$i]" to c }
        val colors = named.associate { (field, text) -> field to ThemeColor.parse(text) }
        colors.filterValues { it == null }.keys.forEach { add("$variant.$it is not a #RRGGBB or #AARRGGBB color") }
        if (palette.sky.isEmpty()) add("$variant.sky is empty")
        if (colors.values.any { it == null } || palette.sky.isEmpty()) return@buildList

        val c = colors.mapValues { it.value!! }
        val sky = palette.sky.indices.map { c.getValue("sky[$it]") }
        // Opaque layers only: text over a see-through layer has no fixed contrast. The surface may be translucent.
        for (field in listOf("primary", "onPrimary", "background", "onBackground", "onSurface")) {
            if (!c.getValue(field).opaque) add("$variant.$field must be opaque")
        }
        if (isNotEmpty()) return@buildList

        fun need(text: String, on: String, behind: ThemeColor) {
            val ratio = ThemeColor.contrast(c.getValue(text), behind)
            if (ratio < MIN_CONTRAST) add("$variant: $text on $on has contrast ${ratio.format()}, under $MIN_CONTRAST")
        }
        need("onPrimary", "primary", c.getValue("primary"))
        need("onBackground", "background", c.getValue("background"))
        // A translucent surface shows whatever sky color is behind it; each one must stay readable. Cards stacked on
        // it draw the surface color without its transparency.
        val surface = c.getValue("surface")
        need("onSurface", "surface", surface.copy(alpha = 255))
        if (!surface.opaque) {
            sky.forEachIndexed { i, s -> need("onSurface", "surface over sky[$i]", surface.over(s)) }
        }
    }

    private fun Double.format(): String = ((this * 100).toInt() / 100.0).toString()
}

/** An sRGB color with alpha, components 0..255. */
data class ThemeColor(val alpha: Int, val red: Int, val green: Int, val blue: Int) {
    val opaque: Boolean get() = alpha == 255

    /** WCAG relative luminance. */
    val luminance: Double
        get() {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
        }

    /** This color drawn over an opaque [below]. */
    fun over(below: ThemeColor): ThemeColor {
        val a = alpha / 255.0
        fun mix(top: Int, bottom: Int) = (top * a + bottom * (1 - a) + 0.5).toInt()
        return ThemeColor(255, mix(red, below.red), mix(green, below.green), mix(blue, below.blue))
    }

    companion object {
        private val HEX = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

        /** `#RRGGBB` or `#AARRGGBB`; anything else is null. */
        fun parse(text: String): ThemeColor? {
            if (!HEX.matches(text)) return null
            val v = text.substring(1).toLong(16)
            val hasAlpha = text.length == 9
            return ThemeColor(
                alpha = if (hasAlpha) (v shr 24 and 0xFF).toInt() else 255,
                red = (v shr 16 and 0xFF).toInt(),
                green = (v shr 8 and 0xFF).toInt(),
                blue = (v and 0xFF).toInt(),
            )
        }

        /** WCAG contrast ratio, 1 to 21; both colors opaque. */
        fun contrast(a: ThemeColor, b: ThemeColor): Double {
            val (hi, lo) = listOf(a.luminance, b.luminance).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }
    }
}
