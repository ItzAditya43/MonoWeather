package dev.aditya.monoweather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

/** All sources are normalised to WMO weather codes. */
fun kindOf(code: Int) = when (code) {
    0 -> Kind.CLEAR
    1, 2 -> Kind.PARTLY
    3 -> Kind.CLOUDY
    45, 48 -> Kind.FOG
    in 51..67, in 80..82 -> Kind.RAIN
    in 71..77, 85, 86 -> Kind.SNOW
    in 95..99 -> Kind.STORM
    else -> Kind.CLOUDY
}

fun labelOf(code: Int) = when (code) {
    0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
    61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
    71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
    80, 81 -> "Showers"; 82 -> "Heavy showers"; 85, 86 -> "Snow showers"
    95 -> "Thunderstorm"; 96, 99 -> "Hailstorm"
    else -> "—"
}

fun aqiLabel(aqi: Int) = when {
    aqi <= 50 -> "Good"; aqi <= 100 -> "Moderate"; aqi <= 150 -> "Unhealthy for some"
    aqi <= 200 -> "Unhealthy"; aqi <= 300 -> "Very unhealthy"; else -> "Hazardous"
}

fun uvLabel(uv: Int) = when {
    uv <= 2 -> "Low"; uv <= 5 -> "Moderate"; uv <= 7 -> "High"; uv <= 10 -> "Very high"; else -> "Extreme"
}

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val tz: String) {
    val key get() = "%.3f,%.3f".format(Locale.US, lat, lon)
}

// ---- what the UI draws ----
data class Hour(val label: String, val temp: Int, val code: Int, val isDay: Boolean, val precip: Int)
data class Day(val label: String, val max: Int, val min: Int, val code: Int, val precip: Int)

data class Weather(
    val place: String, val temp: Int, val feels: Int, val humidity: Int, val wind: Int,
    val code: Int, val isDay: Boolean, val hi: Int, val lo: Int,
    val sunrise: String, val sunset: String,
    val hours: List<Hour>, val days: List<Day>, val fetchedAt: Long,
    val source: String, val aqi: Int?, val uv: Int?,
    val dew: Int, val windDir: Int?, val gust: Int?,
    /** Rain in mm for each 15 minutes of the next 2 hours, starting at [nowStart]. Null when the source has no such data. */
    val now2h: List<Double>?, val nowStart: String?,
    val pm25: Int?, val pm10: Int?, val uvPeak: Int?, val uvPeakAt: String?,
    val zone: String,
    /** Today's high minus yesterday's high, when the source has yesterday. */
    val vsYesterday: Int?,
    val windKmh: Int, val imperial: Boolean,
    val goldenHour: String?, val daylight: String,
) {
    val windUnit get() = if (imperial) "mph" else "km/h"
    val kind get() = kindOf(code)
}

// ---- what every provider produces (times are local wall-clock "yyyy-MM-ddTHH:mm") ----
internal data class Cur(val temp: Double, val feels: Double, val humidity: Int, val wind: Double, val code: Int, val isDay: Boolean,
    val dew: Double? = null, val windDir: Int? = null, val gust: Double? = null)
internal data class HourPt(val time: String, val temp: Double, val code: Int, val precip: Int, val isDay: Boolean, val uv: Double?)
internal data class DayPt(val date: String, val max: Double, val min: Double, val code: Int, val precip: Int, val sunrise: String?, val sunset: String?, val uv: Double?)
internal data class Forecast(val zone: ZoneId, val cur: Cur, val hours: List<HourPt>, val days: List<DayPt>, val quarters: List<Pair<String, Double>>? = null)

internal interface Provider {
    val name: String
    fun fetch(p: Place): String
    fun parse(raw: String, p: Place): Forecast
}

object Repo {
    private fun prefs(c: Context) = c.getSharedPreferences("mono", Context.MODE_PRIVATE)
    private val providers = listOf(OpenMeteo, MetNorway, Wttr)

    // ---------- saved cities ----------
    fun places(c: Context): List<Place> {
        val p = prefs(c)
        val arr = p.getString("places", null)?.let { JSONArray(it) }
        if (arr == null) {
            // migrate the single city from v1
            val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return emptyList()
            val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return emptyList()
            val one = listOf(Place(p.getString("pname", null) ?: "Saved place", "", lat, lon, TimeZone.getDefault().id))
            savePlaces(c, one)
            return one
        }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"), o.optString("tz").ifBlank { TimeZone.getDefault().id })
        }
    }

    private fun savePlaces(c: Context, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.lat).put("lon", it.lon).put("tz", it.tz)) }
        prefs(c).edit().putString("places", arr.toString()).apply()
    }

    /** Adds (or finds) a city and returns its index. */
    fun addPlace(c: Context, place: Place): Int {
        val list = places(c).toMutableList()
        val i = list.indexOfFirst { it.key == place.key }
        if (i >= 0) return i
        list += place
        savePlaces(c, list)
        return list.lastIndex
    }

    fun makeHome(c: Context, place: Place) {
        val list = places(c)
        savePlaces(c, list.filter { it.key == place.key } + list.filterNot { it.key == place.key })
    }

    fun removePlace(c: Context, place: Place) {
        savePlaces(c, places(c).filterNot { it.key == place.key })
        prefs(c).edit().remove("cache_${place.key}").apply()
    }

    // ---------- forecast ----------
    fun cached(c: Context, place: Place): Weather? {
        val o = prefs(c).getString("cache_${place.key}", null)?.let { JSONObject(it) } ?: return null
        return runCatching { build(o, place, Prefs.imperial(c)) }.getOrNull()
    }

    /** Tries each source in order; the first one that answers wins. */
    suspend fun refresh(c: Context, place: Place): Weather = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (prov in providers) {
            val raw = runCatching { retry { prov.fetch(place) } }.onFailure { last = it }.getOrNull() ?: continue
            // make sure the answer is usable before trusting it
            if (runCatching { prov.parse(raw, place) }.onFailure { last = it }.isFailure) continue
            val air = runCatching { fetchAir(place) }.getOrNull()
            val o = JSONObject().put("src", prov.name).put("raw", raw).put("at", System.currentTimeMillis())
            if (air != null) o.put("air", air)
            prefs(c).edit().putString("cache_${place.key}", o.toString()).apply()
            return@withContext build(o, place, Prefs.imperial(c))
        }
        throw last ?: IllegalStateException("No weather source answered")
    }

    /** Turns a cached raw answer into what the UI shows, sliced to the current hour — so it stays right offline. */
    private fun build(o: JSONObject, place: Place, imperial: Boolean): Weather {
        val prov = providers.first { it.name == o.getString("src") }
        val f0 = prov.parse(o.getString("raw"), place)
        fun t(x: Double) = if (imperial) x * 9 / 5 + 32 else x
        val f = if (!imperial) f0 else f0.copy(
            cur = f0.cur.copy(temp = t(f0.cur.temp), feels = t(f0.cur.feels), dew = f0.cur.dew?.let(::t)),
            hours = f0.hours.map { it.copy(temp = t(it.temp)) },
            days = f0.days.map { it.copy(max = t(it.max), min = t(it.min)) },
        )
        val dewC = f0.cur.dew ?: dewPoint(f0.cur.temp, f0.cur.humidity)
        val nowLocal = LocalDateTime.now(f.zone).toString()
        val hourKey = nowLocal.take(13)
        val start = f.hours.indexOfLast { it.time.take(13) <= hourKey }.coerceAtLeast(0)
        val hours = f.hours.drop(start).take(24).mapIndexed { i, h ->
            Hour(if (i == 0) "Now" else h.time.substring(11, 16), h.temp.roundToInt(), h.code, h.isDay, h.precip)
        }
        val today = nowLocal.take(10)
        val days = f.days.filter { it.date >= today }.take(7).mapIndexed { n, d ->
            Day(
                if (n == 0) "Today" else LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                d.max.roundToInt(), d.min.roundToInt(), d.code, d.precip,
            )
        }
        val todayPt = f.days.firstOrNull { it.date >= today }
        val sun = todayPt?.let { d -> if (d.sunrise != null && d.sunset != null) d.sunrise to d.sunset else null }
            ?: sunTimes(LocalDate.parse(today), place.lat, place.lon, f.zone)
        val uvNow = f.hours.getOrNull(start)?.uv ?: todayPt?.uv
        val uvToday = f.hours.filter { it.time.startsWith(today) && it.uv != null }.maxByOrNull { it.uv!! }
        val quarterKey = nowLocal.take(14) + "%02d".format((nowLocal.substring(14, 16).toInt() / 15) * 15)
        val quarters = f.quarters?.filter { it.first.take(16) >= quarterKey }?.take(8)?.takeIf { it.size >= 4 }
        val air = o.optJSONObject("air")
        val c = f.cur
        return Weather(
            place = place.name,
            temp = f.cur.temp.roundToInt(), feels = f.cur.feels.roundToInt(),
            humidity = f.cur.humidity, wind = (if (imperial) f.cur.wind * 0.621371 else f.cur.wind).roundToInt(),
            code = f.cur.code, isDay = f.cur.isDay,
            hi = days.firstOrNull()?.max ?: f.cur.temp.roundToInt(), lo = days.firstOrNull()?.min ?: f.cur.temp.roundToInt(),
            sunrise = sun?.first ?: "—", sunset = sun?.second ?: "—",
            hours = hours, days = days, fetchedAt = o.getLong("at"),
            source = prov.name, aqi = air?.optInt("aqi", -1)?.takeIf { it >= 0 } ?: o.optInt("aqi", -1).takeIf { it >= 0 },
            uv = uvNow?.roundToInt(),
            dew = t(dewC).roundToInt(),
            windDir = c.windDir, gust = c.gust?.let { if (imperial) it * 0.621371 else it }?.roundToInt(),
            now2h = quarters?.map { it.second }, nowStart = quarters?.first()?.first?.substring(11, 16),
            pm25 = air?.optInt("pm25", -1)?.takeIf { it >= 0 }, pm10 = air?.optInt("pm10", -1)?.takeIf { it >= 0 },
            uvPeak = uvToday?.uv?.roundToInt(), uvPeakAt = uvToday?.time?.substring(11, 16),
            zone = f.zone.id,
            vsYesterday = f.days.firstOrNull { it.date == LocalDate.parse(today).minusDays(1).toString() }
                ?.let { y -> todayPt?.let { (it.max - y.max).roundToInt() } },
            windKmh = f0.cur.wind.roundToInt(), imperial = imperial,
            goldenHour = sunTimes(LocalDate.parse(today), place.lat, place.lon, f.zone, 84.0)?.second,
            daylight = sun?.let { (a, b) ->
                runCatching { val m = java.time.Duration.between(LocalTime.parse(a), LocalTime.parse(b)).toMinutes(); "${m / 60}h ${m % 60}m" }.getOrNull()
            } ?: "—",
        )
    }

    private fun fetchAir(p: Place): JSONObject {
        val c = JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}&longitude=${p.lon}&current=us_aqi,pm2_5,pm10"))
            .getJSONObject("current")
        return JSONObject().put("aqi", c.getDouble("us_aqi").roundToInt())
            .put("pm25", c.optDouble("pm2_5").takeUnless { it.isNaN() }?.roundToInt() ?: -1)
            .put("pm10", c.optDouble("pm10").takeUnless { it.isNaN() }?.roundToInt() ?: -1)
    }

    // ---------- search & location ----------
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + URLEncoder.encode(q, "UTF-8")))
        val a = o.optJSONArray("results") ?: return@withContext emptyList()
        (0 until a.length()).map {
            val r = a.getJSONObject(it)
            Place(
                r.getString("name"),
                listOf(r.optString("admin1"), r.optString("country")).filter { s -> s.isNotBlank() }.joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"),
                r.optString("timezone").ifBlank { TimeZone.getDefault().id },
            )
        }
    }

    /** One-shot GPS lookup, only when the user asks for it. Never used in the background. */
    suspend fun locateOnce(c: Context): Place? = withContext(Dispatchers.IO) {
        if (!c.getSystemService(LocationManager::class.java).isLocationEnabled) error("Location is off. Turn it on or search a city.")
        runCatching { locate(c) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun locate(c: Context): Place? {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = fresh(c, lm) ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return Place(cityName(c, loc), "", loc.latitude, loc.longitude, TimeZone.getDefault().id)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(c: Context, lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < 30) return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching { lm.getCurrentLocation(provider, signal, c.mainExecutor) { cont.resume(it) } }
                    .onFailure { cont.resume(null) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun cityName(c: Context, loc: Location) = runCatching {
        Geocoder(c, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull() ?: "My location"

    // ---------- plumbing ----------
    private suspend fun <T> retry(block: () -> T): T {
        runCatching { return block() }
        delay(700)
        return block()
    }

    const val UA = "MonoWeather/1.0 (Android personal weather app)"

    fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 7000
        con.readTimeout = 9000
        con.setRequestProperty("User-Agent", UA)
        try {
            if (con.responseCode !in 200..299) error("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }
}

// ================= sources =================

internal object OpenMeteo : Provider {
    override val name = "Open-Meteo"
    override fun fetch(p: Place) = Repo.get(
        "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day,dew_point_2m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day,uv_index" +
            "&minutely_15=precipitation&forecast_minutely_15=12" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max" +
            "&timezone=auto&forecast_days=8&past_days=1"
    )

    override fun parse(raw: String, p: Place): Forecast {
        val o = JSONObject(raw)
        val c = o.getJSONObject("current"); val h = o.getJSONObject("hourly"); val d = o.getJSONObject("daily")
        val zone = runCatching { ZoneId.of(o.getString("timezone")) }.getOrElse { ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds")) }
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            HourPt(
                ht.getString(i), h.getJSONArray("temperature_2m").getDouble(i), h.getJSONArray("weather_code").getInt(i),
                h.getJSONArray("precipitation_probability").optInt(i), h.getJSONArray("is_day").getInt(i) == 1,
                h.optJSONArray("uv_index")?.optDouble(i)?.takeUnless { it.isNaN() },
            )
        }
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            DayPt(
                dt.getString(i), d.getJSONArray("temperature_2m_max").getDouble(i), d.getJSONArray("temperature_2m_min").getDouble(i),
                d.getJSONArray("weather_code").getInt(i), d.getJSONArray("precipitation_probability_max").optInt(i),
                d.getJSONArray("sunrise").getString(i).substring(11), d.getJSONArray("sunset").getString(i).substring(11),
                d.optJSONArray("uv_index_max")?.optDouble(i)?.takeUnless { it.isNaN() },
            )
        }
        val cur = Cur(
            c.getDouble("temperature_2m"), c.getDouble("apparent_temperature"), c.getInt("relative_humidity_2m"),
            c.getDouble("wind_speed_10m"), c.getInt("weather_code"), c.getInt("is_day") == 1,
            c.optDouble("dew_point_2m").takeUnless { it.isNaN() }, c.optDouble("wind_direction_10m").takeUnless { it.isNaN() }?.roundToInt(),
            c.optDouble("wind_gusts_10m").takeUnless { it.isNaN() },
        )
        val quarters = o.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time"); val pr = m.getJSONArray("precipitation")
            (0 until t.length()).map { t.getString(it) to pr.optDouble(it, 0.0).let { v -> if (v.isNaN()) 0.0 else v } }
        }
        return Forecast(zone, cur, hours, days, quarters)
    }
}

/** Norwegian Meteorological Institute. Global, free, needs only a User-Agent. */
internal object MetNorway : Provider {
    override val name = "MET Norway"
    override fun fetch(p: Place) = Repo.get(
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${"%.4f".format(Locale.US, p.lat)}&lon=${"%.4f".format(Locale.US, p.lon)}"
    )

    private fun code(symbol: String): Int {
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> 95
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            s == "fog" -> 45
            "sleet" in s -> 66
            "snow" in s -> if ("heavy" in s) 75 else if ("light" in s) 71 else 73
            "showers" in s -> if ("heavy" in s) 82 else 80
            "rain" in s -> if ("heavy" in s) 65 else if ("light" in s) 61 else 63
            else -> 3
        }
    }

    private fun chance(mm: Double) = when { mm >= 2 -> 90; mm >= 0.5 -> 70; mm > 0.05 -> 40; else -> 0 }

    override fun parse(raw: String, p: Place): Forecast {
        val zone = ZoneId.of(p.tz)
        val ts = JSONObject(raw).getJSONObject("properties").getJSONArray("timeseries")
        val hours = mutableListOf<HourPt>()
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i)
            val local = Instant.parse(t.getString("time")).atZone(zone).toLocalDateTime()
            val data = t.getJSONObject("data")
            val next = data.optJSONObject("next_1_hours") ?: data.optJSONObject("next_6_hours") ?: continue
            val symbol = next.getJSONObject("summary").getString("symbol_code")
            val mm = next.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
            val sun = sunTimes(local.toLocalDate(), p.lat, p.lon, zone)
            val isDay = when {
                symbol.endsWith("_day") -> true
                symbol.endsWith("_night") || symbol.endsWith("_polartwilight") -> false
                sun != null -> local.toLocalTime().let { it >= LocalTime.parse(sun.first) && it < LocalTime.parse(sun.second) }
                else -> true
            }
            val inst = data.getJSONObject("instant").getJSONObject("details")
            hours += HourPt(local.format(FMT), inst.getDouble("air_temperature"), code(symbol), chance(mm), isDay, null)
        }
        val first = ts.getJSONObject(0).getJSONObject("data").getJSONObject("instant").getJSONObject("details")
        val h0 = hours.first()
        val wind = first.optDouble("wind_speed", 0.0) * 3.6
        val cur = Cur(first.getDouble("air_temperature"), first.getDouble("air_temperature"), first.optDouble("relative_humidity", 0.0).roundToInt(), wind, h0.code, h0.isDay,
            windDir = first.optDouble("wind_from_direction").takeUnless { it.isNaN() }?.roundToInt())
        return Forecast(zone, cur, hours, aggregate(hours, p, zone))
    }
}

/** wttr.in (World Weather Online data). Last resort: 3-day, 3-hourly. */
internal object Wttr : Provider {
    override val name = "wttr.in"
    override fun fetch(p: Place) = Repo.get("https://wttr.in/${"%.4f".format(Locale.US, p.lat)},${"%.4f".format(Locale.US, p.lon)}?format=j1")

    private fun code(w: Int) = when (w) {
        113 -> 0; 116 -> 2; 119, 122 -> 3
        143, 248, 260 -> 45
        176, 263, 266, 293, 296, 353 -> 61
        299, 302, 356 -> 63
        305, 308, 359 -> 65
        281, 284, 311, 314, 182, 185, 317, 320, 350, 362, 365, 374, 377 -> 66
        179, 227, 323, 326, 368 -> 71
        329, 332, 371 -> 73
        230, 335, 338 -> 75
        200, 386, 389, 392, 395 -> 95
        else -> 3
    }

    private val AMPM = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

    override fun parse(raw: String, p: Place): Forecast {
        val zone = ZoneId.of(p.tz)
        val o = JSONObject(raw)
        val c = o.getJSONArray("current_condition").getJSONObject(0)
        val wd = o.getJSONArray("weather")
        val hours = mutableListOf<HourPt>()
        val days = mutableListOf<DayPt>()
        for (i in 0 until wd.length()) {
            val d = wd.getJSONObject(i)
            val date = d.getString("date")
            val astro = d.getJSONArray("astronomy").getJSONObject(0)
            val rise = runCatching { LocalTime.parse(astro.getString("sunrise"), AMPM) }.getOrNull()
            val set = runCatching { LocalTime.parse(astro.getString("sunset"), AMPM) }.getOrNull()
            val hs = d.getJSONArray("hourly")
            var dayCode = 0
            var dayPrecip = 0
            for (j in 0 until hs.length()) {
                val h = hs.getJSONObject(j)
                val t = LocalTime.of(h.getString("time").toInt() / 100, 0)
                val wc = code(h.getString("weatherCode").toInt())
                val pr = maxOf(h.optString("chanceofrain").toIntOrNull() ?: 0, h.optString("chanceofsnow").toIntOrNull() ?: 0)
                val isDay = if (rise != null && set != null) t >= rise && t < set else t.hour in 6..17
                hours += HourPt("${date}T${t}", h.getString("tempC").toDouble(), wc, pr, isDay, h.optString("uvIndex").toDoubleOrNull())
                if (t.hour in 9..18 && severity(wc) > severity(dayCode)) dayCode = wc
                dayPrecip = maxOf(dayPrecip, pr)
            }
            days += DayPt(date, d.getString("maxtempC").toDouble(), d.getString("mintempC").toDouble(), dayCode, dayPrecip,
                rise?.toString(), set?.toString(), d.optString("uvIndex").toDoubleOrNull())
        }
        val nowT = LocalTime.now(zone)
        val today = days.firstOrNull()
        val isDay = if (today?.sunrise != null && today.sunset != null) nowT >= LocalTime.parse(today.sunrise) && nowT < LocalTime.parse(today.sunset) else nowT.hour in 6..17
        val cur = Cur(
            c.getString("temp_C").toDouble(), c.getString("FeelsLikeC").toDouble(), c.getString("humidity").toInt(),
            c.getString("windspeedKmph").toDouble(), code(c.getString("weatherCode").toInt()), isDay,
            windDir = c.optString("winddirDegree").toIntOrNull(),
        )
        return Forecast(zone, cur, hours, days)
    }
}

private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

private fun severity(code: Int) = when (kindOf(code)) {
    Kind.CLEAR -> 0; Kind.PARTLY -> 1; Kind.CLOUDY -> 2; Kind.FOG -> 3; Kind.SNOW -> 4; Kind.RAIN -> 5; Kind.STORM -> 6
}

/** Builds daily summaries from hourly points for sources that don't provide them. */
private fun aggregate(hours: List<HourPt>, p: Place, zone: ZoneId): List<DayPt> =
    hours.groupBy { it.time.take(10) }.map { (date, hs) ->
        val daytime = hs.filter { it.time.substring(11, 13).toInt() in 9..18 }.ifEmpty { hs }
        val sun = sunTimes(LocalDate.parse(date), p.lat, p.lon, zone)
        DayPt(date, hs.maxOf { it.temp }, hs.minOf { it.temp }, daytime.maxBy { severity(it.code) }.code,
            hs.maxOf { it.precip }, sun?.first, sun?.second, null)
    }

/** Magnus formula, for sources that don't report dew point. */
fun dewPoint(t: Double, rh: Int): Double {
    val g = kotlin.math.ln(rh.coerceIn(1, 100) / 100.0) + 17.62 * t / (243.12 + t)
    return 243.12 * g / (17.62 - g)
}

/** NOAA approximation; good to about a minute. */
fun sunTimes(date: LocalDate, lat: Double, lon: Double, zone: ZoneId, zenith: Double = 90.833): Pair<String, String>? {
    val g = 2 * PI / 365 * (date.dayOfYear - 1)
    val eqt = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
    val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
    val latR = Math.toRadians(lat)
    val cosHa = cos(Math.toRadians(zenith)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
    if (cosHa < -1 || cosHa > 1) return null
    val ha = Math.toDegrees(acos(cosHa))
    fun at(min: Double) = date.atStartOfDay(ZoneOffset.UTC).plusSeconds((min * 60).toLong())
        .withZoneSameInstant(zone).toLocalTime().toString().take(5)
    return at(720 - 4 * (lon + ha) - eqt) to at(720 - 4 * (lon - ha) - eqt)
}

/** Moon phase from the mean synodic month; good to within about a day. Returns (name, % lit, days to next full moon). */
fun moonPhase(date: LocalDate): Triple<String, Int, Int> {
    val synodic = 29.530588853
    val age = ((date.toEpochDay() - LocalDate.of(2000, 1, 6).toEpochDay() - 0.24) % synodic + synodic) % synodic
    val lit = ((1 - cos(2 * PI * age / synodic)) / 2 * 100).roundToInt()
    val name = when {
        age < 1.0 || age > 28.5 -> "New moon"
        age < 6.4 -> "Waxing crescent"
        age < 8.4 -> "First quarter"
        age < 13.8 -> "Waxing gibbous"
        age < 15.8 -> "Full moon"
        age < 21.1 -> "Waning gibbous"
        age < 23.1 -> "Last quarter"
        else -> "Waning crescent"
    }
    val toFull = ((14.765 - age + synodic) % synodic).let { if (it > synodic - 1) 0.0 else it }.roundToInt()
    return Triple(name, lit, toFull)
}
