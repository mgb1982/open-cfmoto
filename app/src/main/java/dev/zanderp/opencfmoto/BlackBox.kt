// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * v2.3 · 🎥 Black box. While the crash detector is armed it keeps the last [WINDOW_MS] of motion in
 * memory: acceleration (peak per 100 ms), lean/tilt against the riding attitude, and GPS (speed,
 * heading, position, ~1 Hz). Nothing is written while riding normally. When the detector sees an
 * impact, the 60 s before it plus [AFTER_MS] after it are saved to a small JSON file, tagged with what
 * the detector concluded (alarm / upright at a light / rider moving again). [summary] turns it into the
 * figures an insurer or an accident report asks for. Only on the phone; shared only by the rider.
 */
object BlackBox {
    const val BEFORE_MS = 60_000L
    const val AFTER_MS = 30_000L
    private const val WINDOW_MS = BEFORE_MS + 5_000L
    private const val KEEP_FILES = 10

    data class Accel(val t: Long, val g: Float, val tilt: Float?)
    data class Fix(val t: Long, val lat: Double, val lon: Double, val kmh: Float?, val bearing: Float?, val acc: Float?)

    private val accel = ArrayDeque<Accel>()
    private val fixes = ArrayDeque<Fix>()
    private var bucketStart = 0L
    private var bucketPeak = 0f
    private var bucketTilt: Float? = null
    private var lastFixTime = 0L

    /** Set while an impact is being recorded (wall-clock ms of the impact). */
    @Volatile private var impactWall = 0L
    @Volatile private var impactOutcome = "watching"
    private val main = Handler(Looper.getMainLooper())

    /** Called by [CrashDetector] for every accelerometer sample (wall-clock time). */
    @Synchronized
    fun onAccel(nowWall: Long, g: Float, tilt: Float?) {
        if (bucketStart == 0L) bucketStart = nowWall
        if (g > bucketPeak) { bucketPeak = g; bucketTilt = tilt }
        if (nowWall - bucketStart >= 100) {
            accel.addLast(Accel(bucketStart, bucketPeak, bucketTilt ?: tilt))
            bucketStart = nowWall; bucketPeak = 0f; bucketTilt = null
        }
        val loc: Location? = TripRecorder.lastSeen
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            fixes.addLast(Fix(loc.time, loc.latitude, loc.longitude,
                if (loc.hasSpeed()) loc.speed * 3.6f else null,
                if (loc.hasBearing()) loc.bearing else null,
                if (loc.hasAccuracy()) loc.accuracy else null))
        }
        // While an impact is being recorded we keep everything until it is saved.
        if (impactWall == 0L) {
            while (accel.isNotEmpty() && nowWall - accel.first().t > WINDOW_MS) accel.removeFirst()
            while (fixes.isNotEmpty() && nowWall - fixes.first().t > WINDOW_MS) fixes.removeFirst()
        }
    }

    @Synchronized
    fun reset() {
        accel.clear(); fixes.clear(); bucketStart = 0L; bucketPeak = 0f; lastFixTime = 0L
        if (impactWall != 0L) main.removeCallbacksAndMessages(null)
        impactWall = 0L
    }

    /** The detector saw an impact: record [AFTER_MS] more, then save. */
    fun onImpact(ctx: Context, g: Float, kmh: Float?) {
        if (impactWall != 0L) return
        impactWall = System.currentTimeMillis()
        impactOutcome = "watching"
        LogBus.log("[BLACKBOX] impact ${"%.1f".format(g)} g at ${kmh?.toInt() ?: "?"} km/h — recording")
        val app = ctx.applicationContext
        main.postDelayed({ save(app) }, AFTER_MS)
    }

    /** What the detector concluded: "alarm", "upright", "moving", "test". Saved with the event. */
    fun onOutcome(outcome: String) {
        if (impactWall != 0L) impactOutcome = outcome
    }

    private fun save(ctx: Context) {
        val at = impactWall
        if (at == 0L) return
        val json = synchronized(this) {
            val from = at - BEFORE_MS
            val to = at + AFTER_MS
            JSONObject().apply {
                put("v", 1)
                put("impact", at)
                put("outcome", impactOutcome)
                put("accel", JSONArray().apply {
                    accel.filter { it.t in from..to }.forEach { a ->
                        put(JSONArray().put(a.t - at).put(round2(a.g)).put(a.tilt?.let { round1(it) } ?: JSONObject.NULL))
                    }
                })
                put("gps", JSONArray().apply {
                    fixes.filter { it.t in from..to }.forEach { f ->
                        put(JSONArray().put(f.t - at).put(f.lat).put(f.lon)
                            .put(f.kmh?.let { round1(it) } ?: JSONObject.NULL)
                            .put(f.bearing?.let { round1(it) } ?: JSONObject.NULL)
                            .put(f.acc?.let { round1(it) } ?: JSONObject.NULL))
                    }
                })
            }
        }
        impactWall = 0L
        try {
            val dir = dir(ctx).apply { mkdirs() }
            File(dir, "bb_$at.json").writeText(json.toString())
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_FILES)?.forEach { it.delete() }
            LogBus.log("[BLACKBOX] saved event ($impactOutcome)")
        } catch (e: Exception) {
            LogBus.log("[BLACKBOX] save failed: $e")
        }
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "blackbox")

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.startsWith("bb_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()

    // ── Report

    data class Summary(
        val impact: Long,
        val outcome: String,
        val peakG: Float,
        val kmhAtImpact: Float?,
        val kmh5sBefore: Float?,
        val maxDecel: Float?,          // km/h lost per second, worst second in the 5 s before
        val tiltAtImpact: Float?,
        val tiltAfter: Float?,
        val lat: Double?, val lon: Double?,
        val bearing: Float?,
        val accel: List<Accel>,        // t relative to impact
        val gps: List<Fix>,            // t relative to impact
    )

    fun load(file: File): Summary? = try {
        val o = JSONObject(file.readText())
        val impact = o.getLong("impact")
        val acc = o.getJSONArray("accel").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) }
            .map { Accel(it.getLong(0), it.getDouble(1).toFloat(), if (it.isNull(2)) null else it.getDouble(2).toFloat()) } }
        val gps = o.getJSONArray("gps").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) }
            .map { Fix(it.getLong(0), it.getDouble(1), it.getDouble(2),
                if (it.isNull(3)) null else it.getDouble(3).toFloat(),
                if (it.isNull(4)) null else it.getDouble(4).toFloat(),
                if (it.isNull(5)) null else it.getDouble(5).toFloat()) } }
        val before = gps.filter { it.t <= 0 }
        val atImpact = before.lastOrNull()
        val fiveBefore = before.lastOrNull { it.t <= -5_000L }
        var worst: Float? = null
        val recent = before.filter { it.t >= -6_000L && it.kmh != null }
        for (i in 1 until recent.size) {
            val dt = (recent[i].t - recent[i - 1].t) / 1000f
            if (dt <= 0f) continue
            val decel = (recent[i - 1].kmh!! - recent[i].kmh!!) / dt
            if (decel > 0 && (worst == null || decel > worst)) worst = decel
        }
        val peak = acc.filter { it.t in -1_000L..1_000L }.maxOfOrNull { it.g } ?: acc.maxOfOrNull { it.g } ?: 0f
        Summary(
            impact = impact,
            outcome = o.optString("outcome", "watching"),
            peakG = peak,
            kmhAtImpact = atImpact?.kmh,
            kmh5sBefore = fiveBefore?.kmh,
            maxDecel = worst,
            tiltAtImpact = acc.filter { it.t in -300L..300L }.mapNotNull { it.tilt }.maxOrNull(),
            tiltAfter = acc.filter { it.t > 15_000L }.mapNotNull { it.tilt }.lastOrNull(),
            lat = atImpact?.lat, lon = atImpact?.lon,
            bearing = atImpact?.bearing,
            accel = acc, gps = gps,
        )
    } catch (e: Exception) {
        LogBus.log("[BLACKBOX] can't read ${file.name}: $e"); null
    }

    /** GPX of the recorded track, for any map tool. */
    fun gpx(s: Summary): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"RideScreen AA\">\n<trk><name>RideScreen AA black box</name><trkseg>\n")
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        s.gps.forEach { f -> append("<trkpt lat=\"${f.lat}\" lon=\"${f.lon}\"><time>${iso.format(java.util.Date(s.impact + f.t))}</time></trkpt>\n") }
        append("</trkseg></trk>\n</gpx>\n")
    }

    private fun round1(f: Float) = Math.round(f * 10) / 10.0
    private fun round2(f: Float) = Math.round(f * 100) / 100.0
    @Suppress("unused") private fun maxOf0(a: Float, b: Float) = max(a, b)
}
