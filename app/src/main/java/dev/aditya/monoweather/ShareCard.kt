package dev.aditya.monoweather

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A clean image of today's weather in the current theme, handed to Android's share sheet. */
object ShareCard {
    fun share(c: Context, place: Place, w: Weather, theme: Theme) {
        val b = draw(c, place, w, theme)
        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "mono-weather.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.share", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${place.name}: ${w.temp}°, ${labelOf(w.code)} · via Mono")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        c.startActivity(Intent.createChooser(send, "Share weather").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun draw(c: Context, place: Place, w: Weather, theme: Theme): Bitmap {
        val W = 1080; val H = 1350
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val pal = paletteFor(theme, w.kind, w.isDay)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (theme == Theme.MIDNIGHT) {
            cv.drawColor(android.graphics.Color.BLACK)
            p.shader = RadialGradient(W * 0.82f, -H * 0.05f, H * 0.75f, intArrayOf(pal.top.copy(alpha = 0.5f).toArgb(), pal.top.copy(alpha = 0.12f).toArgb(), 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
            p.shader = RadialGradient(W * 0.05f, H * 0.1f, H * 0.4f, pal.glow2.copy(alpha = 0.16f).toArgb(), 0, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        } else {
            p.shader = LinearGradient(0f, 0f, 0f, H.toFloat(), pal.top.toArgb(), pal.bottom.toArgb(), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        }
        p.shader = null
        val font = runCatching { c.resources.getFont(R.font.inter_tight) }.getOrDefault(Typeface.SANS_SERIF)
        fun face(weight: Int) = Typeface.create(font, weight, false)
        val ink = Ink.toArgb()
        val dim = Ink.copy(alpha = 0.6f).toArgb()
        p.color = dim; p.typeface = face(400); p.textSize = 34f; p.letterSpacing = 0.12f
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase()
        cv.drawText("${place.name.uppercase()} · $date", 90f, 170f, p)
        p.letterSpacing = -0.04f
        p.typeface = face(100); p.textSize = 380f
        p.shader = LinearGradient(0f, 260f, 0f, 640f, android.graphics.Color.WHITE, pal.accent.toArgb(), Shader.TileMode.CLAMP)
        cv.drawText("${w.temp}°", 70f, 620f, p)
        p.shader = null; p.letterSpacing = 0f
        p.color = ink; p.typeface = face(300); p.textSize = 72f
        cv.drawText(labelOf(w.code), 90f, 760f, p)
        p.color = dim; p.typeface = face(400); p.textSize = 40f
        cv.drawText("H ${w.hi}°   L ${w.lo}°   Feels ${w.feels}°", 90f, 840f, p)
        val (wear, _) = Insights.wear(w)
        p.color = pal.accent.toArgb(); p.textSize = 38f
        cv.drawText(wear, 90f, 920f, p)
        // a small 12-hour line
        val hs = w.hours.take(12)
        if (hs.size > 2) {
            val lo = hs.minOf { it.temp }; val span = maxOf(1, hs.maxOf { it.temp } - lo)
            val x0 = 90f; val x1 = W - 90f; val yb = 1150f; val yt = 1030f
            val pts = hs.mapIndexed { i, h -> (x0 + i * (x1 - x0) / (hs.size - 1)) to (yb - (h.temp - lo).toFloat() / span * (yb - yt)) }
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = pal.accent.toArgb(); p.strokeCap = Paint.Cap.ROUND
            val path = android.graphics.Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (i in 1 until pts.size) { val (ax, ay) = pts[i - 1]; val (bx, by) = pts[i]; val mx = (ax + bx) / 2; cubicTo(mx, ay, mx, by, bx, by) }
            }
            cv.drawPath(path, p)
            p.style = Paint.Style.FILL; p.textSize = 30f; p.color = dim; p.textAlign = Paint.Align.CENTER
            listOf(0, hs.size / 2, hs.lastIndex).forEach { i -> cv.drawText(if (i == 0) "now" else hs[i].label, pts[i].first, 1210f, p); cv.drawText("${hs[i].temp}°", pts[i].first, pts[i].second - 22f, p) }
            p.textAlign = Paint.Align.LEFT
        }
        p.color = Ink.copy(alpha = 0.35f).toArgb(); p.textSize = 28f; p.letterSpacing = 0.3f; p.typeface = face(500)
        cv.drawText("MONO", 90f, H - 70f, p)
        return b
    }
}
