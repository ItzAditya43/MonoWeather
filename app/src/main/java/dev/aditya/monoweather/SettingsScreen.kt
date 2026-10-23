package dev.aditya.monoweather

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private enum class Page(val title: String, val icon: String) {
    APPEARANCE("Appearance", "✦"), HOME("Home screen", "▦"), UNITS("Units", "°"),
    RADAR("Radar", "◎"), ALERTS("Alerts", "☂"), DATA("Data", "⇄"),
    WIDGETS("Widgets", "▧"), FRESH("Keep widgets fresh", "⚡"), ABOUT("About", "i"),
}

/**
 * Settings: a short list of categories; each opens its own page that slides in (and back out).
 * Every change is saved to [Prefs] and applied at once via [MainVm.settingsChanged].
 */
@Composable
fun SettingsScreen(vm: MainVm, s: UiState, w: Weather?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val theme = LocalTheme.current
    var page by remember { mutableStateOf<Page?>(null) }
    BackHandler { if (page != null) page = null else onBack() }
    val rev = s.rev
    fun apply(block: () -> Unit) { block(); vm.settingsChanged() }

    val bg = if (theme == Theme.MIDNIGHT) Brush.verticalGradient(listOf(Color.Black, Color.Black))
    else Brush.verticalGradient(0f to pal.top, 0.55f to pal.bottom)

    Box(Modifier.fillMaxSize().background(Color.Black).background(bg)) {
        androidx.compose.animation.AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState != null
                (androidx.compose.animation.slideInHorizontally(spring(dampingRatio = 0.92f, stiffness = 520f)) { if (forward) it / 3 else -it / 3 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { if (forward) -it / 4 else it / 4 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
            },
            label = "settings",
        ) { current ->
            key(rev) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (current != null) page = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
                            Text(current?.title ?: "Settings", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
                        }
                    }
                    if (current == null) {
                        val u = Prefs.units(ctx)
                        fun summary(p: Page) = when (p) {
                            Page.APPEARANCE -> "${s.theme.label} · animations ${s.anim.label.lowercase()}"
                            Page.HOME -> "${Section.entries.count { Prefs.shown(ctx, it) } + 3} of ${Section.entries.size + 3} shown"
                            Page.UNITS -> "${if (u.tempF) "°F" else "°C"} · ${u.wind} · ${if (u.clock24) "24h" else "12h"}"
                            Page.RADAR -> if (Prefs.radarWifi(ctx)) "Loads on Wi-Fi" else "Loads only when you tap"
                            Page.ALERTS -> listOfNotNull("Rain soon".takeIf { Alerts.rainOn(ctx) }, "Morning".takeIf { Alerts.morningOn(ctx) }).joinToString(" · ").ifEmpty { "Off" }
                            Page.DATA -> "${Prefs.source(ctx)} · every ${Prefs.refreshMin(ctx).let { if (it < 60) "$it min" else "${it / 60} h" }}"
                            Page.WIDGETS -> if (s.theme == Theme.MIDNIGHT) "Orbit and Pulse · dark or glass" else "Type and Horizon · dark or glass"
                            Page.FRESH -> "Autostart and battery"
                            Page.ABOUT -> "Mono " + (runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")
                        }
                        listOf(
                            listOf(Page.APPEARANCE, Page.HOME, Page.UNITS),
                            listOf(Page.RADAR, Page.ALERTS, Page.DATA),
                            listOf(Page.WIDGETS, Page.FRESH),
                            listOf(Page.ABOUT),
                        ).forEach { grp ->
                            item {
                                Group("") {
                                    grp.forEachIndexed { i, p ->
                                        Item(p.icon, p.title, summary(p), onClick = { page = p }) { Text("›", color = Dim, fontSize = 18.sp) }
                                        if (i < grp.lastIndex) Divider()
                                    }
                                }
                            }
                        }
                    } else item {
                        when (current) {
                            Page.APPEARANCE -> Group("") {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Theme.entries.forEach { t -> ThemeCard(t, t == s.theme, w, Modifier.weight(1f)) { vm.setTheme(t) } }
                                }
                                Divider()
                                Item("✦", "Animations", "Full, reduced, or still skies to save battery", below = true) {
                                    Chips(Anim.entries.map { it.label }, Prefs.anim(ctx).ordinal) { i -> apply { Prefs.setAnim(ctx, Anim.entries[i]) } }
                                }
                                Divider()
                                Item("◉", "Haptics", "Light ticks on swipe, refresh and hold") { Toggle(Prefs.haptics(ctx)) { v -> apply { Prefs.setHaptics(ctx, v) } } }
                            }
                            Page.HOME -> Group("") {
                                listOf("°" to "Current weather", "≋" to "Next 24 hours", "▤" to "7 days").forEach { (ic, t) ->
                                    Item(ic, t, null) { Locked() }
                                    Divider()
                                }
                                Section.entries.forEachIndexed { i, sec ->
                                    val icon = when (sec) { Section.NEXT2H -> "☂"; Section.AIR -> "◌"; Section.COMFORT -> "〰"; Section.EXTRA -> "▥"; Section.RADAR -> "◎"; Section.SUN -> "☾"; Section.YESTERDAY -> "±" }
                                    Item(icon, sec.label, sec.sub.ifEmpty { null }) { Toggle(Prefs.shown(ctx, sec)) { v -> apply { Prefs.setShown(ctx, sec, v) } } }
                                    if (i < Section.entries.lastIndex) Divider()
                                }
                            }
                            Page.UNITS -> {
                                val u = Prefs.units(ctx)
                                Group("") {
                                    Item("°", "Temperature", null) { Chips(listOf("°C", "°F"), if (u.tempF) 1 else 0) { i -> apply { Prefs.setTempF(ctx, i == 1) } } }
                                    Divider()
                                    val winds = listOf("km/h", "mph", "m/s", "kn")
                                    Item("➶", "Wind", null, below = true) { Chips(winds, winds.indexOf(u.wind).coerceAtLeast(0)) { i -> apply { Prefs.setWind(ctx, winds[i]) } } }
                                    Divider()
                                    Item("◷", "Time", null) { Chips(listOf("24h", "12h"), if (u.clock24) 0 else 1) { i -> apply { Prefs.setClock24(ctx, i == 0) } } }
                                }
                            }
                            Page.RADAR -> Group("") {
                                Item("⇣", "Load automatically on Wi-Fi", "Otherwise only when you tap") { Toggle(Prefs.radarWifi(ctx)) { v -> apply { Prefs.setRadarWifi(ctx, v) } } }
                                Divider()
                                Item("▁", "Start minimised", null) { Toggle(Prefs.radarMin(ctx)) { v -> apply { Prefs.setRadarMin(ctx, v) } } }
                            }
                            Page.ALERTS -> AlertsGroup(::apply)
                            Page.DATA -> DataGroup(vm, ::apply)
                            Page.WIDGETS -> WidgetsGroup(s.theme)
                            Page.FRESH -> FreshGroup()
                            Page.ABOUT -> Group("") {
                                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                                Item("i", "Mono $version", "Open source on GitHub · MIT", onClick = {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ItzAditya43/MonoWeather")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }) { Text("›", color = Dim, fontSize = 18.sp) }
                                Divider()
                                Item("©", "Data & licences", "Open-Meteo, MET Norway (CC BY 4.0) · wttr.in · RainViewer · Esri · Inter Tight (OFL)") {}
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- building blocks ----------

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 16.dp)) {
        if (title.isNotEmpty()) Text(title.uppercase(), color = pal.accent.copy(alpha = 0.8f), fontSize = 10.5.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(shape).then(
                if (theme == Theme.MIDNIGHT)
                    Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.045f), Color.White.copy(alpha = 0.015f)))).border(1.dp, Hair, shape)
                else Modifier.background(Brush.verticalGradient(listOf(pal.top.copy(alpha = 0.6f), pal.bottom.copy(alpha = 0.7f)))).border(1.dp, pal.accent.copy(alpha = 0.15f), shape)
            ),
            content = content,
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Hair)

@Composable
private fun Item(icon: String, title: String, sub: String?, onClick: (() -> Unit)? = null, below: Boolean = false, trailing: @Composable () -> Unit) {
    val pal = LocalPal.current
    // wide controls (many chips) sit under the label instead of squeezing it
    if (below) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(30.dp).background(pal.accent.copy(alpha = 0.14f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Text(icon, color = pal.accent, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ink, fontSize = 14.sp)
                    if (sub != null) Text(sub, color = Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
                }
            }
            Box(Modifier.padding(start = 42.dp, top = 10.dp)) { trailing() }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).background(pal.accent.copy(alpha = 0.14f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Text(icon, color = pal.accent, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Dim, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        trailing()
    }
}

@Composable
private fun Locked() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.Lock, null, tint = Dim, modifier = Modifier.size(11.dp))
        Text("Always on", color = Dim, fontSize = 11.sp)
    }
}

/** Compact switch with a springy thumb, as in the reference. */
@Composable
internal fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    val haptic = LocalHapticFeedback.current
    var state by remember { mutableStateOf(on) }
    val x by animateDpAsState(if (state) 20.dp else 3.dp, spring(dampingRatio = 0.6f, stiffness = 600f), label = "thumb")
    val track by animateColorAsState(if (state) pal.accent else Color.White.copy(alpha = 0.12f), label = "track")
    Box(
        Modifier.width(42.dp).height(25.dp).clip(RoundedCornerShape(13.dp)).background(track)
            .semantics { role = Role.Switch; stateDescription = if (state) "On" else "Off" }
            .clickable { state = !state; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(state) }
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(19.dp).background(if (state) Color.Black else Color.White, CircleShape))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val pal = LocalPal.current
    val haptic = LocalHapticFeedback.current
    var sel by remember { mutableStateOf(selected) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
        options.forEachIndexed { i, o ->
            val on = i == sel
            Text(
                o, color = if (on) Color.Black else Ink, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) pal.accent else Color.Transparent)
                    .border(1.dp, if (on) pal.accent else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                    .clickable { sel = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ThemeCard(t: Theme, on: Boolean, w: Weather?, modifier: Modifier, onPick: () -> Unit) {
    val pal = LocalPal.current
    val p = paletteFor(t, w?.kind ?: Kind.CLEAR, w?.isDay ?: false)
    val preview = if (t == Theme.MIDNIGHT)
        Brush.radialGradient(0f to p.top.copy(alpha = 0.55f), 0.6f to Color.Black, center = androidx.compose.ui.geometry.Offset(260f, 0f), radius = 380f)
    else Brush.verticalGradient(listOf(p.top, p.bottom))
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).border(if (on) 1.5.dp else 1.dp, if (on) pal.accent else Hair, RoundedCornerShape(16.dp))
            .clickable(onClick = onPick).padding(10.dp)
            .semantics { contentDescription = "${t.label} theme" + if (on) ", selected" else "" }
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black).background(preview))
        Text(t.label, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text(t.blurb, color = Dim, fontSize = 11.sp)
    }
}

// ---------- groups with their own logic ----------

@Composable
private fun AlertsGroup(apply: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) pending?.invoke(); pending = null }
    fun enable(f: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pending = f; notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else f()
    }
    Group("") {
        Item("☂", "Rain soon", "Heads-up when rain is likely within 2 hours") {
            Toggle(Alerts.rainOn(ctx)) { v -> if (v) enable { Alerts.setRain(ctx, true) } else Alerts.setRain(ctx, false) }
        }
        Divider()
        val pcts = listOf(40, 60, 80)
        Item("%", "Rain threshold", "Notify when the chance reaches", below = true) {
            Chips(pcts.map { "$it%" }, pcts.indexOf(Prefs.rainPct(ctx)).coerceAtLeast(1)) { i -> apply { Prefs.setRainPct(ctx, pcts[i]) } }
        }
        Divider()
        Item("☼", "Morning summary", null) {
            Toggle(Alerts.morningOn(ctx)) { v -> if (v) enable { Alerts.setMorning(ctx, true) } else Alerts.setMorning(ctx, false) }
        }
        Divider()
        val hours = listOf(6, 7, 8)
        Item("◷", "Summary time", null) {
            Chips(hours.map { "$it:00" }, hours.indexOf(Prefs.morningHour(ctx)).coerceAtLeast(1)) { i -> apply { Prefs.setMorningHour(ctx, hours[i]) } }
        }
    }
}

@Composable
private fun DataGroup(vm: MainVm, apply: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var confirm by remember { mutableStateOf(false) }
    Group("") {
        val sources = listOf("Auto", "Open-Meteo", "MET Norway", "wttr.in")
        Item("⇄", "Weather source", "Your pick goes first; the others stay as backups", below = true) {
            Chips(sources, sources.indexOf(Prefs.source(ctx)).coerceAtLeast(0)) { i -> apply { Prefs.setSource(ctx, sources[i]) } }
        }
        Divider()
        val mins = listOf(30, 60, 180)
        Item("↻", "Refresh every", null) {
            Chips(listOf("30 min", "1 h", "3 h"), mins.indexOf(Prefs.refreshMin(ctx)).coerceAtLeast(0)) { i -> apply { Prefs.setRefreshMin(ctx, mins[i]); Sync.schedule(ctx, replace = true) } }
        }
        Divider()
        val kb = Prefs.bytesThisMonth(ctx) / 1024.0
        Item("⌁", "Data used", "This month") { Text(if (kb >= 1024) "%.1f MB".format(kb / 1024) else "%.0f KB".format(kb), color = Dim, fontSize = 13.sp) }
        Divider()
        Item("⌫", "Clear saved forecasts", "Cities are kept", onClick = { if (confirm) { vm.clearCache(); confirm = false } else confirm = true }) {
            Text(if (confirm) "Tap again" else "Clear", color = pal.accent, fontSize = 13.sp)
        }
        Divider()
        Column(Modifier.padding(14.dp)) {
            Text("Background refresh log", color = Ink, fontSize = 14.sp)
            Text("Shows whether your phone lets Mono refresh while closed", color = Dim, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            val log = Prefs.logEntries(ctx)
            if (log.isEmpty()) Text("No background refresh yet", color = Dim, fontSize = 12.sp)
            log.take(6).forEach { Text(it, color = Dim, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 18.sp) }
        }
    }
}

/** Xiaomi stops background apps; this sends the user straight to the two switches that keep widgets updating. */
@Composable
private fun FreshGroup() {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        lifecycle.lifecycle.addObserver(o)
        onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    fun open(vararg intents: Intent) {
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    val pkg = Uri.parse("package:" + ctx.packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    Group("") {
        Item("⚡", "Autostart", "Lets Mono refresh in the background on Xiaomi", onClick = {
            open(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details)
        }) { Text("Open ›", color = pal.accent, fontSize = 13.sp) }
        Divider()
        Item("▮", "Battery: no limits", null, onClick = if (exempt) null else {
            { @Suppress("BatteryLife") open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), details) }
        }) { Text(if (exempt) "On ✓" else "Set ›", color = if (exempt) Dim else pal.accent, fontSize = 13.sp) }
    }
}

@Composable
private fun WidgetsGroup(theme: Theme) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val names = mapOf(Design.TYPE to "Type", Design.HORIZON to "Horizon", Design.ORBIT to "Orbit", Design.PULSE to "Pulse")
    val wDp = LocalConfiguration.current.screenWidthDp - 30 - 24 - 16
    var hasWall by remember { mutableStateOf(Glass.hasWallpaper(ctx)) }
    var tick by remember { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && Glass.saveWallpaper(ctx, uri)) { hasWall = true; tick++; Widget.updateAll(ctx) }
    }
    Group("") {
        Item("▧", "Glass that matches your wallpaper", if (hasWall) "Tap each glass widget once so Mono learns where it sits" else "Pick your home-screen wallpaper (no permission needed)", onClick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(if (hasWall) "Change" else "Pick", color = pal.accent, fontSize = 13.sp) }
        Divider()
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
            Text("How to add one", color = Ink, fontSize = 14.sp)
            Text(
                "1. Long-press an empty spot on your home screen\n2. Tap Widgets → Mono\n3. Drag one of the four below onto the screen\nLong-press a placed widget → Reconfigure to change its city.",
                color = Dim, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Widget.all.filter { (_, spec) -> (spec.first == Design.ORBIT || spec.first == Design.PULSE) == (theme == Theme.MIDNIGHT) }.forEach { (cls, spec) ->
                val (design, glass) = spec
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${names[design]} · ${if (glass) "glass" else "dark"}", color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    Box(
                        Modifier.padding(top = 8.dp).fillMaxWidth().height(92.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF2B3A2F), Color(0xFF4A3D2A), Color(0xFF22303F))))
                            .padding(8.dp)
                    ) {
                        key(tick) {
                            AndroidView(
                                factory = { c -> android.widget.FrameLayout(c) },
                                update = { f ->
                                    f.removeAllViews()
                                    f.addView(WidgetRenderer.views(f.context, design, glass, wDp, 76, null).apply(f.context, f))
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
