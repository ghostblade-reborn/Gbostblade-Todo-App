package com.ghostblade.task

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Sound effects — iOS-style minimal set.
 *  Apple's UI sounds are famously restrained: mostly near-silence, with
 *  ultra-short soft clicks (keyboard), a gentle pop, and a low thock.
 *  These are synthesized recreations of that character (Apple's actual
 *  sound files are proprietary and can't be shipped): every sound is a
 *  single event, 8–60 ms, at whisper volume — no chimes, no melodies. */
object Sfx {
    private const val SR = 22050
    private val exec = Executors.newSingleThreadExecutor()

    fun enabled(): Boolean =
        Store.ctx.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean("sounds", true)

    /** The iOS click: a tiny burst of band-filtered noise, ~14 ms.
     *  A resonant one-pole sweep gives it that dry, woody "tick". */
    private fun click(vol: Double, bright: Double = 0.32, durMs: Int = 14) {
        if (!enabled()) return
        exec.execute {
            val n = (SR * durMs / 1000).coerceAtLeast(1)
            val buf = ShortArray(n)
            val rnd = java.util.Random(7)
            var lp = 0.0
            var hp = 0.0
            for (i in 0 until n) {
                val frac = i.toDouble() / n
                val w = (rnd.nextDouble() * 2 - 1)
                lp += bright * (w - lp)            // low-pass: softens the burst
                hp += 0.55 * (lp - hp)             // second pole: removes the rumble
                val env = Math.min(1.0, frac / 0.08) * exp(-4.5 * frac)
                buf[i] = ((lp - hp) * env * vol * 32767 * 2.4)
                    .toInt().coerceIn(-32767, 32767).toShort()
            }
            play(buf)
        }
    }

    /** A soft sine "pop": pitch glides down, gone in ~40 ms. */
    private fun pop(f0: Double, vol: Double, durMs: Int = 42) {
        if (!enabled()) return
        exec.execute {
            val n = (SR * durMs / 1000).coerceAtLeast(1)
            val buf = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val frac = i.toDouble() / n
                val f = f0 * (1.0 - 0.35 * frac)
                phase += 2 * PI * f / SR
                val env = Math.min(1.0, frac / 0.05) * exp(-4.0 * frac)
                buf[i] = (sin(phase) * env * vol * 32767).toInt().coerceIn(-32767, 32767).toShort()
            }
            play(buf)
        }
    }

    /** A low, quiet "thock" — the iOS lock/toggle weight, without the noise. */
    private fun thock(f0: Double, vol: Double, durMs: Int = 55) {
        if (!enabled()) return
        exec.execute {
            val n = (SR * durMs / 1000).coerceAtLeast(1)
            val buf = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val frac = i.toDouble() / n
                val f = f0 * (1.0 - 0.5 * frac)
                phase += 2 * PI * f / SR
                val env = Math.min(1.0, frac / 0.04) * exp(-5.0 * frac)
                buf[i] = (sin(phase) * env * vol * 32767).toInt().coerceIn(-32767, 32767).toShort()
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

    /* The set — one quiet gesture per action, like iOS. */
    fun tap() = click(0.14)                    // keyboard-click softness
    fun tick() = click(0.10, bright = 0.42, durMs = 10)   // picker tock
    fun nav() = click(0.06, bright = 0.30, durMs = 10)    // barely there
    fun hold() = thock(170.0, 0.12)            // gentle press weight
    fun open() = thock(240.0, 0.09)            // sheet rises — near-silent
    fun close() = thock(190.0, 0.07)           // sheet settles — quieter still
    fun add() = pop(760.0, 0.13)               // a single soft pop
    fun complete() = pop(920.0, 0.12)          // one pop, no chime
    fun del() = thock(130.0, 0.11)             // low, brief, done
}
