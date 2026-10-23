package dev.aditya.monoweather

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opens when a widget is added or long-pressed → "Reconfigure": choose which saved city it shows. */
class WidgetConfig : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val places = Repo.places(this)
        val current = Prefs.widgetCity(this, id) ?: places.firstOrNull()?.key
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = Inter)) {
                Column(
                    Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Widget city", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light)
                    Text("Which saved city should this widget show?", color = Ink.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
                    if (places.isEmpty()) Text("Open Mono and add a city first.", color = Ink, fontSize = 15.sp)
                    places.forEach { p ->
                        val on = p.key == current
                        Column(
                            Modifier.fillMaxWidth().background(Color.White.copy(alpha = if (on) 0.10f else 0.04f), RoundedCornerShape(16.dp))
                                .border(1.dp, Color.White.copy(alpha = if (on) 0.35f else 0.1f), RoundedCornerShape(16.dp))
                                .clickable { pick(id, p) }.padding(16.dp)
                        ) {
                            Text(p.name, color = Ink, fontSize = 17.sp)
                            if (p.detail.isNotEmpty()) Text(p.detail, color = Ink.copy(alpha = 0.55f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    private fun pick(id: Int, p: Place) {
        Prefs.setWidgetCity(this, id, p.key)
        Widget.updateAll(this)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
