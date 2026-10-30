package io.github.hugo_cacciatori.basedinformant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The theme's look beyond colors (theme format v2, owner 2026-10-30): corner radius, the light edge of translucent
 * cards and the glow of the main button. The default look is modern glass (01-product.md section 10).
 */
data class Look(val corner: Dp = 12.dp, val border: Dp = 0.dp, val glow: Float = 0f) {
    fun shapes() = Shapes(
        extraSmall = RoundedCornerShape(corner / 3),
        small = RoundedCornerShape(corner / 2),
        medium = RoundedCornerShape(corner * 2 / 3),
        large = RoundedCornerShape(corner),
        extraLarge = RoundedCornerShape(corner * 4 / 3),
    )
}

val LocalLook = staticCompositionLocalOf { Look() }

/** The spacing scale every screen uses. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * A frosted card: the theme's translucent surface over the drawn background, a fine light edge that catches the eye
 * from the top, and the theme's corner. Text inside always sits on the surface (01-product.md section 10).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val edge = if (look.border > 0.dp) {
        BorderStroke(look.border, Brush.verticalGradient(listOf(scheme.onSurface.copy(alpha = 0.28f), scheme.onSurface.copy(alpha = 0.06f))))
    } else {
        null
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(look.corner),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        border = edge,
    ) {
        Column(Modifier.padding(padding), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.s), content = content)
    }
}

/** The main action: full width, tall, with the theme's glow around it when enabled. */
@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalLook.current
    val primary = MaterialTheme.colorScheme.primary
    val glow = if (enabled) look.glow else 0f
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(look.corner),
        colors = ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(vertical = Space.l),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .shadow(elevation = (18 * glow).dp, shape = RoundedCornerShape(look.corner), ambientColor = primary, spotColor = primary),
    ) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
}

/** A small uppercase section label above a group of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
}
