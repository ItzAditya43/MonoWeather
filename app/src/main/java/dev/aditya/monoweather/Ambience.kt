package dev.aditya.monoweather

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Weather sounds, synthesised on the fly (no audio files): soft rain, wind, storm rumble or a calm night.
 * Runs on one small thread only while Mono is open and the setting is on.
 */
object Ambience {
    @Volatile private var running = false
    @Volatile var kind: Kind = Kind.CLEAR
    @Volatile var night = false
    private var thread: Thread? = null

    fun start(k: Kind, isNight: Boolean) {
        kind = k; night = isNight
        if (running) return
        running = true
        thread = Thread(::loop, "mono-ambience").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    private fun loop() {
        val rate = 22050
        val n = 1024
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, n * 4)).build()
        val buf = ShortArray(n)
        val rnd = Random(42)
        var lp = 0.0; var lp2 = 0.0; var brown = 0.0; var t = 0L
        var fade = 0.0
        var rumble = 0.0; var rumbleT = 0
        track.play()
        try {
            while (running || fade > 0.001) {
                val target = if (running) 1.0 else 0.0
                val k = kind
                for (i in 0 until n) {
                    fade += (target - fade) * 0.0004
                    val white = rnd.nextDouble() * 2 - 1
                    lp += (white - lp) * 0.08          // rain hiss
                    lp2 += (white - lp2) * 0.02        // softer body
                    brown = (brown + white * 0.02) * 0.995 // wind
                    val sec = t / rate.toDouble()
                    val gustLfo = 0.55 + 0.45 * sin(2 * PI * sec / 7.0)
                    var v = when (k) {
                        Kind.RAIN -> lp * 0.55 + lp2 * 0.5 + (if (rnd.nextInt(2600) == 0) rnd.nextDouble() * 0.5 else 0.0)
                        Kind.STORM -> lp * 0.6 + lp2 * 0.6 + rumble
                        Kind.SNOW, Kind.FOG -> brown * 1.6 * gustLfo
                        Kind.CLOUDY, Kind.PARTLY -> brown * 2.2 * gustLfo
                        Kind.CLEAR -> if (night) brown * 0.9 + crickets(sec, rnd) else brown * 1.4 * gustLfo
                    }
                    if (k == Kind.STORM) {
                        if (rumbleT <= 0 && rnd.nextInt(rate * 9) == 0) rumbleT = rate * 3
                        if (rumbleT > 0) { rumbleT--; rumble = brown * 6 * (rumbleT / (rate * 3.0)) } else rumble *= 0.999
                    }
                    v *= 0.22 * fade
                    buf[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                    t++
                }
                track.write(buf, 0, n)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // faint, sparse chirps for a calm night
    private fun crickets(sec: Double, rnd: Random): Double {
        val beat = (sec * 2.2) % 3.0
        return if (beat < 0.12) sin(2 * PI * 4300 * sec) * 0.05 * (1 - beat / 0.12) else 0.0
    }
}
