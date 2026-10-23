package dev.aditya.monoweather

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: home city's temperature and condition, from the saved forecast (no network). */
class WeatherTile : TileService() {
    override fun onStartListening() {
        val home = Repo.places(this).firstOrNull()
        val w = home?.let { Repo.cached(this, it) }
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            icon = Icon.createWithResource(this@WeatherTile, R.drawable.ic_stat)
            label = w?.let { "${it.temp}° ${home.name}" } ?: "Mono"
            if (Build.VERSION.SDK_INT >= 29) subtitle = w?.let { labelOf(it.code) } ?: "Tap to set up"
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
