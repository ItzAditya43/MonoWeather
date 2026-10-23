package dev.aditya.monoweather

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.ADD
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.COS
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.DIV
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MAX
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MIN
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MOD
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MUL
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.SIN
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.SUB
import androidx.compose.remote.creation.CreationDisplayInfo
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import androidx.compose.remote.creation.profile.WidgetsProfileWriterV6
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.sin

/**
 * The widget's live layer, on Android 16's Remote Compose engine: the home screen runs the animation itself
 * (rain, snow, stars, sun glow, drifting cloud) and moves Orbit's sun by the clock, with Mono asleep.
 * On older Android the frame-flipping layer in [WidgetRenderer] is used instead.
 */
@SuppressLint("RestrictedApi")
object RcWidget {
    fun supported() = Build.VERSION.SDK_INT >= 36

    private fun rr(i: Int, k: Int): Float { val x = sin(i * 12.9898 + k * 78.233) * 43758.547; return (x - floor(x)).toFloat() }

    fun layer(c: Context, wPx: Int, hPx: Int, w: Weather, pal: Palette, design: Design, anim: Anim): RemoteViews? =
        if (!supported() || anim == Anim.OFF) null else runCatching { build(c, wPx, hPx, w, pal, design, anim) }.getOrNull()

    @RequiresApi(35)
    private fun build(c: Context, wPx: Int, hPx: Int, w: Weather, pal: Palette, design: Design, anim: Anim): RemoteViews {
        val d = c.resources.displayMetrics.density
        val info = CreationDisplayInfo(wPx, hPx, c.resources.displayMetrics.densityDpi)
        val wr = WidgetsProfileWriterV6.createWriter(info, "Mono weather", RcPlatformProfiles.WIDGETS_V6) as RemoteComposeWriterAndroid
        val p = wr.painter
        val W = wPx.toFloat(); val H = hPx.toFloat()
        val t = RemoteComposeWriter.TIME_IN_CONTINUOUS_SEC
        val slow = if (anim == Anim.REDUCED) 0.55f else 1f
        fun n(full: Int) = if (anim == Anim.REDUCED) full / 2 else full
        fun col(a: Float) = pal.light.copy(alpha = a.coerceIn(0f, 1f)).toArgb()

        // transparent canvas; text and background stay in the normal widget views underneath
        p.setColor(0).commit()
        wr.drawRect(0f, 0f, W, H)

        when (w.kind) {
            Kind.RAIN, Kind.STORM -> {
                p.setColor(col(0.32f)).setStrokeWidth(1.2f * d).setStrokeCap(android.graphics.Paint.Cap.ROUND).commit()
                repeat(n(28)) { i ->
                    val x = rr(i, 2) * W
                    val speed = H * (0.9f + rr(i, 9) * 0.7f) * slow
                    val y = wr.floatExpression(t, speed, MUL, rr(i, 1) * (H + 14 * d), ADD, H + 14 * d, MOD, 7 * d, SUB)
                    val y2 = wr.floatExpression(y, 7 * d, ADD)
                    wr.drawLine(x, y, x - 1.6f * d, y2)
                }
            }
            Kind.SNOW -> {
                p.setColor(col(0.55f)).commit()
                repeat(n(24)) { i ->
                    val speed = H * (0.08f + rr(i, 31) * 0.1f) * slow
                    val y = wr.floatExpression(t, speed, MUL, rr(i, 3) * H, ADD, H, MOD)
                    val x = wr.floatExpression(t, 0.6f + rr(i, 34), MUL, i.toFloat(), ADD, SIN, 4 * d, MUL, rr(i, 4) * W, ADD)
                    wr.drawCircle(x, y, (0.9f + rr(i, 36) * 1.1f) * d)
                }
            }
            Kind.CLEAR -> if (!w.isDay) {
                p.setColor(col(0.55f)).commit()
                repeat(n(26)) { i ->
                    val r = wr.floatExpression(t, (0.6f + rr(i, 3) * 1.6f) * slow, MUL, i.toFloat(), ADD, SIN, 0.45f * d, MUL, 0.75f * d, ADD)
                    wr.drawCircle(rr(i, 6) * W, rr(i, 7) * H, r)
                }
            } else {
                // a soft sun glow breathing in the top-right corner
                listOf(0.10f to 1.25f, 0.08f to 0.9f, 0.07f to 0.55f).forEach { (a, k) ->
                    p.setColor(col(a)).commit()
                    val r = wr.floatExpression(t, 0.8f * slow, MUL, SIN, 0.08f * H * k, MUL, H * k, ADD)
                    wr.drawCircle(W * 0.92f, 0f, r)
                }
            }
            else -> {
                p.setColor(col(if (w.kind == Kind.FOG) 0.08f else 0.065f)).commit()
                repeat(3) { i ->
                    val ow = 120 * d; val oh = 13 * d
                    val speed = W * (0.012f + rr(i, 13) * 0.02f) * slow
                    val left = wr.floatExpression(t, speed, MUL, rr(i, 8) * (W + ow * 2), ADD, W + ow * 2, MOD, ow, SUB)
                    val right = wr.floatExpression(left, ow * 2, ADD)
                    val cy = H * (0.25f + i * 0.25f)
                    wr.drawOval(left, cy - oh, right, cy + oh)
                }
            }
        }

        // Orbit: place the sun on its arc from the clock, so it moves even if Mono never refreshes
        if (design == Design.ORBIT && w.sunriseMin != null && w.sunsetMin != null) {
            val rise = w.sunriseMin.toFloat(); val span = (w.sunsetMin - w.sunriseMin).coerceAtLeast(1).toFloat()
            val cx = 12 * d + 42 * d
            val cy = (H - 56 * d) / 2 + 42 * d
            val r = 32 * d
            val f = wr.floatExpression(RemoteContext.FLOAT_TIME_IN_MIN, rise, SUB, span, DIV, 0f, MAX, 1f, MIN)
            val a = wr.floatExpression(f, 3.14159265f, MUL, 3.14159265f, ADD)
            val dx = wr.floatExpression(a, COS, r, MUL, cx, ADD)
            val dy = wr.floatExpression(a, SIN, r, MUL, cy, ADD)
            p.setColor(pal.accent.copy(alpha = 0.18f).toArgb()).commit(); wr.drawCircle(dx, dy, 13 * d)
            p.setColor(pal.accent.copy(alpha = 0.35f).toArgb()).commit(); wr.drawCircle(dx, dy, 8 * d)
            p.setColor(if (w.isDay) 0xFFFFFFFF.toInt() else pal.accent.toArgb()).commit(); wr.drawCircle(dx, dy, 4 * d)
        }
        return RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(wr.encodeToByteArray())).build())
    }
}
