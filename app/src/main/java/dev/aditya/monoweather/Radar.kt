package dev.aditya.monoweather

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

private const val Z = 6
private const val FRAMES = 6

private class Frame(val label: String, val tiles: List<ImageBitmap?>)
private class RadarData(val base: List<ImageBitmap?>, val labels: List<ImageBitmap?>, val frames: List<Frame>, val fx: Float, val fy: Float)

/** Kept for the app session, so reopening a page doesn't download again. */
private val memo = mutableMapOf<String, Pair<Long, RadarData>>()

private fun tileXY(lat: Double, lon: Double): Pair<Double, Double> {
    val n = 1 shl Z
    val r = Math.toRadians(lat)
    return (lon + 180) / 360 * n to (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n
}

private fun bitmap(url: String): ImageBitmap? = runCatching {
    val con = URL(url).openConnection() as HttpURLConnection
    con.connectTimeout = 7000; con.readTimeout = 9000
    con.setRequestProperty("User-Agent", Repo.UA)
    try {
        val bytes = con.inputStream.use { it.readBytes() }
        Net.bytes.addAndGet(bytes.size.toLong())
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } finally { con.disconnect() }
}.getOrNull()

private var appCtx: android.content.Context? = null

private suspend fun load(p: Place): RadarData = withContext(Dispatchers.IO) {
    memo[p.key]?.let { (at, d) -> if (System.currentTimeMillis() - at < 10 * 60_000) return@withContext d }
    val (x, y) = tileXY(p.lat, p.lon)
    val cx = floor(x).toInt(); val cy = floor(y).toInt()
    val cells = (-1..1).flatMap { dy -> (-1..1).map { dx -> (cx + dx) to (cy + dy) } }
    val meta = JSONObject(Repo.get("https://api.rainviewer.com/public/weather-maps.json"))
    val host = meta.getString("host")
    val past = meta.getJSONObject("radar").getJSONArray("past")
    // 6 frames spread over the last ~2 hours (frames are 10 minutes apart)
    val picks = (0 until FRAMES).map { (past.length() - 1 - (FRAMES - 1 - it) * 2).coerceAtLeast(0) }
    val zone = runCatching { ZoneId.of(p.tz) }.getOrDefault(ZoneId.systemDefault())
    val esri = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas"
    val d = coroutineScope {
        val base = cells.map { (tx, ty) -> async { bitmap("$esri/World_Dark_Gray_Base/MapServer/tile/$Z/$ty/$tx") } }
        val labels = cells.map { (tx, ty) -> async { bitmap("$esri/World_Dark_Gray_Reference/MapServer/tile/$Z/$ty/$tx") } }
        val frames = picks.map { i ->
            val f = past.getJSONObject(i)
            async {
                val label = Instant.ofEpochSecond(f.getLong("time")).atZone(zone).toLocalTime().toString().take(5)
                Frame(label, cells.map { (tx, ty) -> bitmap("$host${f.getString("path")}/256/$Z/$tx/$ty/2/1_1.png") })
            }
        }
        RadarData(base.awaitAll(), labels.awaitAll(), frames.awaitAll(), (x - cx + 1).toFloat(), (y - cy + 1).toFloat())
    }
    if (d.frames.all { f -> f.tiles.all { it == null } }) error("Radar unavailable")
    Net.flush(appCtx ?: return@withContext d)
    memo[p.key] = System.currentTimeMillis() to d
    d
}

/**
 * Rain radar. Nothing downloads until the user taps Load. Hold the title to minimise it to one line.
 */
@Composable
fun RadarCard(place: Place, w: Weather, pal: Palette, theme: Theme, label: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var minimised by remember { mutableStateOf(Prefs.radarMin(ctx)) }
    var data by remember(place.key) { mutableStateOf<RadarData?>(null) }
    var state by remember(place.key) { mutableStateOf("idle") } // idle | loading | ready | failed
    var frame by remember { mutableIntStateOf(FRAMES - 1) }
    var playing by remember { mutableStateOf(true) }

    // optional: load by itself on unmetered networks
    LaunchedEffect(place.key) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        if (state == "idle" && Prefs.radarWifi(ctx) && !cm.isActiveNetworkMetered && !minimised) state = "loading"
    }
    LaunchedEffect(state, place.key) {
        if (state != "loading") return@LaunchedEffect
        appCtx = ctx.applicationContext
        runCatching { load(place) }.onSuccess { data = it; frame = it.frames.lastIndex; state = "ready" }.onFailure { state = "failed" }
    }
    LaunchedEffect(data, playing) {
        val n = data?.frames?.size ?: return@LaunchedEffect
        while (playing) { delay(if (frame == n - 1) 1300 else 650); frame = (frame + 1) % n }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().semantics { contentDescription = "Rain radar. Hold to ${if (minimised) "expand" else "minimise"}" }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        minimised = !minimised; Prefs.setRadarMin(ctx, minimised)
                    })
                }
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { label() }
            Text(if (minimised) "Hold to expand" else "Hold to minimise", color = Ink.copy(alpha = 0.5f), fontSize = 10.5.sp)
        }
        AnimatedVisibility(minimised, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(radarSummary(w), color = Ink, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        AnimatedVisibility(!minimised, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column {
                Box(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(16.dp))
                        .background(if (theme == Theme.MIDNIGHT) Color.Black else pal.bottom)
                ) {
                    val d = data
                    if (d == null) {
                        Column(
                            Modifier.align(Alignment.Center).clickable(enabled = state != "loading") { state = "loading" }.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                when (state) { "loading" -> "Loading…"; "failed" -> "Try again"; else -> "Load radar" },
                                color = pal.accent, fontSize = 14.sp,
                                modifier = Modifier.border(1.dp, pal.accent.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                            Text(
                                if (state == "failed") "Radar service didn't answer" else "Downloads only when you tap · about 250 KB",
                                color = Ink.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        RadarMap(d, frame, pal, theme)
                        Row(
                            Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("light", color = Ink.copy(alpha = 0.55f), fontSize = 10.sp)
                            Box(Modifier.width(46.dp).height(5.dp).background(Brush.horizontalGradient(listOf(pal.accent.copy(alpha = 0.15f), pal.accent)), RoundedCornerShape(3.dp)))
                            Text("heavy", color = Ink.copy(alpha = 0.55f), fontSize = 10.sp)
                        }
                        Text("RainViewer · Esri", color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                    }
                }
                val d = data
                if (d != null) Timeline(d, frame, playing, pal, onPlay = { playing = !playing }, onSeek = { playing = false; frame = it })
            }
        }
    }
}

private fun radarSummary(w: Weather) = when {
    w.now2h?.any { it >= 0.05 } == true -> "Rain on the way or overhead · expand to see it move"
    (w.hours.drop(1).take(2).maxOfOrNull { it.precip } ?: 0) >= 50 -> "Showers possible nearby · expand to check"
    else -> "No rain near you right now"
}

@Composable
private fun RadarMap(d: RadarData, frame: Int, pal: Palette, theme: Theme) {
    val base = remember(theme) {
        val k = if (theme == Theme.MIDNIGHT) 0.55f else 0.8f
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f); timesAssign(ColorMatrix().apply { setToScale(k, k, k, 1f) }) })
    }
    // echoes → luminance → the screen's accent colour, so the radar stays one hue
    val tint = remember(pal.accent) {
        val a = pal.accent
        val m = ColorMatrix(floatArrayOf(
            0.4f * a.red * 2.4f, 0.4f * a.red * 2.4f, 0.2f * a.red * 2.4f, 0f, 0f,
            0.4f * a.green * 2.4f, 0.4f * a.green * 2.4f, 0.2f * a.green * 2.4f, 0f, 0f,
            0.4f * a.blue * 2.4f, 0.4f * a.blue * 2.4f, 0.2f * a.blue * 2.4f, 0f, 0f,
            0f, 0f, 0f, 0.95f, 0f,
        ))
        ColorFilter.colorMatrix(m)
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
        val tile = size.width / 2.2f
        val ox = size.width / 2 - d.fx * tile
        val oy = size.height / 2 - d.fy * tile
        val ts = IntSize(tile.toInt() + 1, tile.toInt() + 1)
        fun at(i: Int) = IntOffset((ox + (i % 3) * tile).toInt(), (oy + (i / 3) * tile).toInt())
        d.base.forEachIndexed { i, b -> b?.let { drawImage(it, dstOffset = at(i), dstSize = ts, colorFilter = base) } }
        d.frames.getOrNull(frame)?.tiles?.forEachIndexed { i, b -> b?.let { drawImage(it, dstOffset = at(i), dstSize = ts, colorFilter = tint) } }
        d.labels.forEachIndexed { i, b -> b?.let { drawImage(it, dstOffset = at(i), dstSize = ts, alpha = 0.75f) } }
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.Black, 8.dp.toPx(), c)
        drawCircle(pal.accent, 8.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color.White, 3.dp.toPx(), c)
    }
}

@Composable
private fun Timeline(d: RadarData, frame: Int, playing: Boolean, pal: Palette, onPlay: () -> Unit, onSeek: (Int) -> Unit) {
    val n = d.frames.size
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).clip(CircleShape).clickable(onClick = onPlay)
                .semantics { contentDescription = if (playing) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                if (playing) {
                    drawRect(Ink, Offset(size.width * 0.15f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height))
                    drawRect(Ink, Offset(size.width * 0.63f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height))
                } else drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close() }, Ink)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(16.dp)
                    .pointerInput(n) { detectTapGestures { onSeek(((it.x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1)) } }
                    .pointerInput(n) { detectHorizontalDragGestures { ch, _ -> onSeek(((ch.position.x / size.width) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)) } }
            ) {
                val y = size.height / 2
                val f = frame / (n - 1f)
                drawLine(Ink.copy(alpha = 0.1f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(pal.accent, Offset(0f, y), Offset(size.width * f, y), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.Black, 7.5.dp.toPx(), Offset(size.width * f, y))
                drawCircle(Color.White, 5.5.dp.toPx(), Offset(size.width * f, y))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("−2h", "−1h", "now").forEach { Text(it, color = Ink.copy(alpha = 0.5f), fontSize = 9.sp) }
            }
        }
        Text(if (frame == n - 1) "now" else d.frames[frame].label, color = Ink, fontSize = 11.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
