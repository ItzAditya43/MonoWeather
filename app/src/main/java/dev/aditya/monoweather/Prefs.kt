package dev.aditya.monoweather

import android.content.Context
import android.graphics.Rect
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

enum class Anim(val label: String) { FULL("Full"), REDUCED("Reduced"), OFF("Off") }

/** Home sections that can be hidden. Current weather, 24 hours and 7 days are always shown. */
enum class Section(val label: String, val sub: String, val default: Boolean) {
    NEXT2H("Next 2 hours", "Rain in 15-minute steps", true),
    AIR("Air quality & UV", "", true),
    COMFORT("Humidity & wind", "", true),
    EXTRA("Pressure & visibility", "Extra detail tiles", false),
    RADAR("Rain radar", "", true),
    SUN("Sun & moon", "", true),
    YESTERDAY("Compared with yesterday", "", true),
}

data class Units(val tempF: Boolean, val wind: String, val clock24: Boolean)

/** Every user setting, in one SharedPreferences file. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("mono", Context.MODE_PRIVATE)

    fun theme(c: Context) = runCatching { Theme.valueOf(p(c).getString("theme", null)!!) }.getOrDefault(Theme.MINIMALIST)
    fun setTheme(c: Context, t: Theme) = p(c).edit().putString("theme", t.name).apply()

    fun units(c: Context): Units {
        val legacyImperial = p(c).getBoolean("imperial", false)
        return Units(legacyImperial, p(c).getString("wind", null) ?: if (legacyImperial) "mph" else "km/h", p(c).getBoolean("clock24", true))
    }
    fun setTempF(c: Context, on: Boolean) = p(c).edit().putBoolean("imperial", on).apply()
    fun setWind(c: Context, u: String) = p(c).edit().putString("wind", u).apply()
    fun setClock24(c: Context, on: Boolean) = p(c).edit().putBoolean("clock24", on).apply()

    fun anim(c: Context) = runCatching { Anim.valueOf(p(c).getString("anim", null)!!) }.getOrDefault(Anim.FULL)
    fun setAnim(c: Context, a: Anim) = p(c).edit().putString("anim", a.name).apply()
    fun haptics(c: Context) = p(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, on: Boolean) = p(c).edit().putBoolean("haptics", on).apply()

    fun shown(c: Context, s: Section) = p(c).getBoolean("sec_${s.name}", s.default)
    fun setShown(c: Context, s: Section, on: Boolean) = p(c).edit().putBoolean("sec_${s.name}", on).apply()

    fun radarMin(c: Context) = p(c).getBoolean("radarMin", false)
    fun setRadarMin(c: Context, on: Boolean) = p(c).edit().putBoolean("radarMin", on).apply()
    fun radarWifi(c: Context) = p(c).getBoolean("radarWifi", false)
    fun setRadarWifi(c: Context, on: Boolean) = p(c).edit().putBoolean("radarWifi", on).apply()

    fun rainPct(c: Context) = p(c).getInt("rainPct", 60)
    fun setRainPct(c: Context, v: Int) = p(c).edit().putInt("rainPct", v).apply()
    fun morningHour(c: Context) = p(c).getInt("morningHour", 7)
    fun setMorningHour(c: Context, v: Int) = p(c).edit().putInt("morningHour", v).apply()

    /** "Auto" or a provider name to try first. */
    fun source(c: Context) = p(c).getString("source", "Auto")!!
    fun setSource(c: Context, v: String) = p(c).edit().putString("source", v).apply()
    fun refreshMin(c: Context) = p(c).getInt("refreshMin", 30)
    fun setRefreshMin(c: Context, v: Int) = p(c).edit().putInt("refreshMin", v).apply()

    // per-widget city and on-screen position (for wallpaper-matched glass)
    fun widgetCity(c: Context, id: Int) = p(c).getString("wcity_$id", null)
    fun setWidgetCity(c: Context, id: Int, key: String) = p(c).edit().putString("wcity_$id", key).apply()
    fun widgetBounds(c: Context, id: Int) = p(c).getString("wb_$id", null)?.let { Rect.unflattenFromString(it) }
    fun setWidgetBounds(c: Context, id: Int, r: Rect) = p(c).edit().putString("wb_$id", r.flattenToString()).apply()

    // data used this month
    fun addBytes(c: Context, n: Long) {
        if (n <= 0) return
        val month = LocalDate.now().toString().take(7)
        val pp = p(c)
        val cur = if (pp.getString("bytesMonth", "") == month) pp.getLong("bytes", 0) else 0
        pp.edit().putString("bytesMonth", month).putLong("bytes", cur + n).apply()
    }
    fun bytesThisMonth(c: Context) = if (p(c).getString("bytesMonth", "") == LocalDate.now().toString().take(7)) p(c).getLong("bytes", 0) else 0

    // background refresh log, newest first, last 12 entries
    fun log(c: Context, line: String) {
        val entries = (listOf("${fmtClock(LocalTime.now(), units(c).clock24)}  $line") + logEntries(c)).take(12)
        p(c).edit().putString("log", entries.joinToString("\n")).apply()
    }
    fun logEntries(c: Context) = p(c).getString("log", "")!!.lines().filter { it.isNotBlank() }
}

/** Formats a clock time the way the user chose in Settings → Units (24h "13:45", 12h "1:45 pm"). */
fun fmtClock(t: LocalTime, clock24: Boolean): String =
    if (clock24) "%02d:%02d".format(t.hour, t.minute)
    else t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)).lowercase()

/** Bytes downloaded since the last flush into [Prefs.addBytes]. */
object Net {
    val bytes = AtomicLong()
    fun flush(c: Context) = Prefs.addBytes(c, bytes.getAndSet(0))
}
