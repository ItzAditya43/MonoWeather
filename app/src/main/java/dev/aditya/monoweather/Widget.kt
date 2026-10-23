package dev.aditya.monoweather

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class Design { TYPE, HORIZON, ORBIT, PULSE }

/**
 * One provider per (design, style). Type and Horizon use the Minimalist palette,
 * Orbit and Pulse the Midnight one; each comes as "dark" or "glass".
 */
abstract class MonoWidget(val design: Design, val glass: Boolean) : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        // re-slices the saved forecast to the current hour, so the widget stays right even if a refresh was missed
        ids.forEach { WidgetRenderer.render(c, m, it, design, glass) }
        Sync.schedule(c)
        Sync.nowIfStale(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) =
        WidgetRenderer.render(c, m, id, design, glass)

    override fun onEnabled(c: Context) = Sync.now(c)
}

class TypeDark : MonoWidget(Design.TYPE, false)
class TypeGlass : MonoWidget(Design.TYPE, true)
class HorizonDark : MonoWidget(Design.HORIZON, false)
class HorizonGlass : MonoWidget(Design.HORIZON, true)
class OrbitDark : MonoWidget(Design.ORBIT, false)
class OrbitGlass : MonoWidget(Design.ORBIT, true)
class PulseDark : MonoWidget(Design.PULSE, false)
class PulseGlass : MonoWidget(Design.PULSE, true)

object Widget {
    val all = listOf(
        TypeDark::class.java to (Design.TYPE to false), TypeGlass::class.java to (Design.TYPE to true),
        HorizonDark::class.java to (Design.HORIZON to false), HorizonGlass::class.java to (Design.HORIZON to true),
        OrbitDark::class.java to (Design.ORBIT to false), OrbitGlass::class.java to (Design.ORBIT to true),
        PulseDark::class.java to (Design.PULSE to false), PulseGlass::class.java to (Design.PULSE to true),
    )

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        all.forEach { (cls, spec) ->
            m.getAppWidgetIds(ComponentName(c, cls)).forEach { WidgetRenderer.render(c, m, it, spec.first, spec.second) }
        }
    }
}

object WidgetRenderer {
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private const val DIM = 0x99FFFFFF.toInt()

    fun render(c: Context, m: AppWidgetManager, id: Int, design: Design, glass: Boolean) {
        val o = m.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 80
        m.updateAppWidget(id, views(c, design, glass, wDp, hDp))
    }

    fun views(c: Context, design: Design, glass: Boolean, wDp: Int, hDp: Int): RemoteViews {
        val d = c.resources.displayMetrics.density
        val home = Repo.places(c).firstOrNull()
        val w = home?.let { Repo.cached(c, it) }
        val midnight = design == Design.ORBIT || design == Design.PULSE
        val pal = if (midnight) midnightPaletteOf(w?.kind ?: Kind.CLEAR, w?.isDay ?: false) else paletteOf(w?.kind ?: Kind.CLEAR, w?.isDay ?: false)

        val layout = when (design) {
            Design.TYPE -> R.layout.widget_type; Design.HORIZON -> R.layout.widget_horizon
            Design.ORBIT -> R.layout.widget_orbit; Design.PULSE -> R.layout.widget_pulse
        }
        val v = RemoteViews(c.packageName, layout)
        v.setImageViewBitmap(R.id.w_bg, background(wDp, hDp, d, pal, glass, midnight))
        val fx = intArrayOf(R.id.w_f0, R.id.w_f1, R.id.w_f2, R.id.w_f3, R.id.w_f4, R.id.w_f5, R.id.w_f6, R.id.w_f7)
        fx.forEachIndexed { i, vid -> v.setImageViewBitmap(vid, particles(wDp, hDp, w, pal, i)) }
        v.setTextViewText(R.id.w_temp, w?.let { "${it.temp}°" } ?: "--")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.w_root, open)

        when (design) {
            Design.TYPE -> {
                v.setTextViewText(R.id.w_cond, w?.let { labelOf(it.code) } ?: "Tap to pick a city")
                v.setTextViewText(R.id.w_place, home?.name ?: "Mono")
            }
            Design.HORIZON -> if (w != null) v.setImageViewBitmap(R.id.w_chart, horizon(w, (wDp - 32 - 76 - 12).coerceAtLeast(120), (hDp - 16).coerceIn(48, 120), d))
            Design.ORBIT -> {
                v.setTextColor(R.id.w_temp, pal.accent.toArgb())
                v.setTextViewText(R.id.w_cond, w?.let { labelOf(it.code) } ?: "Tap to set up")
                v.setImageViewBitmap(R.id.w_arc, orbit(w, pal, d))
            }
            Design.PULSE -> {
                v.setTextViewText(R.id.w_cond, w?.let { pulseCaption(it) } ?: "Tap to pick a city")
                v.setImageViewBitmap(R.id.w_bars, pulse(w, pal, (wDp - 32 - 76 - 14).coerceAtLeast(120), d))
            }
        }
        return v
    }

    private val grain: Bitmap by lazy {
        val rnd = java.util.Random(7)
        Bitmap.createBitmap(IntArray(96 * 96) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }, 96, 96, Bitmap.Config.ARGB_8888)
    }

    private fun rr(i: Int, k: Int): Float { val x = sin(i * 12.9898 + k * 78.233) * 43758.547; return (x - kotlin.math.floor(x)).toFloat() }

    /**
     * One frame of the 8-frame particle loop the widget flips through (rain, snow, stars, sun glow, drifting cloud).
     * Drawn at 1 px per dp to stay light; the launcher scales it up.
     */
    private fun particles(wDp: Int, hDp: Int, w: Weather?, pal: Palette, f: Int): Bitmap {
        val b = Bitmap.createBitmap(wDp.coerceAtLeast(1), hDp.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (w == null) return b
        val cv = Canvas(b)
        val W = b.width.toFloat(); val H = b.height.toFloat()
        val acc = pal.light
        fun col(a: Float) = acc.copy(alpha = a.coerceIn(0f, 1f)).toArgb()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        when (w.kind) {
            Kind.RAIN, Kind.STORM -> {
                p.strokeWidth = 1.1f; p.color = col(0.3f)
                repeat(26) { i ->
                    val y = ((rr(i, 1) * H + f * H / 8) % (H + 12)) - 6; val x = rr(i, 2) * W
                    cv.drawLine(x, y, x - 2, y + 7, p)
                }
                if (w.kind == Kind.STORM && f == 2) cv.drawColor(col(0.12f))
            }
            Kind.SNOW -> {
                p.color = col(0.5f)
                repeat(22) { i ->
                    val y = (rr(i, 3) * H + f * H / 16) % H; val x = rr(i, 4) * W + sin(f / 8.0 * 2 * PI + i).toFloat() * 4
                    cv.drawCircle(x, y, 1.3f, p)
                }
            }
            Kind.CLEAR -> if (!w.isDay) repeat(24) { i ->
                p.color = col(0.15f + 0.5f * (0.5f + 0.5f * sin(f / 8.0 * 2 * PI * (1 + rr(i, 5)) + i).toFloat()))
                cv.drawCircle(rr(i, 6) * W, rr(i, 7) * H, 0.9f, p)
            } else {
                p.shader = RadialGradient(W * 0.9f, 0f, H * (1.1f + 0.12f * sin(f / 8.0 * 2 * PI).toFloat()), col(0.22f), 0, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, W, H, p)
            }
            else -> {
                p.color = col(0.07f)
                repeat(3) { i ->
                    val cx = ((rr(i, 8) * W + f * W / 32) % (W + 120)) - 60
                    cv.drawOval(RectF(cx - 60, H * (0.25f + i * 0.25f) - 14, cx + 60, H * (0.25f + i * 0.25f) + 14), p)
                }
            }
        }
        // keep the particles inside the rounded card
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        val clip = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ALPHA_8)
        Canvas(clip).drawRoundRect(RectF(0f, 0f, W, H), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG))
        cv.drawBitmap(clip, 0f, 0f, mask)
        return b
    }

    private fun bmp(wDp: Int, hDp: Int, d: Float) = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    private fun background(wDp: Int, hDp: Int, d: Float, pal: Palette, glass: Boolean, midnight: Boolean): Bitmap {
        val b = bmp(wDp, hDp, d)
        val cv = Canvas(b)
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val rad = 24 * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when {
            glass -> {
                // frosted grey glass: the wallpaper shows through, with frost grain, depth and a bright top edge
                p.shader = LinearGradient(0f, 0f, r.right, r.bottom, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
                cv.drawRoundRect(r, rad, rad, p)
                p.shader = LinearGradient(0f, 0f, 0f, r.bottom * 0.4f, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                cv.drawRoundRect(r, rad, rad, p)
                p.shader = LinearGradient(0f, r.bottom * 0.6f, 0f, r.bottom, 0x00000000, 0x1A000000, Shader.TileMode.CLAMP)
                cv.drawRoundRect(r, rad, rad, p)
                p.shader = android.graphics.BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                p.alpha = 18
                cv.drawRoundRect(r, rad, rad, p)
                p.alpha = 255
                // bright rim along the top edge, clipped to the card's rounded shape
                cv.save()
                cv.clipPath(Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) })
                p.shader = LinearGradient(0f, 0f, 0f, 3 * d, 0x73FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, r.right, 3 * d, p)
                cv.restore()
            }
            midnight -> { p.color = android.graphics.Color.BLACK; cv.drawRoundRect(r, rad, rad, p) }
            else -> {
                p.shader = LinearGradient(0f, 0f, 0f, r.bottom, pal.top.toArgb(), pal.bottom.toArgb(), Shader.TileMode.CLAMP)
                cv.drawRoundRect(r, rad, rad, p)
            }
        }
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = when {
            glass -> 0x57FFFFFF
            midnight -> 0x14FFFFFF
            else -> pal.accent.copy(alpha = 0.22f).toArgb()
        }
        r.inset(d / 2, d / 2)
        cv.drawRoundRect(r, rad, rad, p)
        return b
    }

    /** Next 6 hours as one white line with dots; "now" is hollow. */
    private fun horizon(w: Weather, wDp: Int, hDp: Int, d: Float): Bitmap {
        val b = bmp(wDp, hDp, d)
        val cv = Canvas(b)
        val hs = w.hours.take(6)
        if (hs.size < 2) return b
        val lo = hs.minOf { it.temp }; val span = max(1, hs.maxOf { it.temp } - lo)
        val x0 = 14 * d; val x1 = b.width - 14 * d
        val yTop = 26 * d; val yBot = b.height - 26 * d
        val pts = hs.mapIndexed { i, h -> (x0 + i * (x1 - x0) / (hs.size - 1)) to (yBot - (h.temp - lo).toFloat() / span * (yBot - yTop)) }
        val line = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) {
                val (ax, ay) = pts[i - 1]; val (bx, by) = pts[i]; val mx = (ax + bx) / 2
                cubicTo(mx, ay, mx, by, bx, by)
            }
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xD9FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
        cv.drawPath(line, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        pts.forEachIndexed { i, (x, y) ->
            p.style = Paint.Style.FILL; p.color = if (i == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            cv.drawCircle(x, y, 3.5f * d, p)
            p.style = Paint.Style.STROKE; p.color = android.graphics.Color.WHITE; p.strokeWidth = 1.5f * d
            cv.drawCircle(x, y, 3.5f * d, p)
            t.typeface = light; t.textSize = 11.5f * d; t.color = android.graphics.Color.WHITE
            cv.drawText("${hs[i].temp}°", x, y - 8 * d, t)
            t.typeface = regular; t.textSize = 10 * d; t.color = DIM
            cv.drawText(if (i == 0) "now" else hs[i].label.take(2), x, b.height - 6 * d, t)
        }
        return b
    }

    /** Today's sun path: dotted arc sunrise→sunset, solid up to now, glowing dot at the sun's position. */
    private fun orbit(w: Weather?, pal: Palette, d: Float): Bitmap {
        val b = bmp(84, 56, d)
        val cv = Canvas(b)
        val cx = b.width / 2f; val cy = 42 * d; val r = 32 * d
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; strokeCap = Paint.Cap.ROUND }
        p.color = 0x24FFFFFF; p.pathEffect = DashPathEffect(floatArrayOf(1 * d, 4 * d), 0f)
        cv.drawArc(rect, 180f, 180f, false, p)
        p.pathEffect = null
        val f = sunProgress(w)
        p.color = pal.accent.toArgb()
        cv.drawArc(rect, 180f, 180f * f, false, p)
        val a = PI + PI * f
        val dx = cx + (cos(a) * r).toFloat(); val dy = cy + (sin(a) * r).toFloat()
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(dx, dy, 14 * d, pal.accent.copy(alpha = 0.6f).toArgb(), 0, Shader.TileMode.CLAMP) }
        cv.drawCircle(dx, dy, 14 * d, glow)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (w?.isDay != false) android.graphics.Color.WHITE else pal.accent.toArgb() }
        cv.drawCircle(dx, dy, 4 * d, dot)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 8.5f * d; color = 0x66FFFFFF; typeface = regular }
        cv.drawText(w?.sunrise ?: "", cx - r, cy + 12 * d, t)
        cv.drawText(w?.sunset ?: "", cx + r, cy + 12 * d, t)
        return b
    }

    private fun sunProgress(w: Weather?): Float {
        if (w == null) return 0.5f
        return runCatching {
            val now = LocalTime.now(ZoneId.of(w.zone))
            val rise = LocalTime.parse(w.sunrise); val set = LocalTime.parse(w.sunset)
            ((now.toSecondOfDay() - rise.toSecondOfDay()).toFloat() / (set.toSecondOfDay() - rise.toSecondOfDay())).coerceIn(0f, 1f)
        }.getOrDefault(0.5f)
    }

    /** Rain for the next 2 hours as glowing bars; flat when dry. */
    private fun pulse(w: Weather?, pal: Palette, wDp: Int, d: Float): Bitmap {
        val b = bmp(wDp, 36, d)
        val cv = Canvas(b)
        val q = w?.now2h ?: List(8) { 0.0 }
        val wet = q.any { it >= 0.05 }
        val mx = maxOf(1.5, q.max())
        val bw = b.width / 8f
        val acc = pal.accent.toArgb()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        q.take(8).forEachIndexed { i, v ->
            val h = if (wet) max(4 * d, (v / mx).toFloat() * (b.height - 6 * d)) else 4 * d
            val x = i * bw + 3 * d
            val rect = RectF(x, b.height - h, x + bw - 6 * d, b.height.toFloat())
            if (wet) {
                p.shader = null; p.color = pal.accent.copy(alpha = 0.35f).toArgb(); p.maskFilter = BlurMaskFilter(5 * d, BlurMaskFilter.Blur.NORMAL)
                cv.drawRoundRect(rect, 3 * d, 3 * d, p)
                p.maskFilter = null
            }
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, acc, pal.accent.copy(alpha = 0.15f).toArgb(), Shader.TileMode.CLAMP)
            cv.drawRoundRect(rect, 3 * d, 3 * d, p)
        }
        return b
    }

    private fun pulseCaption(w: Weather): String {
        val q = w.now2h ?: return "${labelOf(w.code)} · ${w.hours.drop(1).take(2).maxOfOrNull { it.precip } ?: 0}% rain chance"
        if (q.none { it >= 0.05 }) return "${labelOf(w.code)} · dry for the next 2 hours"
        val start = runCatching { LocalTime.parse(w.nowStart) }.getOrNull() ?: return "Rain in the next 2 hours"
        return if (q[0] >= 0.05) q.indexOfFirst { it < 0.05 }.let { if (it < 0) "Rain for the next 2 hours" else "Rain now, easing by ${start.plusMinutes(15L * it)}" }
        else "Rain likely from ${start.plusMinutes(15L * q.indexOfFirst { it >= 0.05 })}"
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val places = Repo.places(applicationContext)
        if (places.isEmpty()) return Result.success()
        val results = places.map { runCatching { Repo.refresh(applicationContext, it) } }
        Widget.updateAll(applicationContext)
        results.first().getOrNull()?.let { Alerts.check(applicationContext, it) }
        return if (results.first().isSuccess || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}

object Sync {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    fun now(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("now", androidx.work.ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build())
    }

    /** Second refresh path: the system's widget timer asks for a fetch when the saved forecast is over 30 minutes old. */
    fun nowIfStale(c: Context) {
        val home = Repo.places(c).firstOrNull() ?: return
        val at = Repo.cached(c, home)?.fetchedAt ?: 0
        if (System.currentTimeMillis() - at > 30 * 60_000) now(c)
    }
}

/** Rain-soon and morning-summary notifications for the first (home) city. */
object Alerts {
    private fun prefs(c: Context) = c.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    fun rainOn(c: Context) = prefs(c).getBoolean("rain", false)
    fun morningOn(c: Context) = prefs(c).getBoolean("morning", false)
    fun setRain(c: Context, on: Boolean) = prefs(c).edit().putBoolean("rain", on).apply()
    fun setMorning(c: Context, on: Boolean) = prefs(c).edit().putBoolean("morning", on).apply()

    fun check(c: Context, w: Weather) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (rainOn(c) && w.kind != Kind.RAIN && w.kind != Kind.STORM && now - p.getLong("lastRain", 0) > 3 * 3600_000) {
            val soon = w.hours.drop(1).take(2).firstOrNull { it.precip >= 60 }
            if (soon != null) {
                notify(c, 1, "Rain soon in ${w.place}", "${soon.precip}% chance around ${soon.label}. Take an umbrella.")
                p.edit().putLong("lastRain", now).apply()
            }
        }
        val today = LocalDate.now().toString()
        if (morningOn(c) && LocalTime.now().hour in 6..9 && p.getString("lastMorning", "") != today) {
            val d = w.days.firstOrNull()
            val air = w.aqi?.let { " · AQI $it" } ?: ""
            notify(c, 2, "Today in ${w.place}", "${labelOf(w.code)}, ${w.hi}° / ${w.lo}° · rain ${d?.precip ?: 0}%$air")
            p.edit().putString("lastMorning", today).apply()
        }
    }

    private fun notify(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("weather", "Weather alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(c, "weather")
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id, n)
    }
}
