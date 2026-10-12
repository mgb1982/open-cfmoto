// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 🚦 "Your red lights" (v2.2), computed from the 1 Hz tracks trips already store.
 *
 *  1. Traffic signals come from OpenStreetMap (Overpass), fetched once per ~5 km cell the rider has
 *     ridden through and cached on disk. Signal nodes within [JUNCTION_MERGE_M] are one junction.
 *  2. Every time a track goes through a junction (within [PASS_RADIUS_M]) it's a *pass*, keyed by the
 *     approach direction (N/E/S/W). A pass is a *stop* if the bike stood still (< [STOP_MS] m/s) for
 *     ≥ [MIN_STOP_S] s near it; the wait and the moment it moved off (≈ green) are kept.
 *  3. Same-name trips (A → B) are split into route variants by the cells they cross, and compared
 *     by median door-to-door time and red-light stops.
 *  4. Experimental: for approaches with enough stops, the signal cycle is estimated from the
 *     move-off times (the period that lines them up best). Only shown when the fit is strong.
 */
object TrafficLights {
    private const val CELL_DEG = 0.05            // Overpass cache cell (~5.5 km)
    private const val JUNCTION_MERGE_M = 35.0
    private const val PASS_RADIUS_M = 45.0
    private const val STOP_MS = 1.5f             // < 5.4 km/h = stopped
    private const val MIN_STOP_S = 4
    private const val MAX_STOP_S = 240           // longer = parked / coffee, not a light
    private const val MIN_PASSES_SHOWN = 2

    data class Junction(val id: Int, val lat: Double, val lon: Double)

    class Approach(val junction: Junction, val dir: Int) {
        var passes = 0
        var stops = 0
        var waitS = 0
        val greenStarts = ArrayList<Long>()      // epoch ms when the bike moved off
        val stopRate: Float get() = if (passes == 0) 0f else stops.toFloat() / passes
        val avgWaitS: Int get() = if (stops == 0) 0 else waitS / stops
        var cycleS: Int? = null
        var cycleConfidence = 0f
    }

    class JunctionStats(val junction: Junction, val approaches: List<Approach>) {
        val passes get() = approaches.sumOf { it.passes }
        val stops get() = approaches.sumOf { it.stops }
        val stopRate: Float get() = if (passes == 0) 0f else stops.toFloat() / passes
        val avgWaitS: Int get() = approaches.sumOf { it.waitS }.let { w -> if (stops == 0) 0 else w / stops }
        /** The approach the rider uses most (what the list shows). */
        val main: Approach get() = approaches.maxByOrNull { it.passes } ?: approaches.first()
    }

    class Variant(val trips: List<Trip>, val signalStops: List<Int>) {
        val count get() = trips.size
        val medianMin: Int get() = median(trips.map { ((it.end - it.start) / 60_000L).toInt() })
        val medianStops: Int get() = median(signalStops)
        val medianKm: Double get() = trips.map { it.distanceKm }.sorted()[trips.size / 2]
        val sample: Trip get() = trips.maxByOrNull { it.start }!!
    }

    class RouteGroup(val name: String, val variants: List<Variant>)

    class Result(
        val junctions: List<JunctionStats>,
        val routes: List<RouteGroup>,
        val tripsUsed: Int,
        val signalsKnown: Int,
        val offline: Boolean,
    )

    // ── Signals from OSM, cached per cell

    private fun cacheDir(ctx: Context) = File(ctx.filesDir, "signals").apply { mkdirs() }

    private fun cellKey(lat: Double, lon: Double) =
        "${floor(lat / CELL_DEG).toInt()}_${floor(lon / CELL_DEG).toInt()}"

    /** Blocking (network for uncached cells). @return signal nodes, and whether some cell failed. */
    private fun signalsFor(ctx: Context, trips: List<Trip>, progress: (String) -> Unit): Pair<List<DoubleArray>, Boolean> {
        val cells = LinkedHashSet<String>()
        for (t in trips) for (p in t.points) cells += cellKey(p.lat, p.lon)
        val out = ArrayList<DoubleArray>()
        var failed = false
        var done = 0
        for (key in cells) {
            done++
            val f = File(cacheDir(ctx), "$key.json")
            val text = if (f.isFile && System.currentTimeMillis() - f.lastModified() < 60L * 24 * 3600_000L) {
                f.readText()
            } else {
                progress(ctx.getString(R.string.tl_progress_signals, done, cells.size))
                try {
                    val (y, x) = key.split('_').map { it.toInt() }
                    val s = y * CELL_DEG; val w = x * CELL_DEG
                    val q = String.format(
                        Locale.ROOT,
                        "[out:json][timeout:25];node[\"highway\"=\"traffic_signals\"](%.4f,%.4f,%.4f,%.4f);out skel;",
                        s, w, s + CELL_DEG, w + CELL_DEG,
                    )
                    AppHttp.throttle("overpass-api.de", 1_000)
                    val body = AppHttp.postForm("https://overpass-api.de/api/interpreter", "data=" + AppHttp.encode(q))
                    val arr = JSONObject(body).optJSONArray("elements") ?: JSONArray()
                    val slim = JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        slim.put(JSONArray().put(e.optDouble("lat")).put(e.optDouble("lon")))
                    }
                    slim.toString().also { f.writeText(it) }
                } catch (e: Exception) {
                    LogBus.log("[TL] signals for $key failed: ${e.message}")
                    failed = true
                    null
                }
            } ?: continue
            try {
                val a = JSONArray(text)
                for (i in 0 until a.length()) a.getJSONArray(i).let { out += doubleArrayOf(it.getDouble(0), it.getDouble(1)) }
            } catch (_: Exception) {}
        }
        return out to failed
    }

    private fun clusterJunctions(nodes: List<DoubleArray>): List<Junction> {
        val grid = HashMap<Long, MutableList<Int>>()
        val sumLat = ArrayList<Double>(); val sumLon = ArrayList<Double>(); val n = ArrayList<Int>()
        fun g(lat: Double, lon: Double): Long = (floor(lat / 0.0005).toLong() shl 32) xor (floor(lon / 0.0005).toLong() and 0xffffffffL)
        for (p in nodes) {
            var best = -1
            var bestD = JUNCTION_MERGE_M
            val gy = floor(p[0] / 0.0005).toLong(); val gx = floor(p[1] / 0.0005).toLong()
            for (dy in -1L..1L) for (dx in -1L..1L) {
                val k = ((gy + dy) shl 32) xor ((gx + dx) and 0xffffffffL)
                for (j in grid[k].orEmpty()) {
                    val d = metres(p[0], p[1], sumLat[j] / n[j], sumLon[j] / n[j])
                    if (d < bestD) { bestD = d; best = j }
                }
            }
            if (best >= 0) {
                sumLat[best] += p[0]; sumLon[best] += p[1]; n[best] += 1
            } else {
                sumLat += p[0]; sumLon += p[1]; n += 1
                grid.getOrPut(g(p[0], p[1])) { ArrayList() } += n.lastIndex
            }
        }
        return n.indices.map { Junction(it, sumLat[it] / n[it], sumLon[it] / n[it]) }
    }

    // ── Analysis

    fun analyse(ctx: Context, progress: (String) -> Unit): Result {
        progress(ctx.getString(R.string.tl_progress_trips))
        val trips = TripStore.list(ctx).filter { it.points.size >= 30 }
        if (trips.isEmpty()) return Result(emptyList(), emptyList(), 0, 0, false)
        val (nodes, failed) = signalsFor(ctx, trips, progress)
        progress(ctx.getString(R.string.tl_progress_analysing))
        val junctions = clusterJunctions(nodes)
        val jGrid = HashMap<Long, MutableList<Junction>>()
        fun key(lat: Double, lon: Double) = (floor(lat / 0.001).toLong() shl 32) xor (floor(lon / 0.001).toLong() and 0xffffffffL)
        for (j in junctions) jGrid.getOrPut(key(j.lat, j.lon)) { ArrayList() } += j
        fun near(lat: Double, lon: Double): Junction? {
            val gy = floor(lat / 0.001).toLong(); val gx = floor(lon / 0.001).toLong()
            var best: Junction? = null; var bestD = PASS_RADIUS_M
            for (dy in -1L..1L) for (dx in -1L..1L) {
                for (j in jGrid[((gy + dy) shl 32) xor ((gx + dx) and 0xffffffffL)].orEmpty()) {
                    val d = metres(lat, lon, j.lat, j.lon)
                    if (d < bestD) { bestD = d; best = j }
                }
            }
            return best
        }

        val approaches = HashMap<Long, Approach>()
        val stopsPerTrip = HashMap<String, Int>()
        for (t in trips) {
            val pts = t.points
            var i = 0
            var tripStops = 0
            while (i < pts.size) {
                val j = near(pts[i].lat, pts[i].lon)
                if (j == null) { i++; continue }
                // The pass: consecutive points near this junction.
                var end = i
                while (end + 1 < pts.size && metres(pts[end + 1].lat, pts[end + 1].lon, j.lat, j.lon) < PASS_RADIUS_M) end++
                val from = pts[(i - 8).coerceAtLeast(0)]
                val dir = if (metres(from.lat, from.lon, pts[i].lat, pts[i].lon) > 8) direction(from, pts[i]) else -1
                // Longest still run inside the pass (+ the few points before it: queues reach back).
                var bestRun = 0; var run = 0; var bestRunEnd = -1
                for (k in (i - 4).coerceAtLeast(0)..end) {
                    if (pts[k].speedMs < STOP_MS) { run++; if (run > bestRun) { bestRun = run; bestRunEnd = k } } else run = 0
                }
                val runS = if (bestRun >= 2 && bestRunEnd >= 0)
                    ((pts[bestRunEnd].time - pts[bestRunEnd - bestRun + 1].time) / 1000L).toInt() + 1 else 0
                if (dir >= 0 && runS <= MAX_STOP_S) {
                    val a = approaches.getOrPut(j.id.toLong() * 8 + dir) { Approach(j, dir) }
                    a.passes++
                    if (runS >= MIN_STOP_S) {
                        a.stops++; a.waitS += runS; tripStops++
                        val off = bestRunEnd + 1
                        if (off < pts.size && off <= end + 5) a.greenStarts += pts[off].time
                    }
                }
                i = end + 1
            }
            stopsPerTrip[t.id] = tripStops
        }
        approaches.values.forEach { estimateCycle(it) }
        val byJunction = approaches.values.groupBy { it.junction.id }
            .map { (_, list) -> JunctionStats(list.first().junction, list) }
            .filter { it.passes >= MIN_PASSES_SHOWN }
            .sortedWith(compareByDescending<JunctionStats> { it.stops }.thenByDescending { it.stopRate })

        return Result(byJunction, routeGroups(ctx, trips, stopsPerTrip), trips.size, nodes.size, failed)
    }

    /** Same-name trips (A → B) split into variants by the ~200 m cells they cross. */
    private fun routeGroups(ctx: Context, trips: List<Trip>, stops: Map<String, Int>): List<RouteGroup> {
        val named = trips.mapNotNull { t -> TripNames.cached(ctx, t)?.takeIf { it.contains("→") }?.let { it to t } }
        val out = ArrayList<RouteGroup>()
        for ((name, list) in named.groupBy({ it.first }, { it.second })) {
            if (list.size < 2) continue
            val cells = list.associateWith { t -> t.points.map { (floor(it.lat / 0.002).toLong() shl 32) xor (floor(it.lon / 0.002).toLong() and 0xffffffffL) }.toSet() }
            val groups = ArrayList<MutableList<Trip>>()
            for (t in list.sortedBy { it.start }) {
                val home = groups.firstOrNull { g -> jaccard(cells.getValue(g.first()), cells.getValue(t)) >= 0.6 }
                if (home != null) home += t else groups += mutableListOf(t)
            }
            val variants = groups.map { g -> Variant(g, g.map { stops[it.id] ?: 0 }) }
                .sortedWith(compareByDescending<Variant> { it.count }.thenBy { it.medianMin })
            out += RouteGroup(name, variants)
        }
        return out.sortedWith(compareByDescending<RouteGroup> { it.variants.size }.thenByDescending { g -> g.variants.sumOf { it.count } })
    }

    /** Period (60–150 s) that best lines up the move-off times; kept only if the fit is strong. */
    private fun estimateCycle(a: Approach) {
        val ts = a.greenStarts.map { it / 1000.0 }
        if (ts.size < 6) return
        var bestC = 0; var bestR = 0.0
        for (c in 60..150) {
            var sx = 0.0; var sy = 0.0
            for (t in ts) { val ph = 2 * PI * ((t % c) / c); sx += cos(ph); sy += sin(ph) }
            val r = hypot(sx, sy) / ts.size
            if (r > bestR + 0.02) { bestR = r; bestC = c }
        }
        if (bestR >= 0.7) { a.cycleS = bestC; a.cycleConfidence = bestR.toFloat() }
    }

    // ── Helpers

    /** 0 = heading north, 1 = east, 2 = south, 3 = west. */
    private fun direction(a: TrackPoint, b: TrackPoint): Int {
        val y = b.lat - a.lat
        val x = (b.lon - a.lon) * cos(Math.toRadians(a.lat))
        val deg = (Math.toDegrees(atan2(x, y)) + 360) % 360
        return (((deg + 45) % 360) / 90).toInt()
    }

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    private fun jaccard(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    private fun median(v: List<Int>): Int = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]

    // ── Junction names (street), cached; only fetched for the few shown

    private fun namePrefs(ctx: Context) = ctx.getSharedPreferences("tl_names", Context.MODE_PRIVATE)

    fun cachedName(ctx: Context, j: Junction): String? =
        namePrefs(ctx).getString(String.format(Locale.ROOT, "%.4f,%.4f", j.lat, j.lon), null)

    /** Blocking. Street name at the junction (Nominatim, zoom 17). */
    fun fetchName(ctx: Context, j: Junction): String? {
        cachedName(ctx, j)?.let { return it }
        return try {
            AppHttp.throttle("nominatim.openstreetmap.org", 1_100)
            val body = AppHttp.getText(
                String.format(Locale.ROOT,
                    "https://nominatim.openstreetmap.org/reverse?lat=%.5f&lon=%.5f&format=jsonv2&zoom=17&addressdetails=1&accept-language=%s",
                    j.lat, j.lon, Locale.getDefault().language),
            )
            val addr = JSONObject(body).optJSONObject("address")
            val name = addr?.optString("road")?.takeIf { it.isNotBlank() }
                ?: JSONObject(body).optString("name").takeIf { it.isNotBlank() }
            if (name != null) namePrefs(ctx).edit().putString(String.format(Locale.ROOT, "%.4f,%.4f", j.lat, j.lon), name).apply()
            name
        } catch (_: Exception) { null }
    }
}
