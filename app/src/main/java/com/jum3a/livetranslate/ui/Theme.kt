package com.jum3a.livetranslate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Emerald and gold: the colours of mosque tilework and illuminated Quran pages.
val Gold = Color(0xFFD9B45A)
val GoldLight = Color(0xFFEBD49A)
val Emerald = Color(0xFF0B3D2C)
val EmeraldLight = Color(0xFF1E6B4E)
/** "Live" colour: speech being heard right now. */
val Mint = Color(0xFF5FD3A2)
val Ivory = Color(0xFFF1EBDC)

// Always dark: easy on the eyes (and on others nearby) in a mosque or lecture hall.
private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF2B2003),
    primaryContainer = Color(0xFF17473A),
    onPrimaryContainer = Color(0xFFF2E6C4),
    secondary = Color(0xFF86CBA9),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF1B3D31),
    onSecondaryContainer = Color(0xFFCFEBDC),
    tertiary = Color(0xFFF0B45E),
    onTertiary = Color(0xFF452B00),
    background = Color(0xFF08140F),
    onBackground = Ivory,
    surface = Color(0xFF08140F),
    onSurface = Ivory,
    surfaceVariant = Color(0xFF1A2A22),
    onSurfaceVariant = Color(0xFFB8B19E),
    surfaceContainerLowest = Color(0xFF050D0A),
    surfaceContainerLow = Color(0xFF0C1A14),
    surfaceContainer = Color(0xFF0F2019),
    surfaceContainerHigh = Color(0xFF14291F),
    surfaceContainerHighest = Color(0xFF1A3127),
    outline = Color(0xFF6E7C70),
    outlineVariant = Color(0xFF243A2F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Classical serif for headings; the default sans-serif stays for the translation text itself. */
private val Serif = FontFamily.Serif
private val Type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    )
}

@Composable
fun Jum3aTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}

/** Outline of an eight-pointed star (two overlapping squares) centred on [c]. */
private fun DrawScope.octagram(c: Offset, r: Float, color: Color, stroke: Float?) {
    for (start in listOf(0.0, Math.PI / 4)) {
        val p = Path()
        for (i in 0 until 4) {
            val a = start + i * Math.PI / 2 - Math.PI / 2
            val x = c.x + r * cos(a).toFloat()
            val y = c.y + r * sin(a).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, color, style = if (stroke != null) Stroke(stroke) else Fill)
    }
}

/** A faint lattice of eight-pointed stars, like a carved screen, drawn behind the content. */
fun Modifier.starPattern(color: Color = Gold, alpha: Float = 0.045f, tile: Dp = 64.dp): Modifier = drawBehind {
    val step = tile.toPx()
    val r = step * 0.24f
    val c = color.copy(alpha = alpha)
    val w = 1.dp.toPx()
    var row = 0
    var y = step / 2
    while (y < size.height + step) {
        var x = if (row % 2 == 0) step / 2 else 0f
        while (x < size.width + step) {
            octagram(Offset(x, y), r, c, w)
            x += step
        }
        y += step / 2
        row++
    }
}

/** A small solid eight-pointed star, used as an ornament. */
@Composable
fun StarGlyph(size: Dp = 10.dp, color: Color = Gold, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { octagram(center, this.size.minDimension / 2, color, null) }
}

/** Section heading: gold star, serif title, and a fading gold rule. */
@Composable
fun OrnamentHeading(title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StarGlyph(11.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, color = Gold)
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .drawBehind {
                    drawLine(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Gold.copy(alpha = 0.45f), Color.Transparent),
                        ),
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                    )
                },
        )
    }
}

/** Gold title text style for the app name. */
val AppTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(color = Ivory)
