// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * v2.3 · 🔔 A short two-tone chime when the dash starts showing Android Auto (rising) and when the
 * link is lost (falling), so the rider knows without looking. Synthesised (no sound files), played
 * as navigation guidance so it reaches the helmet like a Maps prompt, without asking for audio
 * focus (that would fight the handlebar-button media session). The "lost" chime waits a few
 * seconds: a quick automatic reconnect stays silent.
 */
object ConnectSound {
    private const val PREFS = "connect_sound"
    private const val KEY_ON = "on"
    private const val RATE = 44_100
    private const val LOST_GRACE_MS = 4_000L

    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var wasLive = false

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()

    private fun live(p: Phase) = p == Phase.STREAMING || p == Phase.MIRRORING

    /** From [ConnectionState.set], any thread. */
    fun onPhase(p: Phase) {
        main.post {
            val ctx = app ?: return@post
            if (live(p)) {
                main.removeCallbacks(lostCheck)
                if (!wasLive) { wasLive = true; if (enabled(ctx)) play(UP) }
            } else if (wasLive) {
                if (p == Phase.STOPPED || p == Phase.IDLE) { wasLive = false; main.removeCallbacks(lostCheck) }   // the rider stopped it
                else { main.removeCallbacks(lostCheck); main.postDelayed(lostCheck, LOST_GRACE_MS) }
            }
        }
    }

    private val lostCheck = Runnable {
        val ctx = app ?: return@Runnable
        if (wasLive && !live(ConnectionState.phase)) {
            wasLive = false
            if (enabled(ctx)) play(DOWN)
        }
    }

    // Two notes each: E5 → A5 (connected) and A5 → E5 (lost).
    private val UP = doubleArrayOf(659.25, 880.0)
    private val DOWN = doubleArrayOf(880.0, 659.25)

    fun preview(ctx: Context) { init(ctx); play(UP) }

    private fun play(notes: DoubleArray) {
        Thread {
            try {
                val noteMs = 140; val gapMs = 40
                val per = RATE * noteMs / 1000; val gap = RATE * gapMs / 1000
                val pcm = ShortArray(notes.size * (per + gap))
                var i = 0
                for (f in notes) {
                    for (n in 0 until per) {
                        // Soft attack and decay so it chimes instead of clicking.
                        val env = min(1.0, n / (RATE * 0.008)) * (1.0 - n.toDouble() / per)
                        val v = sin(2 * PI * f * n / RATE) * 0.6 + sin(4 * PI * f * n / RATE) * 0.15
                        pcm[i++] = (v * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    }
                    i += gap
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((notes.size * (noteMs + gapMs) + 150).toLong())
                track.release()
            } catch (e: Exception) {
                LogBus.log("[SOUND] chime failed: $e")
            }
        }.start()
    }
}
