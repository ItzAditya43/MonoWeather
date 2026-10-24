package dev.aditya.monoweather

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-language helpers built only from the forecast Mono already has (no extra downloads). */
object Insights {
    data class Warn(val title: String, val detail: String)
    data class Commute(val time: String, val temp: Int, val precip: Int, val wet: Boolean)

    private fun hourLabel(h: Int, c24: Boolean) =
        if (c24) "%02d:00".format(h) else LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a", Locale.US)).lowercase()

    private fun toC(w: Weather, t: Int) = if (w.units.tempF) ((t - 32) * 5 / 9.0) else t.toDouble()

    private fun wet(h: Hour, pct: Int) = h.precip >= pct || h.mm >= 0.3

    /** "Light layers · umbrella until 2 pm" plus a supporting line. */
    fun wear(w: Weather, pct: Int = 50): Pair<String, String> {
        val f = toC(w, w.feels)
        val base = when {
            f >= 32 -> "Light, breathable clothes"
            f >= 26 -> "T-shirt weather"
            f >= 20 -> "Light layers"
            f >= 14 -> "Light jacket"
            f >= 8 -> "Jacket"
            else -> "Warm coat"
        }
        val next = w.hours.take(13)
        val c24 = w.units.clock24
        val extra = buildList {
            val first = next.indexOfFirst { wet(it, pct) }
            if (first == 0) {
                val dry = next.indexOfFirst { !wet(it, pct) }
                add(if (dry < 0) "umbrella all day" else "umbrella until ${hourLabel(next[dry].hour, c24)}")
            } else if (first > 0) add("umbrella from ${hourLabel(next[first].hour, c24)}")
            // a noticeably cooler evening deserves a layer
            val later = next.drop(4).minOfOrNull { toC(w, it.feels ?: it.temp) }
            if (later != null && f - later >= 5 && f >= 14) add("a layer for later")
        }
        val title = (listOf(base) + extra).joinToString(" · ")
        val sub = listOfNotNull(
            "Feels ${w.feels}°",
            w.uvPeak?.takeIf { it >= 3 }?.let { "UV peaks $it" + (w.uvPeakAt?.let { t -> " at $t" } ?: "") + if (it >= 6) " · sunscreen" else "" },
            w.wind.takeIf { w.windKmh >= 20 }?.let { "breezy, $it ${w.windUnit}" },
        ).joinToString(" · ")
        return title to sub
    }

    /** Weather at the user's two commute times, from the next 24 hours. */
    fun commute(w: Weather, times: List<String>, pct: Int): List<Commute> = times.mapNotNull { t ->
        val hr = t.substringBefore(":").toIntOrNull() ?: return@mapNotNull null
        val h = w.hours.drop(1).firstOrNull { it.hour == hr } ?: w.hours.firstOrNull { it.hour == hr } ?: return@mapNotNull null
        Commute(hourLabel(hr, w.units.clock24).let { if (w.units.clock24) t else it }, h.temp, h.precip, wet(h, pct))
    }

    /** Warnings derived from the forecast (not an official service). */
    fun warnings(w: Weather): List<Warn> {
        val c24 = w.units.clock24
        val next = w.hours.take(13)
        fun span(pred: (Hour) -> Boolean): String? {
            val a = next.indexOfFirst(pred); if (a < 0) return null
            val b = next.indexOfLast(pred)
            return if (a == b) "around ${hourLabel(next[a].hour, c24)}" else "${hourLabel(next[a].hour, c24)}–${hourLabel((next[b].hour + 1) % 24, c24)}"
        }
        return buildList {
            span { kindOf(it.code) == Kind.STORM }?.let { add(Warn("Thunderstorms likely $it", "From the forecast · stay indoors during lightning")) }
            val heavyHour = next.maxOfOrNull { it.mm } ?: 0.0
            if (w.rainToday >= 20 || heavyHour >= 7) {
                val s = span { it.mm >= 2.5 } ?: "today"
                add(Warn("Heavy rain likely $s", "From the forecast · about ${w.rainToday.roundToInt()} mm expected today"))
            }
            val hottest = next.maxOfOrNull { toC(w, it.feels ?: it.temp) } ?: toC(w, w.feels)
            if (hottest >= 40) add(Warn("Extreme heat", "Feels up to ${hottest.roundToInt()}°C · avoid the midday sun, drink water"))
            else if (hottest >= 37) add(Warn("Very hot afternoon", "Feels up to ${hottest.roundToInt()}°C · keep water handy"))
            if (w.windKmh >= 40) add(Warn("Strong wind", "${w.wind} ${w.windUnit}" + (w.gust?.let { ", gusts $it" } ?: "") + " · secure loose things"))
            w.aqi?.takeIf { it > 150 }?.let { add(Warn("Unhealthy air (AQI $it)", "Limit long outdoor effort · consider a mask")) }
            w.uvPeak?.takeIf { it >= 11 }?.let { add(Warn("Extreme UV ($it)", "Skin burns in minutes · shade from 11 to 4")) }
        }
    }

    /** One small fact from the days Mono has seen for this city. */
    fun fact(c: Context, place: Place, w: Weather): String? {
        val hist = History.read(c, place)
        val today = w.days.firstOrNull()?.date ?: return null
        val past = hist.filterKeys { it < today }.toSortedMap()
        if (past.size < 3) return null
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        fun since(d: String) = LocalDate.parse(d).format(fmt)
        if (w.rainToday >= 1) {
            val wetter = past.entries.lastOrNull { it.value.second >= w.rainToday }
            return if (wetter == null) "Wettest day in ${past.size} days" else if (wetter.key < LocalDate.parse(today).minusDays(3).toString()) "Wettest day since ${since(wetter.key)}" else null
        }
        val dry = past.entries.reversed().takeWhile { it.value.second < 0.2 }.count() + 1
        if (dry >= 3) return "$dry dry days in a row"
        val hi = w.days.first().max.toDouble()
        val warmer = past.entries.lastOrNull { it.value.first >= hi }
        return when {
            warmer == null -> "Warmest day in ${past.size} days"
            warmer.key < LocalDate.parse(today).minusDays(3).toString() -> "Warmest day since ${since(warmer.key)}"
            else -> null
        }
    }
}

/** Each city's daily high and rain total, kept for 60 days, so facts can compare today with the past. */
object History {
    private fun p(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun record(c: Context, place: Place, w: Weather) {
        val d = w.days.firstOrNull() ?: return
        if (d.date.isEmpty()) return
        val o = p(c).getString(place.key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        o.put(d.date, JSONObject().put("max", d.max).put("mm", d.mm))
        val cutoff = LocalDate.parse(d.date).minusDays(60).toString()
        o.keys().asSequence().toList().filter { it < cutoff }.forEach { o.remove(it) }
        p(c).edit().putString(place.key, o.toString()).apply()
    }

    fun read(c: Context, place: Place): Map<String, Pair<Int, Double>> {
        val o = p(c).getString(place.key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { it.optInt("max") to it.optDouble("mm") } }
    }
}
