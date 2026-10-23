package dev.aditya.monoweather

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class City(val place: Place, val weather: Weather?, val loading: Boolean = false, val error: String? = null)
data class UiState(
    val cities: List<City> = emptyList(), val locating: Boolean = false, val notice: String? = null,
    val theme: Theme = Theme.MINIMALIST, val anim: Anim = Anim.FULL, val haptics: Boolean = true,
    val hidden: Set<Section> = emptySet(),
    /** Bumped whenever a setting changes, so settings screens re-read [Prefs]. */
    val rev: Int = 0,
)

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(
        UiState(
            Repo.places(app).map { City(it, Repo.cached(app, it)) }, theme = Prefs.theme(app),
            anim = Prefs.anim(app), haptics = Prefs.haptics(app), hidden = Section.entries.filterNot { Prefs.shown(app, it) }.toSet(),
        )
    )
    val state = _state.asStateFlow()
    /** Pager index to scroll to after adding a city. */
    val jumpTo = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val jobs = mutableMapOf<String, Job>()

    private fun update(key: String, f: (City) -> City) =
        _state.update { s -> s.copy(cities = s.cities.map { if (it.place.key == key) f(it) else it }) }

    fun refresh(key: String, force: Boolean = false) {
        val city = _state.value.cities.firstOrNull { it.place.key == key } ?: return
        if (jobs[key]?.isActive == true) { if (force) jobs[key]?.cancel() else return }
        jobs[key] = viewModelScope.launch {
            update(key) { it.copy(loading = true) }
            runCatching { Repo.refresh(ctx, city.place) }
                .onSuccess { w ->
                    update(key) { it.copy(weather = w, loading = false, error = null) }
                    if (_state.value.cities.firstOrNull()?.place?.key == key) Widget.updateAll(ctx)
                }
                .onFailure { e -> update(key) { it.copy(loading = false, error = e.message ?: "Offline") } }
        }
    }

    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        _state.value.cities.filter { it.weather == null || now - it.weather.fetchedAt > 10 * 60_000 }
            .forEach { refresh(it.place.key) }
    }

    private fun reload() {
        val old = _state.value.cities.associateBy { it.place.key }
        _state.update { s -> s.copy(cities = Repo.places(ctx).map { old[it.key] ?: City(it, Repo.cached(ctx, it)) }) }
    }

    fun add(p: Place) {
        val i = Repo.addPlace(ctx, p)
        reload()
        jumpTo.tryEmit(i)
        refresh(p.key, force = true)
        if (i == 0) Sync.now(ctx)
    }

    fun remove(p: Place) {
        Repo.removePlace(ctx, p)
        reload()
        Widget.updateAll(ctx)
    }

    fun locateOnce() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true, notice = null) }
            runCatching { Repo.locateOnce(ctx) }
                .onSuccess { p ->
                    _state.update { it.copy(locating = false, notice = if (p == null) "Couldn't find your location. Search a city instead." else null) }
                    if (p != null) add(p)
                }
                .onFailure { e -> _state.update { it.copy(locating = false, notice = e.message) } }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Re-reads every setting and rebuilds the forecasts from cache (units, clock) and the widgets. */
    fun settingsChanged() {
        _state.update { s ->
            s.copy(
                theme = Prefs.theme(ctx), anim = Prefs.anim(ctx), haptics = Prefs.haptics(ctx),
                hidden = Section.entries.filterNot { Prefs.shown(ctx, it) }.toSet(), rev = s.rev + 1,
                cities = s.cities.map { it.copy(weather = Repo.cached(ctx, it.place) ?: it.weather) },
            )
        }
        Widget.updateAll(ctx)
    }

    fun clearCache() {
        Repo.clearCache(ctx)
        _state.update { s -> s.copy(cities = s.cities.map { it.copy(weather = null) }, notice = "Saved forecasts cleared", rev = s.rev + 1) }
        _state.value.cities.forEach { refresh(it.place.key, force = true) }
    }

    /** Moves a city to the front, making it the widget city. */
    fun makeHome(p: Place) {
        Repo.makeHome(ctx, p)
        reload()
        _state.update { s -> s.copy(cities = Repo.places(ctx).map { pl -> s.cities.first { it.place.key == pl.key } }) }
        Widget.updateAll(ctx)
        jumpTo.tryEmit(0)
    }

    fun setTheme(t: Theme) {
        Prefs.setTheme(ctx, t)
        settingsChanged()
        Widget.syncPicker(ctx)
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Sync.schedule(this)
        Widget.syncPicker(this)
        rememberWidgetTap(intent)
        setContent {
            MaterialTheme(
                darkColorScheme(
                    primary = Ink, onPrimary = Color.Black,
                    background = Color.Black, surface = Color.Black, onSurface = Ink,
                    surfaceContainerHigh = Color(0xFF1C1C1C), surfaceContainerHighest = Color(0xFF1C1C1C),
                )
            ) { App(vm) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        rememberWidgetTap(intent)
    }

    /** A widget tap carries the widget's exact screen position, which lets glass widgets line up with the wallpaper. */
    private fun rememberWidgetTap(i: android.content.Intent?) {
        val id = i?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val r = i?.sourceBounds
        if (id > 0 && r != null) {
            Prefs.setWidgetBounds(this, id, r)
            Widget.updateAll(this)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshIfStale()
    }
}
