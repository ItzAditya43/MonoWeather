package dev.aditya.monoweather

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = androidx.compose.ui.text.font.FontFamily(
    listOf(100, 200, 300, 400, 500, 600).map {
        androidx.compose.ui.text.font.Font(
            R.font.inter_tight, FontWeight(it),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(it)),
        )
    }
)

private val Dim = Ink.copy(alpha = 0.58f)
private val Faint = Ink.copy(alpha = 0.10f)
private val Hair = Ink.copy(alpha = 0.08f)
private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

/** The palette of the page on screen (animated) and the chosen theme. */
private val LocalPal = staticCompositionLocalOf { paletteOf(Kind.CLOUDY, true) }
private val LocalTheme = staticCompositionLocalOf { Theme.MINIMALIST }

// ---------- scales for the hold-to-expand cards (single hue, faint → strong) ----------
private data class Band(val from: Int, val to: Int, val name: String, val advice: String)

private val AQI = listOf(
    Band(0, 50, "Good", "Air is clean. Great for outdoor runs."),
    Band(51, 100, "Moderate", "Fine for most. Very sensitive people may want shorter outdoor time."),
    Band(101, 150, "Unhealthy for some", "Asthma or heart conditions: limit long outdoor effort."),
    Band(151, 200, "Unhealthy", "Everyone: cut back on long outdoor exertion."),
    Band(201, 300, "Very unhealthy", "Stay indoors if you can; wear an N95 outside."),
    Band(301, 500, "Hazardous", "Avoid going out."),
)
private val UVB = listOf(
    Band(0, 2, "Low", "No protection needed."),
    Band(3, 5, "Moderate", "Sunglasses and sunscreen around midday."),
    Band(6, 7, "High", "SPF 30+, hat, shade from 11 to 4."),
    Band(8, 10, "Very high", "Avoid midday sun. Cover up."),
    Band(11, 14, "Extreme", "Stay in shade. Skin burns in minutes."),
)
private val HUM = listOf(
    Band(0, 30, "Dry", "Dry air. Keep water handy."),
    Band(31, 60, "Comfortable", "Comfortable."),
    Band(61, 80, "Humid", "Sticky. Sweat evaporates slowly."),
    Band(81, 100, "Very humid", "Muggy and heavy. Feels warmer than it is."),
)
private val WINDB = listOf(
    Band(0, 5, "Calm", "Smoke rises straight."),
    Band(6, 19, "Light breeze", "Leaves rustle. Pleasant."),
    Band(20, 38, "Breezy", "Small branches move. Hold onto umbrellas."),
    Band(39, 61, "Strong", "Walking against it is hard."),
    Band(62, 120, "Gale", "Stay indoors. Branches break."),
)

private fun bandOf(t: List<Band>, v: Int) = t.firstOrNull { v <= it.to } ?: t.last()

/** Marker position: placed inside its band, so every band gets equal width on the scale. */
private fun posOf(t: List<Band>, v: Int): Float {
    val b = bandOf(t, v); val i = t.indexOf(b)
    val f = ((v - b.from).toFloat() / (b.to - b.from).coerceAtLeast(1)).coerceIn(0f, 1f)
    return (i + f) / t.size
}

private fun stepAlpha(i: Int, n: Int) = 0.18f + 0.82f * i / (n - 1)

private fun compass(deg: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg % 360 + 22) / 45) % 8]

internal fun nowText(w: Weather): String {
    val q = w.now2h
    if (q == null || w.nowStart == null) {
        val p = w.hours.drop(1).take(2).maxOfOrNull { it.precip } ?: 0
        return if (p >= 50) "Rain likely within 2 hours ($p%)" else "No rain expected for 2 hours"
    }
    val wet = { v: Double -> v >= 0.05 }
    val start = runCatching { LocalTime.parse(w.nowStart) }.getOrDefault(LocalTime.now())
    fun at(i: Int) = start.plusMinutes(15L * i).toString()
    val peak = q.max()
    val word = when { peak < 0.6 -> "Light rain"; peak < 2 -> "Rain"; else -> "Heavy rain" }
    return when {
        q.none(wet) -> "No rain expected for 2 hours"
        wet(q[0]) -> q.indexOfFirst { !wet(it) }.let { if (it < 0) "$word for the next 2 hours" else "$word now, easing by ${at(it)}" }
        else -> "$word likely from ${at(q.indexOfFirst(wet))}"
    }
}

/** Card surface: tinted gradient in Minimalist, hairline glass on black in Midnight. */
@Composable
private fun Modifier.surface(r: Dp = 22.dp): Modifier {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(r)
    return if (LocalTheme.current == Theme.MIDNIGHT)
        background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f), Color.White.copy(alpha = 0.008f))), shape).border(1.dp, Hair, shape)
    else
        background(Brush.verticalGradient(listOf(pal.top.copy(alpha = 0.55f), pal.bottom.copy(alpha = 0.7f))), shape)
            .border(1.dp, Brush.verticalGradient(listOf(pal.accent.copy(alpha = 0.22f), pal.accent.copy(alpha = 0.04f))), shape)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainVm) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var searching by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, Weather>?>(null) }
    val hasPerm = { ctx.checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.locateOnce() else if (vm.state.value.cities.isEmpty()) searching = true
    }
    LaunchedEffect(Unit) { if (vm.state.value.cities.isEmpty()) searching = true }

    val pager = rememberPagerState { s.cities.size }
    LaunchedEffect(Unit) { vm.jumpTo.collect { pager.animateScrollToPage(it) } }
    LaunchedEffect(pager.settledPage) { if (s.cities.size > 1) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    val w = s.cities.getOrNull(pager.currentPage)?.weather
    val kind = w?.kind ?: Kind.CLOUDY
    val isDay = w?.isDay ?: true
    val target = paletteFor(s.theme, kind, isDay)
    val accent by animateColorAsState(target.accent, tween(900), label = "accent")
    val top by animateColorAsState(target.top, tween(900), label = "top")
    val bottom by animateColorAsState(target.bottom, tween(900), label = "bottom")
    val pal = target.copy(accent = accent, top = top, bottom = bottom)

    CompositionLocalProvider(LocalPal provides pal, LocalTheme provides s.theme, androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = Inter)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().blur(if (detail != null) 18.dp else 0.dp)) {
                Crossfade(Triple(kind, isDay, s.theme), animationSpec = tween(1200), label = "scene") { (k, d, t) -> Scene(k, d, t) }
                if (s.cities.isEmpty()) {
                    Welcome(s, onSearch = { searching = true }, onLocate = { if (hasPerm()) vm.locateOnce() else perm.launch(COARSE) })
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        val city = s.cities[page]
                        PullToRefreshBox(
                            isRefreshing = city.loading && city.weather != null,
                            onRefresh = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.refresh(city.place.key, force = true) },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            CityPage(
                                city, page, s.cities.size, s.theme,
                                onSearch = { searching = true },
                                onRetry = { vm.refresh(city.place.key, force = true) },
                                onHold = { key -> city.weather?.let { haptic.performHapticFeedback(HapticFeedbackType.LongPress); detail = key to it } },
                                onTheme = vm::setTheme,
                                imperial = s.imperial, onImperial = vm::setImperial,
                            )
                        }
                    }
                }
            }
            s.notice?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) { vm.clearNotice() } }
            AnimatedVisibility(detail != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                        .clickable(remember { MutableInteractionSource() }, null) { detail = null },
                    contentAlignment = Alignment.Center,
                ) {
                    detail?.let { (key, wx) ->
                        AnimatedVisibility(
                            true,
                            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 400f), initialScale = 0.82f) + fadeIn(),
                            exit = scaleOut() + fadeOut(),
                        ) { DetailCard(key, wx) }
                    }
                }
            }
            if (detail != null) BackHandler { detail = null }
            AnimatedVisibility(
                searching,
                enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 10 },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 10 },
            ) {
                SearchSheet(
                    cities = s.cities,
                    onPick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); searching = false; vm.add(it) },
                    onRemove = vm::remove,
                    onHome = vm::makeHome,
                    onGps = { searching = false; if (hasPerm()) vm.locateOnce() else perm.launch(COARSE) },
                    onClose = { searching = false },
                )
            }
        }
    }
}

@Composable
private fun Dots(n: Int, current: Int) {
    val pal = LocalPal.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { i ->
            Box(
                Modifier.height(5.dp).width(if (i == current) 14.dp else 5.dp)
                    .background(if (i == current) pal.accent else Faint, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier, onDone: () -> Unit) {
    LaunchedEffect(text) { delay(3500); onDone() }
    Text(
        text, color = Ink, fontSize = 14.sp,
        modifier = modifier.systemBarsPadding().padding(24.dp)
            .background(Color(0xE6141414), RoundedCornerShape(16.dp)).border(1.dp, Faint, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** Staggered fade-up on first appearance. */
@Composable
private fun Modifier.reveal(i: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(i * 60L); p.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    return graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 40f }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = LocalPal.current.accent.copy(alpha = 0.78f), fontSize = 10.5.sp, letterSpacing = 1.6.sp, modifier = modifier)
}

@Composable
private fun CityPage(
    city: City, page: Int, count: Int, theme: Theme,
    onSearch: () -> Unit, onRetry: () -> Unit, onHold: (String) -> Unit, onTheme: (Theme) -> Unit,
    imperial: Boolean, onImperial: (Boolean) -> Unit,
) {
    val w = city.weather
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().systemBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).reveal(0), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(city.place.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (city.place.detail.isNotEmpty()) Text(city.place.detail.substringBefore(","), color = Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                if (count > 1) Dots(count, page)
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search and manage cities", tint = Ink) }
            }
        }
        if (w == null) {
            item { PageEmpty(city, onRetry) }
            return@LazyColumn
        }
        item { Hero(w, city.error) }
        item { Card(4) { Label("Next 2 hours", Modifier.padding(start = 16.dp, bottom = 10.dp)); NowCast(w) } }
        item { Card(5) { Label("Next 24 hours", Modifier.padding(start = 16.dp, bottom = 10.dp)); HourlyGraph(w.hours) } }
        item {
            Column(Modifier.reveal(6), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldTile("aqi", "Air quality", w.aqi?.toString() ?: "—", w.aqi?.let { bandOf(AQI, it).name }, AQI, w.aqi, Modifier.weight(1f), onHold)
                    HoldTile("uv", "UV index", w.uv?.toString() ?: "—", w.uv?.let { bandOf(UVB, it).name }, UVB, w.uv, Modifier.weight(1f), onHold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoldTile("hum", "Humidity", "${w.humidity}%", bandOf(HUM, w.humidity).name, HUM, w.humidity, Modifier.weight(1f), onHold)
                    HoldTile(
                        "wind", "Wind", "${w.wind} ${w.windUnit}",
                        bandOf(WINDB, w.windKmh).name + (w.windDir?.let { " · " + compass(it) } ?: ""), WINDB, w.windKmh, Modifier.weight(1f), onHold,
                    )
                }
                Text("Hold a tile for the full scale", color = Ink.copy(alpha = 0.3f), fontSize = 10.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        item { Card(7) { Label("7 days", Modifier.padding(start = 16.dp, bottom = 6.dp)); Daily(w.days) } }
        item { Card(8) { RadarCard(city.place, w, LocalPal.current, theme) { Label("Rain radar") } } }
        item { Card(9) { Label("Sun & moon", Modifier.padding(start = 16.dp, bottom = 10.dp)); SunMoon(w) } }
        item { Card(10) { Label("Settings", Modifier.padding(start = 16.dp, bottom = 6.dp)); Settings(theme, onTheme, imperial, onImperial) } }
        item { Card(11) { Label("Widgets", Modifier.padding(start = 16.dp, bottom = 6.dp)); WidgetGallery(w) } }
        item {
            Text(
                "Source · ${w.source}  ·  backups: " + listOf("Open-Meteo", "MET Norway", "wttr.in").filter { it != w.source }.joinToString(", "),
                color = Ink.copy(alpha = 0.28f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Card(i: Int, body: @Composable () -> Unit) {
    Column(Modifier.reveal(i).fillMaxWidth().surface().padding(vertical = 14.dp)) { body() }
}

@Composable
private fun Hero(w: Weather, error: String?) {
    val pal = LocalPal.current
    val temp = remember { Animatable(0f) }
    LaunchedEffect(w.temp) { temp.animateTo(w.temp.toFloat(), tween(1300, easing = FastOutSlowInEasing)) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val mins = ((now - w.fetchedAt) / 60_000).coerceAtLeast(0)
    val ago = if (mins < 1) "just now" else if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
    Row(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                "${temp.value.roundToInt()}°", fontSize = 96.sp, fontWeight = FontWeight.Thin, lineHeight = 92.sp,
                style = TextStyle(brush = Brush.verticalGradient(listOf(Color.White, pal.accent))),
                modifier = Modifier.reveal(1),
            )
            Text(labelOf(w.code), color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.reveal(2))
            Text("H ${w.hi}°   L ${w.lo}°   Feels ${w.feels}°", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp).reveal(2))
            w.vsYesterday?.let { d ->
                Text(
                    when { d > 0 -> "$d° warmer than yesterday"; d < 0 -> "${-d}° cooler than yesterday"; else -> "Same as yesterday" },
                    color = pal.accent, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp).reveal(2),
                )
            }
        }
        Text(
            if (error != null) "Offline\nsaved $ago" else "Updated\n$ago",
            color = Ink.copy(alpha = 0.32f), fontSize = 11.sp, lineHeight = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.reveal(3),
        )
    }
}

@Composable
private fun NowCast(w: Weather) {
    val pal = LocalPal.current
    Text(nowText(w), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    val q = w.now2h ?: return
    val mx = maxOf(1.5, q.max())
    val grow = remember { Animatable(0f) }
    LaunchedEffect(q) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        q.forEach { v ->
            Box(
                Modifier.weight(1f).fillMaxHeight(((v / mx).toFloat() * grow.value).coerceAtLeast(0.05f))
                    .background(Brush.verticalGradient(listOf(pal.accent, pal.accent.copy(alpha = 0.25f))), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("Now", "+30m", "+1h", "+1.5h", "+2h").forEach { Text(it, color = Dim, fontSize = 10.sp) }
    }
}

@Composable
private fun HourlyGraph(hours: List<Hour>) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val cell = 56.dp
    val lo = hours.minOfOrNull { it.temp } ?: 0
    val span = ((hours.maxOfOrNull { it.temp } ?: 1) - lo).coerceAtLeast(1).toFloat()
    Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
        Row {
            hours.forEachIndexed { i, h ->
                Column(
                    Modifier.width(cell)
                        .background(if (i == 0) pal.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(18.dp))
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(h.label, color = if (i == 0) Ink else Dim, fontSize = 11.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal)
                    Glyph(kindOf(h.code), h.isDay, 22.dp, if (i == 0) pal.accent else Ink)
                    Text(if (h.precip >= 20) "${h.precip}%" else " ", color = pal.accent, fontSize = 10.sp)
                    Spacer(Modifier.height(62.dp))
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val cw = cell.toPx()
            val bottom = size.height - 16.dp.toPx()
            val top = bottom - 30.dp.toPx()
            val pts = hours.mapIndexed { i, h -> Offset(cw * i + cw / 2, bottom - (h.temp - lo) / span * (bottom - top)) }
            if (pts.isEmpty()) return@Canvas
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]; val mx = (a.x + b.x) / 2
                    cubicTo(mx, a.y, mx, b.y, b.x, b.y)
                }
            }
            val fill = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(pts.first().x, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(pal.accent.copy(alpha = 0.25f), Color.Transparent), startY = top, endY = size.height))
            drawPath(line, pal.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val style = TextStyle(color = Ink, fontSize = 12.sp, fontFamily = Inter)
            pts.forEachIndexed { i, p ->
                drawCircle(Color.Black, 4.5.dp.toPx(), p)
                drawCircle(if (i == 0) Color.White else pal.accent, 3.dp.toPx(), p)
                val r = measurer.measure("${hours[i].temp}°", style)
                drawText(r, topLeft = Offset(p.x - r.size.width / 2f, p.y - r.size.height - 5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun Daily(days: List<Day>) {
    val pal = LocalPal.current
    val lo = days.minOfOrNull { it.min } ?: 0
    val hi = days.maxOfOrNull { it.max } ?: 1
    val span = (hi - lo).coerceAtLeast(1).toFloat()
    Column(Modifier.padding(horizontal = 16.dp)) {
        days.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = Hair)
            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, color = Ink, fontSize = 14.sp, modifier = Modifier.width(54.dp))
                Glyph(kindOf(d.code), true, 20.dp)
                Text(if (d.precip >= 20) "${d.precip}%" else "", color = pal.accent, fontSize = 10.5.sp, modifier = Modifier.width(38.dp).padding(start = 6.dp))
                Text("${d.min}°", color = Dim, fontSize = 14.sp, modifier = Modifier.width(32.dp))
                Canvas(Modifier.weight(1f).height(4.dp)) {
                    val y = size.height / 2
                    drawLine(Faint, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                    val x0 = size.width * (d.min - lo) / span
                    val x1 = size.width * (d.max - lo) / span
                    drawLine(Brush.horizontalGradient(listOf(pal.accent.copy(alpha = 0.4f), pal.accent), x0, x1), Offset(x0, y), Offset(x1, y), size.height, StrokeCap.Round)
                }
                Text("${d.max}°", color = Ink, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(36.dp))
            }
        }
    }
}

/** Stepped single-colour scale with a white marker. */
@Composable
private fun SegBar(n: Int, pos: Float, height: Dp, marker: Dp) {
    val pal = LocalPal.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(marker), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(n) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(pal.accent.copy(alpha = stepAlpha(i, n)), RoundedCornerShape(2.dp))) }
        }
        val x = maxWidth * pos.coerceIn(0.01f, 0.99f) - marker / 2
        Box(Modifier.offset(x = x).size(marker).background(Color.Black, CircleShape).padding(marker / 6).background(Color.White, CircleShape))
    }
}

@Composable
private fun HoldTile(key: String, label: String, value: String, note: String?, scale: List<Band>, v: Int?, modifier: Modifier, onHold: (String) -> Unit) {
    val press = remember { Animatable(1f) }
    LaunchedEffect(Unit) {}
    Column(
        modifier.graphicsLayer { scaleX = press.value; scaleY = press.value }.surface(20.dp)
            .pointerInput(key) {
                detectTapGestures(
                    onPress = { press.animateTo(0.97f, tween(120)); tryAwaitRelease(); press.animateTo(1f, tween(160)) },
                    onLongPress = { onHold(key) },
                )
            }
            .padding(14.dp)
    ) {
        Label(label)
        Text(value, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 4.dp))
        Text(note ?: " ", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        SegBar(scale.size, v?.let { posOf(scale, it) } ?: 0f, 3.dp, 10.dp)
    }
}

@Composable
private fun PlainTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.surface(20.dp).padding(14.dp)) {
        Label(label)
        Text(value, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DetailCard(key: String, w: Weather) {
    val pal = LocalPal.current
    data class Spec(val title: String, val bands: List<Band>, val v: Int, val shown: String, val ticks: List<String>, val extra: String, val unit: String = "")
    val spec = when (key) {
        "aqi" -> Spec("Air quality · US AQI", AQI, w.aqi ?: 0, "${w.aqi ?: "—"}", listOf("0", "50", "100", "150", "200", "300", "500"),
            listOfNotNull(w.pm25?.let { "PM2.5 $it µg/m³" }, w.pm10?.let { "PM10 $it µg/m³" }).joinToString(" · "))
        "uv" -> Spec("UV index", UVB, w.uv ?: 0, "${w.uv ?: "—"}", listOf("0", "3", "6", "8", "11+"),
            if (w.uvPeak != null && w.uvPeakAt != null) "Peak today: ${w.uvPeak} at ${w.uvPeakAt}" else "")
        "hum" -> Spec("Humidity", HUM, w.humidity, "${w.humidity}%", listOf("0%", "30%", "60%", "80%", "100%"),
            "Dew point ${w.dew}° · feels muggy above ${if (w.imperial) 68 else 20}°", "%")
        else -> Spec("Wind", WINDB, w.windKmh, "${w.wind} ${w.windUnit}", listOf("0", "6", "20", "39", "62+ km/h"),
            listOfNotNull(w.windDir?.let { "From the ${compassLong(it)}" }, w.gust?.let { "gusts $it ${w.windUnit}" }).joinToString(" · "))
    }
    val band = bandOf(spec.bands, spec.v)
    Column(
        Modifier.padding(18.dp).fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))), RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(28.dp))
            .clickable(remember { MutableInteractionSource() }, null) {}
            .padding(22.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label(spec.title)
                Text(spec.shown, color = Ink, fontSize = 54.sp, fontWeight = FontWeight.ExtraLight, lineHeight = 58.sp, modifier = Modifier.padding(top = 6.dp))
                Text(band.name, color = Ink, fontSize = 18.sp)
            }
            if (key == "wind" && w.windDir != null) Compass(w.windDir)
        }
        Spacer(Modifier.height(20.dp))
        SegBar(spec.bands.size, posOf(spec.bands, spec.v), 8.dp, 18.dp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            spec.ticks.forEach { Text(it, color = Dim, fontSize = 10.sp) }
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            spec.bands.forEachIndexed { i, b ->
                val on = b == band
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(pal.accent.copy(alpha = stepAlpha(i, spec.bands.size)), RoundedCornerShape(3.dp)))
                    Text(
                        buildAnnotatedString {
                            append(b.name)
                            if (on) withStyle(SpanStyle(color = pal.accent, fontSize = 11.sp)) { append("  ← now") }
                        },
                        color = if (on) Ink else Dim, fontSize = 12.5.sp, modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    Text("${b.from}–${b.to}${spec.unit}", color = if (on) Ink else Dim, fontSize = 12.5.sp)
                }
            }
        }
        HorizontalDivider(color = Hair, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        Text(band.advice, color = Ink, fontSize = 13.5.sp, lineHeight = 20.sp)
        if (spec.extra.isNotEmpty()) Text(spec.extra, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun compassLong(deg: Int) = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((deg % 360 + 22) / 45) % 8]

@Composable
private fun Compass(deg: Int) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(66.dp)) {
        val c = center; val r = size.minDimension / 2 - 2.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.18f), r, c, style = Stroke(1.dp.toPx()))
        repeat(4) { i ->
            val a = Math.toRadians(i * 90.0 - 90)
            val o = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
            val inner = Offset(c.x + ((r - 4.dp.toPx()) * kotlin.math.cos(a)).toFloat(), c.y + ((r - 4.dp.toPx()) * kotlin.math.sin(a)).toFloat())
            drawLine(Color.White.copy(alpha = 0.4f), o, inner, 1.dp.toPx())
        }
        val n = measurer.measure("N", TextStyle(color = Ink, fontSize = 8.sp, fontFamily = Inter))
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - r + 6.dp.toPx()))
        // the wind comes FROM deg; the arrow points where it blows to, like the preview
        rotate(deg.toFloat(), c) {
            val s = r / 30f
            drawPath(Path().apply {
                moveTo(c.x, c.y - 21 * s); lineTo(c.x + 6 * s, c.y + 2 * s); lineTo(c.x, c.y - 2 * s); lineTo(c.x - 6 * s, c.y + 2 * s); close()
            }, pal.accent)
        }
    }
}

@Composable
private fun Settings(theme: Theme, onTheme: (Theme) -> Unit, imperial: Boolean, onImperial: (Boolean) -> Unit) {
    val pal = LocalPal.current
    val haptic = LocalHapticFeedback.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Theme", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Theme.entries.forEach { t ->
                val on = t == theme
                Column(
                    Modifier.weight(1f).background(if (on) pal.accent else Color.Transparent, RoundedCornerShape(11.dp))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTheme(t) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.label, color = if (on) Color.Black else Ink, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                    Text(t.blurb, color = if (on) Color.Black.copy(alpha = 0.6f) else Dim, fontSize = 10.5.sp)
                }
            }
        }
        Text("Units", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Segmented(listOf("°C · km/h", "°F · mph"), if (imperial) 1 else 0) { onImperial(it == 1) }
        Spacer(Modifier.height(8.dp))
        AlertSettings()
        HorizontalDivider(color = Hair)
        KeepFresh()
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val pal = LocalPal.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, o ->
            Text(
                o, color = if (i == selected) Color.Black else Ink, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).background(if (i == selected) pal.accent else Color.Transparent, RoundedCornerShape(11.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(i) }.padding(vertical = 9.dp),
            )
        }
    }
}

/** Xiaomi stops background apps; this sends the user straight to the two switches that keep widgets updating. */
@Composable
private fun KeepFresh() {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        lifecycle.lifecycle.addObserver(o)
        onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    fun open(vararg intents: android.content.Intent) {
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    val pkg = android.net.Uri.parse("package:" + ctx.packageName)
    val details = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    Text("Keep widgets fresh", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
    Text("Xiaomi pauses background apps. These two switches keep widgets and alerts updating.", color = Dim, fontSize = 12.sp, lineHeight = 16.sp)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Autostart ›", color = pal.accent, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).border(1.dp, pal.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .clickable {
                    open(
                        android.content.Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                        details,
                    )
                }.padding(vertical = 10.dp),
        )
        Text(
            if (exempt) "Battery ✓" else "Battery: no limits ›", color = if (exempt) Dim else pal.accent, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f).border(1.dp, (if (exempt) Faint else pal.accent.copy(alpha = 0.35f)), RoundedCornerShape(12.dp))
                .clickable(enabled = !exempt) {
                    @Suppress("BatteryLife")
                    open(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), details)
                }.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun SunMoon(w: Weather) {
    val pal = LocalPal.current
    val measurer = rememberTextMeasurer()
    val (phase, lit, toFull) = remember(w.zone) { moonPhase(java.time.LocalDate.now(runCatching { ZoneId.of(w.zone) }.getOrDefault(ZoneId.systemDefault()))) }
    val progress = remember(w.fetchedAt, w.sunrise) {
        runCatching {
            val now = LocalTime.now(ZoneId.of(w.zone)); val a = LocalTime.parse(w.sunrise); val b = LocalTime.parse(w.sunset)
            ((now.toSecondOfDay() - a.toSecondOfDay()).toFloat() / (b.toSecondOfDay() - a.toSecondOfDay()))
        }.getOrDefault(0.5f)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).aspectRatio(2.5f)) {
            val pad = 14.dp.toPx(); val r = minOf((size.width - 2 * pad) / 2, size.height - 22.dp.toPx())
            val c = Offset(size.width / 2, size.height - 14.dp.toPx())
            val rect = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)
            drawArc(Ink.copy(alpha = 0.15f), 180f, 180f, false, rect.topLeft, rect.size, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx()))))
            val f = progress.coerceIn(0f, 1f)
            drawArc(pal.accent, 180f, 180f * f, false, rect.topLeft, rect.size, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            val a = Math.PI + Math.PI * f
            val dot = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
            drawCircle(Brush.radialGradient(listOf(pal.accent.copy(alpha = 0.5f), Color.Transparent), dot, 12.dp.toPx()), 12.dp.toPx(), dot)
            drawCircle(if (w.isDay) Color.White else pal.accent, 4.dp.toPx(), dot)
            val st = TextStyle(color = Ink.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = Inter)
            val l = measurer.measure(w.sunrise, st); val rr = measurer.measure(w.sunset, st)
            drawText(l, topLeft = Offset(c.x - r - l.size.width / 2f, c.y + 2.dp.toPx()))
            drawText(rr, topLeft = Offset(c.x + r - rr.size.width / 2f, c.y + 2.dp.toPx()))
        }
        Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(34.dp)) {
                val r = size.minDimension / 2 - 2.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.08f), r, center)
                // lit part: half disc plus an ellipse that grows/shrinks with the phase
                val k = (lit / 50f - 1f)
                val waxing = phase.startsWith("Wax") || phase == "First quarter" || phase == "Full moon"
                val p = Path().apply {
                    addArc(androidx.compose.ui.geometry.Rect(center, r), if (waxing) -90f else 90f, 180f)
                }
                val e = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center.x - r * kotlin.math.abs(k), center.y - r, center.x + r * kotlin.math.abs(k), center.y + r)) }
                val lit2 = Path().apply { op(p, e, if (k >= 0) androidx.compose.ui.graphics.PathOperation.Union else androidx.compose.ui.graphics.PathOperation.Difference) }
                drawPath(lit2, pal.accent)
            }
            Text(phase, color = Dim, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Text("$lit% lit", color = Dim, fontSize = 11.sp)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        listOf("Daylight" to w.daylight, "Golden hour" to (w.goldenHour ?: "—"), "Full moon" to if (toFull == 0) "tonight" else "in $toFull d").forEach { (k, v) ->
            Column(Modifier.weight(1f)) {
                Text(k, color = Dim, fontSize = 11.sp)
                Text(v, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun AlertSettings() {
    val ctx = LocalContext.current
    var rain by remember { mutableStateOf(Alerts.rainOn(ctx)) }
    var morning by remember { mutableStateOf(Alerts.morningOn(ctx)) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) pending?.invoke(); pending = null }
    fun enable(apply: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pending = apply; notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else apply()
    }
    ToggleRow("Rain soon", "Heads-up when rain is likely in the next 2 hours", rain) { on ->
        if (on) enable { rain = true; Alerts.setRain(ctx, true) } else { rain = false; Alerts.setRain(ctx, false) }
    }
    HorizontalDivider(color = Hair)
    ToggleRow("Morning summary", "Today's forecast between 6 and 9 am", morning) { on ->
        if (on) enable { morning = true; Alerts.setMorning(ctx, true) } else { morning = false; Alerts.setMorning(ctx, false) }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(sub, color = Dim, fontSize = 12.sp)
        }
        Switch(
            on, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = pal.accent,
                uncheckedThumbColor = Dim, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = Faint,
            ),
        )
    }
}

@Composable
private fun PageEmpty(city: City, onRetry: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(0.3f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxWidth().padding(top = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (city.error == null) {
            Text("Reading the sky…", color = Ink.copy(alpha = pulse), fontSize = 22.sp, fontWeight = FontWeight.Light)
        } else {
            Text("Can't get weather", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text("All three sources failed. Check your connection.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            Pill("Retry", filled = true, onClick = onRetry)
        }
    }
}

@Composable
private fun Welcome(s: UiState, onSearch: () -> Unit, onLocate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mono", color = Ink, fontSize = 56.sp, fontWeight = FontWeight.Thin)
        Text("Pick a city to start", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(bottom = 28.dp))
        Pill("Search a city", filled = true, onClick = onSearch)
        Spacer(Modifier.height(10.dp))
        Pill(if (s.locating) "Finding you…" else "Use my location once", filled = false, onClick = onLocate)
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Text(
        text, color = if (filled) Color.Black else Ink, fontSize = 15.sp,
        modifier = Modifier.background(if (filled) pal.accent else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, pal.accent, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
}

private val POPULAR = listOf("Delhi", "Pune", "Hyderabad", "Chennai", "Goa", "Tokyo", "Dubai")

private fun localTime(tz: String) = runCatching { LocalTime.now(ZoneId.of(tz)).toString().take(5) }.getOrDefault("")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchSheet(cities: List<City>, onPick: (Place) -> Unit, onRemove: (Place) -> Unit, onHome: (Place) -> Unit, onGps: () -> Unit, onClose: () -> Unit) {
    val pal = LocalPal.current
    val theme = LocalTheme.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = runCatching { Repo.search(q.trim()) }.getOrDefault(emptyList())
    }
    BackHandler(onBack = onClose)
    val bg = if (theme == Theme.MIDNIGHT)
        Brush.radialGradient(0f to pal.accent.copy(alpha = 0.14f), 0.6f to Color.Black, center = Offset(540f, 0f), radius = 1600f)
    else Brush.verticalGradient(0f to pal.top, 0.7f to pal.bottom)
    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).background(bg).systemBarsPadding().imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f))), RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = pal.accent, modifier = Modifier.size(18.dp))
                    BasicTextField(
                        q, { q = it }, singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Light, fontFamily = Inter),
                        cursorBrush = SolidColor(pal.accent),
                        modifier = Modifier.weight(1f).padding(start = 10.dp).focusRequester(focus),
                        decorationBox = { inner ->
                            Box { if (q.isEmpty()) Text("Where to?", color = Ink.copy(alpha = 0.35f), fontSize = 20.sp, fontWeight = FontWeight.Light); inner() }
                        },
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Ink) }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(pal.accent.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .border(1.dp, pal.accent.copy(alpha = 0.2f), RoundedCornerShape(16.dp)).clickable(onClick = onGps)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.LocationOn, null, tint = pal.accent, modifier = Modifier.size(18.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Use my location once", color = Ink, fontSize = 14.sp)
                    Text("Saved as a city. GPS isn't used again.", color = Dim, fontSize = 11.5.sp)
                }
            }
        }
        if (q.isBlank()) {
            if (cities.isNotEmpty()) {
                item { Label("Your cities · hold for options") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(cities, key = { it.place.key }) { c -> CityCard(c, home = c == cities.first(), onPick = { onPick(c.place) }, onRemove = { onRemove(c.place) }, onHome = { onHome(c.place) }) }
                    }
                }
            }
            item { Label("Popular") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    POPULAR.forEach { name ->
                        Text(
                            name, color = Ink, fontSize = 13.sp,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(50))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .clickable { q = name }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        } else {
            items(results) { p ->
                Column {
                    Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val n = q.trim()
                            Text(
                                buildAnnotatedString {
                                    if (p.name.startsWith(n, ignoreCase = true)) {
                                        withStyle(SpanStyle(color = pal.accent)) { append(p.name.take(n.length)) }
                                        append(p.name.drop(n.length))
                                    } else append(p.name)
                                },
                                color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            )
                            if (p.detail.isNotEmpty()) Text(p.detail, color = Dim, fontSize = 12.sp)
                        }
                        Text("${localTime(p.tz)}\nlocal time", color = Dim, fontSize = 11.5.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    HorizontalDivider(color = Hair)
                }
            }
        }
    }
}

@Composable
private fun CityCard(c: City, home: Boolean, onPick: () -> Unit, onRemove: () -> Unit, onHome: () -> Unit) {
    val theme = LocalTheme.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val w = c.weather
    val p = paletteFor(theme, w?.kind ?: Kind.CLOUDY, w?.isDay ?: true)
    val bg = if (theme == Theme.MIDNIGHT)
        Brush.radialGradient(0f to p.top.copy(alpha = 0.35f), 0.7f to Color.Black, center = Offset(280f, 0f), radius = 420f)
    else Brush.linearGradient(listOf(p.top, p.bottom))
    Box(
        Modifier.width(126.dp).height(146.dp).background(Color.Black, RoundedCornerShape(22.dp)).background(bg, RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(22.dp))
            .pointerInput(c.place.key) {
                detectTapGestures(onTap = { if (menu) menu = false else onPick() }, onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
            }
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Glyph(w?.kind ?: Kind.CLOUDY, w?.isDay ?: true, 26.dp, p.accent)
            Column {
                Text(w?.let { "${it.temp}°" } ?: "—", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.ExtraLight, lineHeight = 38.sp)
                Text(c.place.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(listOfNotNull(w?.let { labelOf(it.code) }, localTime(c.place.tz)).joinToString(" · "), color = Dim, fontSize = 11.sp, maxLines = 1)
            }
        }
        if (home) Text(
            "WIDGET", color = Ink, fontSize = 8.5.sp, letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        )
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(22.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                if (!home) MenuItem("Set as widget city") { menu = false; onHome() }
                MenuItem("Remove") { menu = false; onRemove() }
                MenuItem("Cancel", dim = true) { menu = false }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, dim: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = if (dim) Dim else Ink, fontSize = 12.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (dim) 0f else 0.08f), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/** Live previews of the 8 home-screen widgets, rendered from the same RemoteViews the launcher gets, with one-tap Add. */
@Composable
private fun WidgetGallery(w: Weather) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val names = mapOf(Design.TYPE to "Type", Design.HORIZON to "Horizon", Design.ORBIT to "Orbit", Design.PULSE to "Pulse")
    val wDp = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 64)
    Text("Type and Horizon follow Minimalist colours, Orbit and Pulse follow Midnight. Each comes dark or frosted glass.",
        color = Dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Widget.all.forEach { (cls, spec) ->
            val (design, glass) = spec
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${names[design]} · ${if (glass) "glass" else "dark"}", color = Ink, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text(
                        "Add", color = pal.accent, fontSize = 13.sp,
                        modifier = Modifier.border(1.dp, pal.accent.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .clickable { ctx.getSystemService(android.appwidget.AppWidgetManager::class.java).requestPinAppWidget(android.content.ComponentName(ctx, cls), null, null) }
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
                // glass needs something behind it to read as glass, so preview it over a soft wallpaper-like gradient
                Box(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(96.dp)
                        .background(Brush.linearGradient(listOf(Color(0xFF2B3A2F), Color(0xFF4A3D2A), Color(0xFF22303F))), RoundedCornerShape(18.dp))
                        .padding(8.dp)
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c -> android.widget.FrameLayout(c) },
                        update = { f ->
                            f.removeAllViews()
                            f.addView(WidgetRenderer.views(f.context, design, glass, wDp - 16, 80).apply(f.context, f))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
