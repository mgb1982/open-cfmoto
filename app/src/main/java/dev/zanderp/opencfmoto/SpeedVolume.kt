// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import kotlin.math.roundToInt

/**
 * Speed-compensated media volume, like a car's "speed volume": louder as wind noise grows.
 *
 * Android Auto plays nav prompts and music straight from the phone to the helmet intercom, so we
 * only move the phone's STREAM_MUSIC. The rider's own volume is the **base**; we add a **boost** in
 * steps above a start speed, up to a cap. Any volume change we did not make (phone keys, intercom,
 * bike ▲/▼ in "control media" mode, the Controls slider) re-bases, so the rider stays in charge.
 *
 * Runs only while the bike link is up ([Phase.STREAMING] / [Phase.MIRRORING], kept through a short
 * [Phase.RECONNECTING]) and never while [MediaButtonBridge] pins the volume for handlebar nav —
 * the two would fight over the same stream.
 */
enum class SpeedVolumeLevel(
    val id: String,
    @StringRes val labelRes: Int,
    val startKmh: Int,
    val kmhPerStep: Int,
    val maxSteps: Int,
) {
    OFF("off", R.string.speed_volume_off, 0, 1, 0),
    LOW("low", R.string.speed_volume_low, 50, 25, 2),
    MEDIUM("medium", R.string.speed_volume_medium, 40, 20, 3),
    HIGH("high", R.string.speed_volume_high, 30, 15, 5),
    ;

    companion object {
        fun byId(id: String?): SpeedVolumeLevel = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The curve in use: a [SpeedVolumeLevel] preset, or the rider's custom one. */
data class SpeedVolumeCurve(val startKmh: Int, val kmhPerStep: Int, val maxSteps: Int) {
    /** Boost steps (on a 15-step scale) for a given speed. */
    fun stepsFor(kmh: Float): Int {
        if (maxSteps <= 0 || kmh < startKmh) return 0
        val steps = ((kmh - startKmh) / kmhPerStep.coerceAtLeast(1)).toInt() + 1
        return steps.coerceIn(0, maxSteps)
    }
}

object SpeedVolumePrefs {
    private const val PREFS = "opencfmoto_speed_volume"
    private const val KEY_LEVEL = "level"
    private const val KEY_CUSTOM = "custom_enabled"
    private const val KEY_START = "custom_start_kmh"
    private const val KEY_PER_STEP = "custom_kmh_per_step"
    private const val KEY_MAX = "custom_max_steps"

    const val DEFAULT_START = 40
    const val DEFAULT_PER_STEP = 20
    const val DEFAULT_MAX = 3

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun level(ctx: Context) = SpeedVolumeLevel.byId(prefs(ctx).getString(KEY_LEVEL, null))
    fun setLevel(ctx: Context, level: SpeedVolumeLevel) =
        prefs(ctx).edit().putString(KEY_LEVEL, level.id).apply()

    fun customEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_CUSTOM, false)
    fun setCustomEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_CUSTOM, on).apply()

    fun customStart(ctx: Context) = prefs(ctx).getInt(KEY_START, DEFAULT_START)
    fun customPerStep(ctx: Context) = prefs(ctx).getInt(KEY_PER_STEP, DEFAULT_PER_STEP)
    fun customMax(ctx: Context) = prefs(ctx).getInt(KEY_MAX, DEFAULT_MAX)
    fun setCustom(ctx: Context, startKmh: Int, kmhPerStep: Int, maxSteps: Int) =
        prefs(ctx).edit()
            .putInt(KEY_START, startKmh.coerceIn(0, 150))
            .putInt(KEY_PER_STEP, kmhPerStep.coerceIn(5, 50))
            .putInt(KEY_MAX, maxSteps.coerceIn(1, 8))
            .apply()

    /** Enabled = a level other than Off is selected (the custom curve replaces the level's curve). */
    fun enabled(ctx: Context) = level(ctx) != SpeedVolumeLevel.OFF

    fun curve(ctx: Context): SpeedVolumeCurve {
        val lvl = level(ctx)
        return if (customEnabled(ctx)) {
            SpeedVolumeCurve(customStart(ctx), customPerStep(ctx), customMax(ctx))
        } else {
            SpeedVolumeCurve(lvl.startKmh, lvl.kmhPerStep, lvl.maxSteps)
        }
    }
}

object SpeedVolume {
    /** One volume step up is applied after the target has been higher for this many ticks. */
    private const val UP_TICKS = 2
    /** …and one step down after it has been lower for this many (slower, so lights don't dip it). */
    private const val DOWN_TICKS = 4
    /** Stepping down needs the speed to fall this far below a step's threshold (anti-hunting). */
    private const val HYSTERESIS_KMH = 6f
    private const val TICK_MS = 1_000L
    /** Hold the current boost when GPS has gone quiet (tunnel, parking garage). */
    private const val STALE_FIX_MS = 5_000L
    /** Ignore fixes whose speed is this uncertain (m/s). */
    private const val MAX_SPEED_ACCURACY_MS = 3f
    private const val SMOOTHING = 0.35f
    /** The curves are written for a 15-step media stream; other maxima are scaled. */
    private const val REFERENCE_STEPS = 15f

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var audio: AudioManager? = null
    private var lm: LocationManager? = null

    @Volatile var running = false
        private set
    private var baseVolume = -1
    /** Volume we last wrote; anything else seen on the stream is a manual change. */
    private var lastSet = -1
    /** Current boost in curve steps (0..curve.maxSteps). */
    @Volatile var boostSteps = 0
        private set
    @Volatile var speedKmh = -1f
        private set
    private var lastFixAt = 0L
    private var upCount = 0
    private var downCount = 0
    private var pausedForPin = false

    // Full object (not a SAM lambda): on API 29 the provider/status callbacks are still abstract.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { LogBus.log("[SPDVOL] tick failed: $e") }
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Start or stop to match settings + connection state. Safe to call often (service watchdog tick,
     * teardown, Controls screen). Always hops to the main thread.
     */
    fun sync(context: Context) {
        val ctx = context.applicationContext
        handler.post {
            val phase = ConnectionState.phase
            val linkUp = phase == Phase.STREAMING || phase == Phase.MIRRORING ||
                (running && phase == Phase.RECONNECTING)
            val want = AndroidAutoService.isRunning && linkUp && SpeedVolumePrefs.enabled(ctx) &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (want && !running) start(ctx) else if (!want && running) stop("${phase.logLabel}")
        }
    }

    private fun start(ctx: Context) {
        app = ctx
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val loc = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (loc == null) {
            LogBus.log("[SPDVOL] no LocationManager — speed volume unavailable")
            return
        }
        audio = am
        lm = loc
        try {
            loc.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            LogBus.log("[SPDVOL] location permission missing — not starting")
            return
        } catch (e: Exception) {
            LogBus.log("[SPDVOL] GPS unavailable ($e) — not starting")
            return
        }
        running = true
        baseVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        lastSet = -1
        boostSteps = 0
        speedKmh = -1f
        lastFixAt = 0L
        upCount = 0
        downCount = 0
        pausedForPin = false
        val c = SpeedVolumePrefs.curve(ctx)
        LogBus.log("[SPDVOL] on — level=${SpeedVolumePrefs.level(ctx).id}" +
            (if (SpeedVolumePrefs.customEnabled(ctx)) " (custom)" else "") +
            " start=${c.startKmh}km/h step=${c.kmhPerStep}km/h max=+${c.maxSteps} base=$baseVolume/" +
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stop(reason: String) {
        handler.removeCallbacks(tick)
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        val am = audio
        // Give the rider their own level back — but only if nobody touched the volume since our write.
        if (am != null && lastSet >= 0 && boostSteps > 0 && baseVolume >= 0) {
            try {
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == lastSet) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0)
                }
            } catch (_: Exception) {}
        }
        LogBus.log("[SPDVOL] off ($reason) — volume back to base=$baseVolume")
        running = false
        boostSteps = 0
        speedKmh = -1f
        lastSet = -1
    }

    private fun onLocation(loc: Location) {
        if (!loc.hasSpeed()) return
        if (loc.hasSpeedAccuracy() && loc.speedAccuracyMetersPerSecond > MAX_SPEED_ACCURACY_MS) return
        val kmh = (loc.speed * 3.6f).coerceAtLeast(0f)
        speedKmh = if (speedKmh < 0f) kmh else speedKmh + SMOOTHING * (kmh - speedKmh)
        lastFixAt = System.currentTimeMillis()
    }

    private fun step() {
        val ctx = app ?: return
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Handlebar nav mode owns the stream: stand aside and drop our boost.
        if (MediaButtonBridge.instance?.isVolumePinned == true) {
            if (!pausedForPin) LogBus.log("[SPDVOL] paused — handlebar buttons are pinning the volume")
            pausedForPin = true
            boostSteps = 0
            lastSet = -1
            return
        }
        if (pausedForPin) {
            pausedForPin = false
            baseVolume = cur
            LogBus.log("[SPDVOL] resumed — base=$baseVolume/$max")
        }

        // Someone else moved the volume → that is the rider's new base.
        if (lastSet >= 0 && cur != lastSet) {
            baseVolume = (cur - scaled(boostSteps, max)).coerceIn(0, max)
            LogBus.log("[SPDVOL] manual volume change $lastSet→$cur — new base=$baseVolume (boost +$boostSteps)")
        }
        if (baseVolume < 0) baseVolume = cur

        val curve = SpeedVolumePrefs.curve(ctx)
        val fresh = lastFixAt > 0 && System.currentTimeMillis() - lastFixAt <= STALE_FIX_MS
        if (fresh) {
            val v = speedKmh
            val up = curve.stepsFor(v)
            val down = curve.stepsFor(v + HYSTERESIS_KMH)
            when {
                up > boostSteps -> {
                    downCount = 0
                    if (++upCount >= UP_TICKS) { boostSteps++; upCount = 0 }
                }
                down < boostSteps -> {
                    upCount = 0
                    if (++downCount >= DOWN_TICKS) { boostSteps--; downCount = 0 }
                }
                else -> { upCount = 0; downCount = 0 }
            }
            boostSteps = boostSteps.coerceIn(0, curve.maxSteps)
        }

        val desired = (baseVolume + scaled(boostSteps, max)).coerceIn(0, max)
        if (desired != cur) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, desired, 0)
            LogBus.log("[SPDVOL] ${"%.0f".format(speedKmh)} km/h → boost +$boostSteps, volume $cur→$desired/$max")
        }
        lastSet = desired
    }

    /** Curve steps → stream steps, so a 30- or 150-step media stream gets the same loudness change. */
    private fun scaled(steps: Int, max: Int): Int =
        if (steps <= 0) 0 else (steps * max / REFERENCE_STEPS).roundToInt().coerceAtLeast(steps.coerceAtMost(1))

    /** One-line live status for the Controls screen. */
    fun status(ctx: Context): String = when {
        !SpeedVolumePrefs.enabled(ctx) -> ctx.getString(R.string.speed_volume_status_off)
        !running -> ctx.getString(R.string.speed_volume_status_waiting)
        pausedForPin -> ctx.getString(R.string.speed_volume_status_paused)
        speedKmh < 0f -> ctx.getString(R.string.speed_volume_status_no_gps)
        else -> ctx.getString(R.string.speed_volume_status_live, speedKmh.roundToInt(), boostSteps)
    }
}
