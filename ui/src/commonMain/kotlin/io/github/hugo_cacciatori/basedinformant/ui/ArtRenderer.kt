package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.hugo_cacciatori.basedinformant.core.theme.Art
import io.github.hugo_cacciatori.basedinformant.core.theme.ArtKind
import io.github.hugo_cacciatori.basedinformant.core.theme.ArtLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The procedural 2D art engine (01-product.md section 10, owner 2026-10-30): each layer of the [Art] drawn from its
 * numbers over the first sky color. Time [t] is in seconds; at 0 it is the still frame. Nothing here runs model text:
 * the models only chose the kinds and the numbers.
 */
internal fun DrawScope.drawArt(art: Art, sky: List<Color>, t: Float) {
    drawRect(sky[0])
    art.layers.forEachIndexed { i, layer ->
        val colors = layer.colors.map { sky.getOrElse(it) { sky.last() }.copy(alpha = layer.opacity.coerceIn(0f, 1f)) }.ifEmpty { listOf(sky.last()) }
        val time = t * (0.2f + layer.speed * 1.6f)
        val random = Random(art.seed * 31 + i)
        when (layer.kind) {
            ArtKind.WAVES -> waves(layer, colors, time)
            ArtKind.BLOBS -> blobs(layer, colors, time, random)
            ArtKind.PARTICLES -> particles(layer, colors, time, random)
            ArtKind.RINGS -> rings(layer, colors, time)
            ArtKind.STRIPES -> stripes(layer, colors, time)
            ArtKind.GRID -> grid(layer, colors, time)
            ArtKind.AURORA -> aurora(layer, colors, time, random)
        }
    }
}

private fun count(p: Float, min: Int, max: Int) = (min + p * (max - min)).toInt().coerceIn(min, max)

private fun DrawScope.waves(l: ArtLayer, colors: List<Color>, t: Float) {
    val n = count(l.p(0), 1, 6)
    val height = size.height * (0.03f + 0.12f * l.p(1))
    val cycles = 0.5f + 3f * l.p(2)
    val level = size.height * (0.45f + 0.5f * l.p(3, 0.7f))
    for (w in 0 until n) {
        val path = Path()
        val base = level + w * height * 0.9f
        path.moveTo(0f, size.height)
        var x = 0f
        while (x <= size.width + 8f) {
            val y = base + height * sin((x / size.width) * cycles * 2f * PI.toFloat() + t + w * 1.3f)
            path.lineTo(x, y)
            x += 8f
        }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, colors[w % colors.size])
    }
}

private fun DrawScope.blobs(l: ArtLayer, colors: List<Color>, t: Float, r: Random) {
    val n = count(l.p(0), 2, 8)
    val radius = size.maxDimension * (0.15f + 0.45f * l.p(1))
    val spread = 0.15f + 0.35f * l.p(2)
    repeat(n) { i ->
        val phase = r.nextFloat() * 6.28f
        val ax = 0.5f + 0.5f * r.nextFloat()
        val ay = 0.4f + 0.5f * r.nextFloat()
        val c = colors[i % colors.size]
        val center = Offset(
            size.width * (0.5f + spread * cos(t * ax + phase) + (r.nextFloat() - 0.5f) * 0.4f),
            size.height * (0.5f + spread * sin(t * ay + phase) + (r.nextFloat() - 0.5f) * 0.6f),
        )
        drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center, radius), radius, center)
    }
}

private fun DrawScope.particles(l: ArtLayer, colors: List<Color>, t: Float, r: Random) {
    val n = count(l.p(0), 10, 160)
    val dot = density * (0.8f + 3.5f * l.p(1))
    val angle = l.p(2) * 2f * PI.toFloat()
    val dx = cos(angle) * 0.02f
    val dy = sin(angle) * 0.02f
    repeat(n) { i ->
        val x = ((r.nextFloat() + dx * t * (0.5f + r.nextFloat())) % 1f + 1f) % 1f
        val y = ((r.nextFloat() + dy * t * (0.5f + r.nextFloat())) % 1f + 1f) % 1f
        val twinkle = 0.5f + 0.5f * sin(t * (1f + r.nextFloat() * 2f) + i)
        val c = colors[i % colors.size]
        drawCircle(c.copy(alpha = c.alpha * (0.4f + 0.6f * twinkle)), dot, Offset(x * size.width, y * size.height))
    }
}

private fun DrawScope.rings(l: ArtLayer, colors: List<Color>, t: Float) {
    val n = count(l.p(0), 2, 10)
    val stroke = density * (1f + 10f * l.p(1))
    val center = Offset(size.width * l.p(2), size.height * l.p(3, 0.35f))
    val step = size.maxDimension / n * 0.9f
    for (i in 0 until n) {
        val breathe = 1f + 0.06f * sin(t + i * 0.7f)
        drawCircle(colors[i % colors.size], (i + 1) * step * breathe, center, style = Stroke(stroke))
    }
}

private fun DrawScope.stripes(l: ArtLayer, colors: List<Color>, t: Float) {
    val n = count(l.p(0), 3, 20)
    val angle = -60f + 120f * l.p(1)
    val span = size.maxDimension * 2f
    val gap = span / n
    val width = gap * (0.15f + 0.7f * l.p(2, 0.4f))
    val shift = (t * gap * 0.15f) % gap
    rotate(angle, Offset(size.width / 2f, size.height / 2f)) {
        for (i in 0 until n) {
            val x = -span / 4f + i * gap + shift
            drawRect(colors[i % colors.size], Offset(x, -span / 4f), androidx.compose.ui.geometry.Size(width, span))
        }
    }
}

private fun DrawScope.grid(l: ArtLayer, colors: List<Color>, t: Float) {
    val spacing = size.minDimension * (0.05f + 0.2f * l.p(0))
    val stroke = density * (0.5f + 2.5f * l.p(1, 0.3f))
    val perspective = l.p(2)
    val horizon = size.height * (0.35f + 0.2f * (1f - perspective))
    val c = colors.first()
    val offset = (t * spacing * 0.3f) % spacing
    var y = horizon + offset * perspective
    var gapY = spacing * (1f - perspective * 0.7f)
    while (y < size.height) {
        drawLine(c, Offset(0f, y), Offset(size.width, y), stroke)
        y += gapY
        gapY *= 1f + perspective * 0.25f
    }
    val vanish = Offset(size.width / 2f, horizon)
    var x = -size.width
    while (x < size.width * 2f) {
        val top = if (perspective > 0.05f) vanish.x + (x - vanish.x) * (1f - perspective) else x
        drawLine(c, Offset(top, horizon), Offset(x, size.height), stroke)
        x += spacing
    }
}

private fun DrawScope.aurora(l: ArtLayer, colors: List<Color>, t: Float, r: Random) {
    val n = count(l.p(0), 1, 4)
    val height = size.height * (0.25f + 0.5f * l.p(1))
    val wave = size.width * (0.02f + 0.15f * l.p(2))
    for (i in 0 until n) {
        val c = colors[i % colors.size]
        val top = size.height * (0.05f + 0.3f * r.nextFloat())
        val path = Path()
        val step = 12f
        var x = 0f
        path.moveTo(0f, top)
        while (x <= size.width + step) {
            path.lineTo(x, top + wave * sin(x / size.width * 4f + t * (0.6f + i * 0.2f) + i))
            x += step
        }
        x = size.width + step
        while (x >= -step) {
            path.lineTo(x, top + height + wave * sin(x / size.width * 3f + t * 0.5f + i * 2f))
            x -= step
        }
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = 0f), c, c.copy(alpha = 0f)), top, top + height))
    }
}
