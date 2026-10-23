package dev.aditya.monoweather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.view.WindowManager
import java.io.File

/**
 * Real frosted glass for widgets. Android won't let apps read the wallpaper, so the user picks the same
 * image once; a widget tap tells us where the widget sits; we blur exactly that slice of the image.
 */
object Glass {
    private fun file(c: Context) = File(c.filesDir, "wallpaper.jpg")
    private var cached: Bitmap? = null

    fun hasWallpaper(c: Context) = file(c).exists()

    private fun screen(c: Context): Rect = c.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    /** Stores the picked image cropped to the screen's shape, at quarter resolution (it gets blurred anyway). */
    fun saveWallpaper(c: Context, uri: Uri): Boolean = runCatching {
        val sc = screen(c)
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val k = maxOf(1, minOf(info.size.width / (sc.width() / 2), info.size.height / (sc.height() / 2)))
            d.setTargetSampleSize(k)
        }
        // centre-crop to the screen's aspect ratio, as launchers do for a fixed wallpaper
        val want = sc.width().toFloat() / sc.height()
        val (cw, ch) = if (src.width.toFloat() / src.height > want) ((src.height * want).toInt() to src.height) else (src.width to (src.width / want).toInt())
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val small = Bitmap.createScaledBitmap(crop, sc.width() / 4, sc.height() / 4, true)
        file(c).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cached = small
    }.isSuccess

    /** Blurred slice of the wallpaper behind screen rect [r], sized [w]×[h] px; null if not set up. */
    fun slice(c: Context, r: Rect, w: Int, h: Int): Bitmap? = runCatching {
        val wall = cached ?: BitmapFactory.decodeFile(file(c).path)?.also { cached = it } ?: return null
        val sc = screen(c)
        val k = wall.width.toFloat() / sc.width()
        val left = (r.left * k).toInt().coerceIn(0, wall.width - 1)
        val top = (r.top * k).toInt().coerceIn(0, wall.height - 1)
        val cw = (r.width() * k).toInt().coerceIn(1, wall.width - left)
        val ch = (r.height() * k).toInt().coerceIn(1, wall.height - top)
        val part = Bitmap.createBitmap(wall, left, top, cw, ch)
        // cheap, smooth blur: shrink hard, then grow back with filtering (twice)
        val tiny = Bitmap.createScaledBitmap(part, maxOf(2, cw / 8), maxOf(2, ch / 8), true)
        val mid = Bitmap.createScaledBitmap(tiny, maxOf(4, w / 6), maxOf(4, h / 6), true)
        Bitmap.createScaledBitmap(mid, w, h, true)
    }.getOrNull()
}
