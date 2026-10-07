// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * "Share my ride live": a temporary link (RideScreen AA's own Cloudflare Worker, telemetry/src/live.js)
 * that shows where you are on a map. Off until you start it; it stops by itself a few minutes after
 * the ride ends, when you stop it, or after 12 h. Positions are deleted a day after it ends.
 */
object LiveShare {
    private const val PREFS = "live_share"
    private const val SEND_EVERY_MS = 10_000L
    private const val AUTO_END_AFTER_RIDE_MS = 3 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "live-share").apply { isDaemon = true } }

    @Volatile private var appCtx: Context? = null
    @Volatile private var id: String? = null
    @Volatile private var token: String? = null
    @Volatile var url: String? = null
        private set
    private var listening = false
    @Volatile private var fix: Location? = null
    @Volatile private var lastSentFix = 0L

    val available: Boolean get() = BuildConfig.TELEMETRY_URL.isNotBlank()
    val active: Boolean get() = id != null

    private val tick = object : Runnable {
        override fun run() {
            if (id == null) return
            sendPosition()
            main.postDelayed(this, SEND_EVERY_MS)
        }
    }

    private val autoEnd = Runnable { stop(reason = "ride ended") }

    private val gps = LocationListener { l -> fix = l }

    /** Asks for the name shown on the page (first time), creates the link and opens the share sheet. */
    fun begin(activity: Activity) {
        if (!available) return
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val input = EditText(activity).apply {
            setText(p.getString("name", ""))
            hint = activity.getString(R.string.live_name_hint)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.live_title)
            .setMessage(R.string.live_explain)
            .setView(input)
            .setPositiveButton(R.string.live_start) { _, _ ->
                val name = input.text.toString().trim().take(40)
                p.edit().putString("name", name).apply()
                create(activity, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun create(activity: Activity, name: String) {
        val app = activity.applicationContext
        appCtx = app
        io.execute {
            try {
                val res = post("${base()}/v1/live", JSONObject().put("name", name).toString(), null)
                val o = JSONObject(res)
                id = o.getString("id")
                token = o.getString("token")
                url = o.getString("url")
                LogBus.log("[LIVE] sharing started")
                main.post {
                    startGps(app)
                    main.removeCallbacks(tick)
                    main.post(tick)
                    shareLink(activity)
                }
            } catch (e: Exception) {
                LogBus.log("[LIVE] start failed: ${e.message}")
                main.post { Toast.makeText(activity, R.string.live_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun shareLink(activity: Activity) {
        val u = url ?: return
        try {
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.live_share_text, u)),
                    activity.getString(R.string.live_title),
                )
            )
        } catch (_: Exception) {
        }
    }

    fun stop(reason: String = "user") {
        val i = id ?: return
        val t = token
        id = null
        token = null
        url = null
        main.removeCallbacks(tick)
        main.removeCallbacks(autoEnd)
        stopGps()
        LogBus.log("[LIVE] sharing stopped ($reason)")
        io.execute { runCatching { post("${base()}/v1/live/$i/end", "{}", t) } }
    }

    /** Called from RideExtras.onSession: end a few minutes after the ride (not on a quick reconnect). */
    fun onSession(active: Boolean) {
        main.post {
            main.removeCallbacks(autoEnd)
            if (!active && id != null) main.postDelayed(autoEnd, AUTO_END_AFTER_RIDE_MS)
        }
    }

    private fun sendPosition() {
        val ctx = appCtx ?: return
        val i = id ?: return
        val t = token ?: return
        val loc = fix?.takeIf { System.currentTimeMillis() - it.time < 60_000 }
            ?: RideExtras.lastLocation(ctx, 60_000) ?: return
        // Same fix as last time (stopped, or no new GPS): nothing new to send.
        if (loc.time == lastSentFix) return
        lastSentFix = loc.time
        val body = JSONObject()
            .put("lat", loc.latitude)
            .put("lon", loc.longitude)
            .put("spd", if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0)
            .put("ts", loc.time)
            .toString()
        io.execute {
            try {
                post("${base()}/v1/live/$i", body, t)
            } catch (e: HttpGone) {
                main.post { stop(reason = "expired") }
            } catch (_: Exception) {
                // No signal for a moment: the next tick tries again.
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps(ctx: Context) {
        if (listening) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 10f, gps, Looper.getMainLooper())
            listening = true
        } catch (_: Exception) {
        }
    }

    private fun stopGps() {
        val ctx = appCtx ?: return
        if (!listening) return
        listening = false
        try {
            (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(gps)
        } catch (_: Exception) {
        }
    }

    private class HttpGone : Exception()

    private fun base() = BuildConfig.TELEMETRY_URL.trim().trimEnd('/')

    private fun post(url: String, body: String, bearer: String?): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = AppHttp.openUrl(url)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("User-Agent", AppHttp.USER_AGENT)
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        // No disconnect(): reading the body to the end and closing it lets the connection be
        // reused for the next update instead of a new TLS handshake every 10 s.
        if (code == 410 || code == 404) {
            runCatching { conn.errorStream?.use { it.readBytes() } }
            throw HttpGone()
        }
        if (code !in 200..299) {
            runCatching { conn.errorStream?.use { it.readBytes() } }
            throw java.io.IOException("HTTP $code")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
