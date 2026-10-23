package dev.aditya.monoweather

import org.junit.Assert.assertTrue
import org.junit.Test

/** Live check: every source must fetch and parse into a usable forecast. Needs network. */
class ProvidersTest {
    private val mumbai = Place("Mumbai", "", 19.07, 72.88, "Asia/Kolkata")

    private fun check(p: Provider) {
        val f = p.parse(p.fetch(mumbai), mumbai)
        println("${p.name}: now ${f.cur.temp}° code ${f.cur.code} day=${f.cur.isDay} · ${f.hours.size} hours · ${f.days.size} days · " +
            "first day ${f.days.first().date} ${f.days.first().min}-${f.days.first().max} sun ${f.days.first().sunrise}/${f.days.first().sunset}")
        assertTrue(f.hours.size >= 8)
        assertTrue(f.days.size >= 3)
        assertTrue(f.cur.temp in -60.0..60.0)
    }

    @Test fun openMeteo() {
        check(OpenMeteo)
        val f = OpenMeteo.parse(OpenMeteo.fetch(mumbai), mumbai)
        println("Open-Meteo extras: dew ${f.cur.dew} dir ${f.cur.windDir} gust ${f.cur.gust} quarters ${f.quarters?.size} yesterday ${f.days.first().date}")
        assertTrue(f.cur.dew != null && f.cur.windDir != null)
        assertTrue((f.quarters?.size ?: 0) >= 8)
    }

    @Test fun moon() {
        // 2026-10-26 is a full moon; 2026-11-09 a new moon
        val full = moonPhase(java.time.LocalDate.of(2026, 10, 26)); val new = moonPhase(java.time.LocalDate.of(2026, 11, 9))
        println("moon: $full / $new")
        assertTrue(full.second > 95 && new.second < 5)
    }

    @Test fun dew() = assertTrue(kotlin.math.abs(dewPoint(30.0, 70) - 23.9) < 0.3)
    @Test fun metNorway() = check(MetNorway)
    @Test fun wttr() = check(Wttr)
}
