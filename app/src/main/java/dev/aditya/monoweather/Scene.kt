package dev.aditya.monoweather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Colour grade per condition: near-monochrome, with a faint tint that carries the mood.
 * [top]/[bottom] are the sky gradient, [light] tints the sun/moon/particles, [accent] the UI.
 */
data class Palette(val top: Color, val bottom: Color, val light: Color, val accent: Color)

val Ink = Color(0xFFF4F1EC)

enum class Theme(val label: String, val blurb: String) {
    MINIMALIST("Minimalist", "Soft graded skies"),
    MIDNIGHT("Midnight", "True black for AMOLED"),
}

/**
 * Midnight: the screen stays pure black; colour lives only in a glow near the top and in thin details.
 * Here [Palette.top] is the glow colour.
 */
fun midnightPaletteOf(kind: Kind, isDay: Boolean): Palette {
    val (acc, glow) = when (kind) {
        Kind.CLEAR -> if (isDay) Color(0xFFFFD79A) to Color(0xFFFFBE6E) else Color(0xFFC9D7FF) to Color(0xFF788CFF)
        Kind.PARTLY -> Color(0xFFF2DDB8) to Color(0xFFE6BE8C)
        Kind.CLOUDY -> Color(0xFFD9DEE6) to Color(0xFF96A0AF)
        Kind.FOG -> Color(0xFFE8E2D6) to Color(0xFFC8BEAA)
        Kind.RAIN -> Color(0xFF9EC3FF) to Color(0xFF5082DC)
        Kind.SNOW -> Color(0xFFD8F1FF) to Color(0xFF96D2FF)
        Kind.STORM -> Color(0xFFC7B8FF) to Color(0xFF825AFF)
    }
    if (isDay || kind == Kind.CLEAR) return Palette(glow, Color.Black, acc, acc)
    val a = lerp(acc, Color(0xFFC9D7FF), 0.35f)
    return Palette(lerp(glow, Color(0xFF788CFF), 0.5f), Color.Black, a, a)
}

fun paletteFor(theme: Theme, kind: Kind, isDay: Boolean) =
    if (theme == Theme.MIDNIGHT) midnightPaletteOf(kind, isDay) else paletteOf(kind, isDay)

fun paletteOf(kind: Kind, isDay: Boolean): Palette {
    val day = when (kind) {
        Kind.CLEAR -> Palette(Color(0xFF2B2318), Color(0xFF0A0806), Color(0xFFFFE6BC), Color(0xFFF1D9AA))
        Kind.PARTLY -> Palette(Color(0xFF24211D), Color(0xFF0A0909), Color(0xFFF5E4C6), Color(0xFFE8DCC6))
        Kind.CLOUDY -> Palette(Color(0xFF1E2024), Color(0xFF09090A), Color(0xFFE2E5EA), Color(0xFFD3D8DF))
        Kind.FOG -> Palette(Color(0xFF22211F), Color(0xFF0C0C0B), Color(0xFFEDE8DF), Color(0xFFE4DED4))
        Kind.RAIN -> Palette(Color(0xFF141D26), Color(0xFF05080B), Color(0xFFC3D6E8), Color(0xFFAFC6DC))
        Kind.SNOW -> Palette(Color(0xFF1B232B), Color(0xFF090C10), Color(0xFFEAF5FC), Color(0xFFDCEEF8))
        Kind.STORM -> Palette(Color(0xFF17152B), Color(0xFF06050C), Color(0xFFD9D3FF), Color(0xFFC6BEF2))
    }
    if (isDay) return day
    if (kind == Kind.CLEAR) return Palette(Color(0xFF0F1628), Color(0xFF04060B), Color(0xFFD6E1F7), Color(0xFFBFD0F3))
    // night: pull everything toward midnight blue
    val night = Color(0xFF0C1120)
    return day.copy(top = lerp(day.top, night, 0.55f), bottom = lerp(day.bottom, Color(0xFF030408), 0.5f), accent = lerp(day.accent, Color(0xFFBFD0F3), 0.35f))
}

/** Stable pseudo-random in [0,1) per particle, so particles don't need stored state. */
private fun rnd(i: Int, salt: Int): Float {
    val x = sin(i * 12.9898f + salt * 78.233f) * 43758.547f
    return x - floor(x)
}

/** Full-screen animated backdrop for the current conditions. */
@Composable
fun Scene(kind: Kind, isDay: Boolean, theme: Theme, modifier: Modifier = Modifier) {
    val mid = theme == Theme.MIDNIGHT
    val pal = paletteFor(theme, kind, isDay)
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
    Canvas(modifier.fillMaxSize()) {
        if (mid) {
            drawRect(Color.Black)
            val c = Offset(size.width * 0.75f, size.height * 0.02f)
            drawRect(Brush.radialGradient(0f to pal.top.copy(alpha = 0.32f), 0.5f to pal.top.copy(alpha = 0.07f), 1f to Color.Transparent, center = c, radius = size.height * 0.55f))
        } else drawRect(Brush.verticalGradient(listOf(pal.top, pal.bottom)))
        reach = if (mid) 0.75f else 10f
        band = if (mid) 0.2f else 0.32f
        cloudK = if (mid) 0.45f else 1f
        val l = pal.light
        when (kind) {
            Kind.CLEAR -> if (isDay) sun(t, l) else night(t, l)
            Kind.PARTLY -> { if (isDay) sun(t, l, 0.8f) else night(t, l); clouds(t, l, 3, 0.10f) }
            Kind.CLOUDY -> clouds(t, l, 6, 0.13f)
            Kind.FOG -> { clouds(t, l, 3, 0.06f); fog(t, l) }
            Kind.RAIN -> { clouds(t, l, 5, 0.10f); rain(t, l, 150) }
            Kind.SNOW -> { clouds(t, l, 4, 0.08f); snow(t, l) }
            Kind.STORM -> { clouds(t, l, 7, 0.14f); rain(t, l, 230); flash(t, l) }
        }
        // keep the lower half calm so text stays readable
        if (!mid) drawRect(Brush.verticalGradient(0.42f to Color.Transparent, 1f to pal.bottom.copy(alpha = 0.9f)))
    }
}

// Midnight keeps particles near the top so the lower screen stays pure black (pixels off).
private var reach = 10f
private var band = 0.32f
private var cloudK = 1f
private fun DrawScope.fade(y: Float) = (1f - y / (size.height * reach)).coerceIn(0f, 1f)

private fun DrawScope.sun(t: Float, l: Color, s: Float = 1f) {
    val c = Offset(size.width * 0.78f, size.height * 0.15f)
    val r = size.minDimension * 0.12f * s
    val pulse = 1f + 0.05f * sin(t * 1.1f)
    drawCircle(Brush.radialGradient(listOf(l.copy(alpha = 0.22f), l.copy(alpha = 0.05f), Color.Transparent), c, r * 5f * pulse), r * 5f * pulse, c)
    rotate(t * 5f, c) {
        repeat(12) { i ->
            val a = i * (PI / 6).toFloat()
            val r1 = r * 1.45f
            val r2 = r * (1.85f + 0.18f * sin(t * 2f + i))
            drawLine(l.copy(alpha = 0.45f), c + Offset(cos(a) * r1, sin(a) * r1), c + Offset(cos(a) * r2, sin(a) * r2), r * 0.07f, StrokeCap.Round)
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color.White, l), c, r), r, c)
}

private fun DrawScope.night(t: Float, l: Color) {
    repeat(90) { i ->
        val p = Offset(rnd(i, 1) * size.width, rnd(i, 2) * size.height * 0.6f)
        val tw = 0.25f + 0.55f * (0.5f + 0.5f * sin(t * (0.6f + rnd(i, 3) * 1.8f) + i))
        drawCircle(l.copy(alpha = tw * (0.4f + 0.6f * rnd(i, 4))), 1.dp.toPx() * (0.6f + rnd(i, 5)), p)
    }
    val c = Offset(size.width * 0.76f, size.height * 0.15f)
    val r = size.minDimension * 0.09f
    drawCircle(Brush.radialGradient(listOf(l.copy(alpha = 0.16f), Color.Transparent), c, r * 4.5f), r * 4.5f, c)
    drawCircle(l.copy(alpha = 0.92f), r, c)
    drawCircle(Color.Black.copy(alpha = 0.10f), r * 0.22f, c + Offset(-r * 0.3f, -r * 0.2f))
    drawCircle(Color.Black.copy(alpha = 0.08f), r * 0.15f, c + Offset(r * 0.35f, r * 0.3f))
    drawCircle(Color.Black.copy(alpha = 0.07f), r * 0.11f, c + Offset(-r * 0.1f, r * 0.45f))
}

private fun cloudParts(o: Offset, w: Float) = Path().apply {
    addOval(Rect(Offset(o.x, o.y - w * 0.10f), Size(w * 0.45f, w * 0.30f)))
    addOval(Rect(Offset(o.x + w * 0.22f, o.y - w * 0.24f), Size(w * 0.50f, w * 0.44f)))
    addOval(Rect(Offset(o.x + w * 0.55f, o.y - w * 0.12f), Size(w * 0.45f, w * 0.32f)))
    addRoundRect(RoundRect(Rect(o.x + w * 0.05f, o.y, o.x + w * 0.95f, o.y + w * 0.18f), w * 0.09f, w * 0.09f))
}

/** Puffs merged into a single outline, so overlaps don't double up. */
private fun cloudPath(o: Offset, w: Float) = Path().apply { op(this, cloudParts(o, w), PathOperation.Union) }

private fun DrawScope.clouds(t: Float, l: Color, n: Int, alpha: Float) {
    repeat(n) { i ->
        val w = size.width * (0.5f + rnd(i, 11) * 0.45f) * (if (cloudK < 1f) 0.75f else 1f)
        val y = size.height * (0.04f + rnd(i, 12) * band)
        val speed = size.width * (0.012f + rnd(i, 13) * 0.025f)
        val span = size.width + w * 2
        val x = ((rnd(i, 14) * span + t * speed) % span) - w
        drawPath(cloudParts(Offset(x, y), w), l.copy(alpha = alpha * cloudK * (0.6f + 0.4f * rnd(i, 15))))
    }
}

private fun DrawScope.rain(t: Float, l: Color, n: Int) {
    val len = size.height * 0.035f
    val stroke = 1.3.dp.toPx()
    repeat(n) { i ->
        val speed = size.height * (0.9f + rnd(i, 21) * 0.7f)
        val y = ((rnd(i, 22) * size.height + t * speed) % (size.height + len)) - len
        val x = rnd(i, 23) * size.width * 1.15f - y * 0.12f
        drawLine(l.copy(alpha = (0.12f + 0.35f * rnd(i, 24)) * fade(y)), Offset(x, y), Offset(x - len * 0.13f, y + len), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.snow(t: Float, l: Color) {
    repeat(110) { i ->
        val speed = size.height * (0.04f + rnd(i, 31) * 0.07f)
        val y = (rnd(i, 32) * size.height + t * speed) % size.height
        val x = rnd(i, 33) * size.width + sin(t * (0.5f + rnd(i, 34)) + i) * 14.dp.toPx()
        drawCircle(l.copy(alpha = (0.35f + 0.5f * rnd(i, 35)) * fade(y)), (1.2f + rnd(i, 36) * 2.6f).dp.toPx(), Offset(x, y))
    }
}

private fun DrawScope.fog(t: Float, l: Color) {
    repeat(6) { i ->
        val h = size.height * (0.06f + rnd(i, 41) * 0.05f)
        val y = size.height * (0.12f + i * 0.11f)
        val shift = sin(t * (0.08f + rnd(i, 42) * 0.1f) + i) * size.width * 0.25f
        drawOval(
            Brush.horizontalGradient(listOf(Color.Transparent, l.copy(alpha = 0.09f), Color.Transparent)),
            Offset(-size.width * 0.3f + shift, y), Size(size.width * 1.6f, h),
        )
    }
}

private fun DrawScope.flash(t: Float, l: Color) {
    val p = t % 7.3f
    val a = when {
        p < 0.07f -> 0.28f
        p in 0.16f..0.22f -> 0.18f
        p in 0.22f..0.6f -> 0.18f * (1f - (p - 0.22f) / 0.38f)
        else -> 0f
    }
    if (a > 0f) drawRect(l.copy(alpha = a))
}

/** Small condition icon. */
@Composable
fun Glyph(kind: Kind, isDay: Boolean, size: Dp = 26.dp, color: Color = Ink) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val st = Stroke(s * 0.07f, cap = StrokeCap.Round)
        fun sunAt(c: Offset, r: Float) {
            drawCircle(color, r, c)
            repeat(8) { i ->
                val a = i * (PI / 4).toFloat()
                drawLine(color, c + Offset(cos(a), sin(a)) * (r * 1.5f), c + Offset(cos(a), sin(a)) * (r * 2.1f), s * 0.06f, StrokeCap.Round)
            }
        }
        fun moonAt(c: Offset, r: Float) {
            val p = Path().apply {
                addOval(Rect(c, r))
                op(this, Path().apply { addOval(Rect(c + Offset(r * 0.55f, -r * 0.35f), r * 0.9f)) }, PathOperation.Difference)
            }
            drawPath(p, color)
        }
        fun cloud(y: Float = s * 0.55f) = drawPath(cloudPath(Offset(s * 0.1f, y), s * 0.8f), color, style = st)
        when (kind) {
            Kind.CLEAR -> if (isDay) sunAt(center, s * 0.2f) else moonAt(center, s * 0.32f)
            Kind.PARTLY -> {
                if (isDay) sunAt(Offset(s * 0.65f, s * 0.3f), s * 0.13f) else moonAt(Offset(s * 0.66f, s * 0.3f), s * 0.2f)
                cloud(s * 0.62f)
            }
            Kind.CLOUDY -> cloud()
            Kind.FOG -> repeat(3) { i ->
                val y = s * (0.3f + i * 0.2f)
                drawLine(color, Offset(s * (0.12f + i * 0.06f), y), Offset(s * (0.88f - i * 0.04f), y), s * 0.07f, StrokeCap.Round)
            }
            Kind.RAIN -> { cloud(s * 0.42f); repeat(3) { i -> val x = s * (0.3f + i * 0.2f); drawLine(color, Offset(x, s * 0.7f), Offset(x - s * 0.06f, s * 0.9f), s * 0.06f, StrokeCap.Round) } }
            Kind.SNOW -> { cloud(s * 0.42f); repeat(3) { i -> drawCircle(color, s * 0.045f, Offset(s * (0.3f + i * 0.2f), s * (0.78f + (i % 2) * 0.1f))) } }
            Kind.STORM -> {
                cloud(s * 0.4f)
                drawPath(Path().apply {
                    moveTo(s * 0.55f, s * 0.6f); lineTo(s * 0.42f, s * 0.8f); lineTo(s * 0.52f, s * 0.8f); lineTo(s * 0.44f, s * 0.98f)
                    lineTo(s * 0.64f, s * 0.74f); lineTo(s * 0.54f, s * 0.74f); close()
                }, color)
            }
        }
    }
}
