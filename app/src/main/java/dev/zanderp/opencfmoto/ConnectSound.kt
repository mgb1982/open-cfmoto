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
                if (!wasLive) { wasLive = true; if (enabled(ctx)) play(up = true) }
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
            if (enabled(ctx)) play(up = false)
        }
    }

    /** One tone: frequency glides from [f0] to [f1]; [square] gives the 8-bit flavour. */
    class Seg(val f0: Double, val f1: Double, val ms: Int, val gapMs: Int = 40, val square: Boolean = false)

    /** The chimes to choose from. Lost = the connected one played backwards (pitch falls). */
    enum class Style(val labelRes: Int, val up: List<Seg>) {
        CHIME(R.string.sound_chime, listOf(Seg(659.25, 659.25, 140), Seg(880.0, 880.0, 160))),
        ARPEGGIO(R.string.sound_arpeggio, listOf(Seg(523.25, 523.25, 100, 25), Seg(659.25, 659.25, 100, 25), Seg(783.99, 783.99, 170))),
        FOB(R.string.sound_fob, listOf(Seg(1318.5, 1318.5, 70, 70), Seg(1318.5, 1318.5, 70))),
        SWELL(R.string.sound_swell, listOf(Seg(330.0, 660.0, 380, 0))),
        RETRO(R.string.sound_retro, listOf(Seg(392.0, 392.0, 60, 10, true), Seg(523.25, 523.25, 60, 10, true), Seg(659.25, 659.25, 60, 10, true), Seg(1046.5, 1046.5, 130, 0, true))),
        SYSTEM(R.string.sound_system, emptyList());

        val down: List<Seg> get() = up.reversed().map { Seg(it.f1, it.f0, it.ms, it.gapMs, it.square) }
    }

    fun style(ctx: Context): Style = try {
        Style.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("style", Style.CHIME.name)!!)
    } catch (_: Exception) { Style.CHIME }

    fun setStyle(ctx: Context, s: Style) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", s.name).apply()

    fun preview(ctx: Context, s: Style = style(ctx)) { init(ctx); play(s, up = true) }

    private fun play(up: Boolean) { app?.let { play(style(it), up) } }

    private fun play(style: Style, up: Boolean) {
        if (style == Style.SYSTEM) { playSystem(); return }
        val segs = if (up) style.up else style.down
        Thread {
            try {
                val total = segs.sumOf { RATE * (it.ms + it.gapMs) / 1000 }
                val pcm = ShortArray(total)
                var i = 0
                for (sg in segs) {
                    val per = RATE * sg.ms / 1000
                    var phase = 0.0
                    for (n in 0 until per) {
                        val f = sg.f0 + (sg.f1 - sg.f0) * n / per
                        phase += 2 * PI * f / RATE
                        // Soft attack and decay so it chimes instead of clicking.
                        val env = min(1.0, n / (RATE * 0.008)) * (1.0 - n.toDouble() / per)
                        val v = if (sg.square) (if (sin(phase) >= 0) 0.35 else -0.35)
                            else sin(phase) * 0.6 + sin(2 * phase) * 0.15
                        pcm[i++] = (v * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
                    }
                    i += RATE * sg.gapMs / 1000
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
                Thread.sleep(total * 1000L / RATE + 150)
                track.release()
            } catch (e: Exception) {
                LogBus.log("[SOUND] chime failed: $e")
            }
        }.start()
    }

    /** The phone's own notification sound (same for connected and lost). */
    private fun playSystem() {
        val ctx = app ?: return
        try {
            val r = android.media.RingtoneManager.getRingtone(ctx,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)) ?: return
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            r.play()
        } catch (e: Exception) { LogBus.log("[SOUND] system sound failed: $e") }
    }
}
