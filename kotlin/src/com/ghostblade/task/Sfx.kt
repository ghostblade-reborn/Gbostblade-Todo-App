package com.ghostblade.task

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sound effects: the JS WebAudio synth, re-implemented with AudioTrack.
 *  One small synthesized voice per action — no audio files. */
object Sfx {
    private const val SR = 22050
    private val exec = Executors.newSingleThreadExecutor()

    fun enabled(): Boolean =
        Store.ctx.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean("sounds", true)

    private fun tone(fA: Double, fB: Double, durMs: Int, type: String, vol: Double, delayMs: Int = 0) {
        if (!enabled()) return
        exec.execute {
            if (delayMs > 0) try { Thread.sleep(delayMs.toLong()) } catch (e: InterruptedException) { return@execute }
            val n = (SR * durMs / 1000).coerceAtLeast(1)
            val buf = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val frac = i.toDouble() / n
                val f = fA * Math.pow(fB / fA, frac)
                val phase = 2 * PI * f * t
                val env = Math.min(1.0, frac / 0.06) * exp(-3.0 * frac)
                val v = when (type) {
                    "square" -> if (sin(phase) >= 0) 0.6 else -0.6
                    "sawtooth" -> 2.0 * ((f * t) % 1.0) - 1.0
                    "triangle" -> 2.0 * Math.abs(2.0 * ((f * t) % 1.0) - 1.0) - 1.0
                    else -> sin(phase)
                }
                buf[i] = (v * env * vol * 32767).toInt().coerceIn(-32767, 32767).toShort()
            }
            play(buf)
        }
    }

    private fun whoosh(durMs: Int, vol: Double, up: Boolean) {
        if (!enabled()) return
        exec.execute {
            val n = (SR * durMs / 1000).coerceAtLeast(1)
            val buf = ShortArray(n)
            var lp = 0.0
            val rnd = java.util.Random(42)
            for (i in 0 until n) {
                val frac = i.toDouble() / n
                val cutoff = if (up) 0.02 + frac * 0.25 else 0.27 - frac * 0.25
                lp += cutoff * ((rnd.nextDouble() * 2 - 1) - lp)
                val env = sin(PI * frac)
                buf[i] = (lp * env * vol * 32767 * 2.2).toInt().coerceIn(-32767, 32767).toShort()
            }
            play(buf)
        }
    }

    private fun play(buf: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { t?.release() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.notificationMarkerPosition = buf.size
            track.play()
        } catch (e: Exception) { /* audio busy/unavailable — stay silent */ }
    }

    fun tap() = tone(1150.0, 840.0, 55, "sine", 0.20)
    fun hold() = tone(330.0, 180.0, 120, "triangle", 0.32)
    fun nav() = tone(760.0, 560.0, 60, "sine", 0.13)
    fun open() { whoosh(170, 0.16, true); tone(520.0, 780.0, 100, "sine", 0.10) }
    fun close() = whoosh(130, 0.13, false)
    fun add() { tone(620.0, 940.0, 90, "sine", 0.26); tone(1245.0, 1245.0, 80, "sine", 0.13, 55) }
    fun complete() {
        tone(659.0, 659.0, 100, "sine", 0.26)
        tone(880.0, 880.0, 150, "sine", 0.26, 80)
        tone(1319.0, 1319.0, 200, "sine", 0.11, 80)
    }
    fun del() = tone(400.0, 130.0, 150, "sawtooth", 0.12)
    fun tick() = tone(990.0, 990.0, 45, "square", 0.10)
}
