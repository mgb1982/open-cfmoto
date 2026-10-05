// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/**
 * Watch "Trips" page: the list of recent rides (a small JSON message) and, per ride, a map image the
 * PHONE renders — MapLibre snapshot of the OpenFreeMap dark style with the track drawn on top — and
 * sends as a Data Layer asset (too big for a message). The watch never downloads tiles itself.
 * No network → the track alone on black, so the page still shows the shape of the ride.
 */
object WearTrips {
    const val PATH_TRIPS = "/ocm/trips"
    const val PATH_TRIPMAP = "/ocm/tripmap"

    private const val MAX_TRIPS = 10
    private const val SNAPSHOT_TIMEOUT_MS = 15_000L
    private const val MAX_ZOOM = 4.0
    /** Zoomed images cover this many screens across, so the watch can pan them. */
    private const val PAN_FACTOR = 2
    private const val MAX_IMAGE_PX = 1000
    /** Overview maps rendered ahead when the watch opens the list, so the first tap is instant. */
    private const val PREFETCH = 3
    const val OVERVIEW_KEY = "overview"

    private val main = Handler(Looper.getMainLooper())

    /** One map to render. Jobs run one at a time on the main thread (MapSnapshotter's home). */
    private class Job(
        val ctx: Context, val id: String, val key: String, val z: Double,
        val u: Double, val v: Double, val px: Int, val density: Float,
    )
    private val queue = ArrayDeque<Job>()
    private var busy = false

    private fun enqueue(job: Job, urgent: Boolean) = main.post {
        queue.removeAll { it.id == job.id && it.key == job.key }
        if (urgent) queue.addFirst(job) else queue.addLast(job)
        if (!busy) next()
    }

    private fun next() {
        val job = queue.removeFirstOrNull() ?: run { busy = false; return }
        busy = true
        prepare(job)
    }

    private fun done() = main.post { next() }

    /** Reply to the watch with the last [MAX_TRIPS] rides (summary only); then pre-render overviews. */
    fun sendList(ctx: Context, node: String, requestText: String = "") {
        val req = try { JSONObject(requestText) } catch (_: Exception) { null }
        val px = (req?.optInt("px", 0) ?: 0)
        val density = (req?.optDouble("density", 2.0) ?: 2.0).toFloat().coerceIn(1f, 4f)
        Thread({
            val arr = JSONArray()
            val ids = ArrayList<String>()
            try {
                for (t in TripStore.list(ctx).take(MAX_TRIPS)) {
                    ids.add(t.id)
                    arr.put(
                        JSONObject()
                            .put("id", t.id)
                            .put("start", t.start)
                            .put("end", t.end)
                            .put("dist", t.distanceMeters)
                            .put("mov", t.movingTimeMs)
                            .put("max", t.maxKmh)
                            .put("avg", t.avgKmh)
                    )
                }
            } catch (e: Exception) {
                LogBus.log("[WEAR] trip list failed: ${e.message}")
            }
            try {
                Wearable.getMessageClient(ctx).sendMessage(node, PATH_TRIPS, arr.toString().toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {
            }
            if (px in 200..1000) {
                for (id in ids.take(PREFETCH)) enqueue(Job(ctx, id, OVERVIEW_KEY, 0.0, 0.5, 0.5, px, density), urgent = false)
            }
        }, "wear-trips").start()
    }

    /**
     * Request JSON: {"id", "key", "z": 0..4, "u","v": view centre in the whole-ride frame (0..1),
     * "px": screen px, "density"}. z = 0 is the whole ride at screen size; zoomed requests get an
     * image [PAN_FACTOR]× the screen (capped) so the watch can pan it with a finger.
     */
    fun sendMap(ctx: Context, requestJson: String) {
        val req = try { JSONObject(requestJson) } catch (_: Exception) { return }
        enqueue(
            Job(
                ctx = ctx,
                id = req.optString("id"),
                key = req.optString("key"),
                z = req.optDouble("z", 0.0).coerceIn(0.0, MAX_ZOOM),
                u = req.optDouble("u", 0.5).coerceIn(-0.5, 1.5),
                v = req.optDouble("v", 0.5).coerceIn(-0.5, 1.5),
                px = req.optInt("px", 450).coerceIn(200, 1000),
                density = req.optDouble("density", 2.0).toFloat().coerceIn(1f, 4f),
            ),
            urgent = true,
        )
    }

    private fun prepare(job: Job) {
        val outPx = if (job.z < 0.05) job.px else (job.px * PAN_FACTOR).coerceAtMost(MAX_IMAGE_PX)
        Thread({
            val trip = try { TripStore.get(job.ctx, job.id) } catch (_: Exception) { null }
            if (trip == null || trip.points.size < 2) {
                LogBus.log("[WEAR] map for trip ${job.id}: no track")
                done()
                return@Thread
            }
            val pts = downsample(trip.points.map { LatLng(it.lat, it.lon) }, 600)
            val bounds = frame(pts, job.z, job.u, job.v, outPx.toDouble() / job.px)
            main.post { render(job.ctx.applicationContext, job.id, job.key, outPx, job.density, pts, bounds) }
        }, "wear-tripmap").start()
    }

    private fun render(
        ctx: Context, id: String, key: String, px: Int, density: Float,
        pts: List<LatLng>, bounds: LatLngBounds,
    ) {
        var done = false
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun finish(base: Bitmap?, project: ((LatLng) -> PointF)?, withMap: Boolean) {
            if (done) return
            done = true
            val renderMs = android.os.SystemClock.elapsedRealtime() - t0
            Thread({
                val bmp = if (base != null && project != null) {
                    base.copy(Bitmap.Config.ARGB_8888, true).also { drawTrack(it, pts, project, density) }
                } else {
                    plainTrack(px, pts, bounds, density)
                }
                publish(ctx, id, key, bmp, withMap, renderMs)
                done()
            }, "wear-tripmap-draw").start()
        }

        val logical = (px / density).toInt()
        val snapshotter = try {
            val opts = MapSnapshotter.Options(logical, logical)
                .withStyleBuilder(Style.Builder().fromUri(MapLibreDashController.STYLE_DAY))
                .withRegion(bounds)
                .withPixelRatio(density)
                .withLogo(false)
            MapSnapshotter(ctx, opts)
        } catch (e: Exception) {
            LogBus.log("[WEAR] map snapshot unavailable (${e.message}) — track only")
            finish(null, null, false)
            return
        }
        main.postDelayed({
            if (!done) {
                LogBus.log("[WEAR] map snapshot timed out — track only")
                try { snapshotter.cancel() } catch (_: Exception) {}
                finish(null, null, false)
            }
        }, SNAPSHOT_TIMEOUT_MS)
        try {
            snapshotter.start(
                object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        // Project on the main thread while the snapshot is valid, then draw off it.
                        val proj = pts.map { snapshot.pixelForLatLng(it) }
                        val index = HashMap<LatLng, PointF>(proj.size)
                        pts.forEachIndexed { i, p -> index[p] = proj[i] }
                        finish(snapshot.bitmap, { p -> index[p] ?: PointF(0f, 0f) }, true)
                    }
                },
                object : MapSnapshotter.ErrorHandler {
                    override fun onError(error: String) {
                        LogBus.log("[WEAR] map snapshot failed ($error) — track only")
                        finish(null, null, false)
                    }
                },
            )
        } catch (e: Exception) {
            LogBus.log("[WEAR] map snapshot failed (${e.message}) — track only")
            finish(null, null, false)
        }
    }

    private fun publish(ctx: Context, id: String, key: String, bmp: Bitmap, withMap: Boolean, renderMs: Long) {
        try {
            val out = ByteArrayOutputStream()
            // WebP is ~30-40% smaller than JPEG at the same look: fewer seconds over Bluetooth.
            if (android.os.Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, out)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            // One data item per trip (replaced on each request); "key" says which view it is.
            val req = PutDataMapRequest.create("$PATH_TRIPMAP/$id")
            req.dataMap.putString("key", key)
            req.dataMap.putAsset("map", Asset.createFromBytes(out.toByteArray()))
            req.dataMap.putBoolean("withMap", withMap)
            req.dataMap.putLong("t", System.currentTimeMillis())
            Wearable.getDataClient(ctx).putDataItem(req.asPutDataRequest().setUrgent())
            LogBus.log("[WEAR] trip map $id [$key] ${bmp.width}px sent (${out.size() / 1024} KB, rendered in ${renderMs} ms, ${if (withMap) "map" else "track only"})")
        } catch (e: Exception) {
            LogBus.log("[WEAR] trip map send failed: ${e.message}")
        }
    }

    /**
     * The whole-ride frame (track bounds, padded so the ride fits inside the ROUND screen) is the
     * (0..1, 0..1) space the watch's u/v live in. The view is that frame zoomed in by [z] around
     * (u, v); the image covers [cover]× the view so it can be panned.
     */
    private fun frame(pts: List<LatLng>, z: Double, u: Double, v: Double, cover: Double): LatLngBounds {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in pts) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        val lonScale = cos(Math.toRadians(cLat)).coerceAtLeast(0.2)
        // Square span in "latitude degrees", at least ~400 m, with room for the round mask.
        val span0 = max(maxLat - minLat, (maxLon - minLon) * lonScale).coerceAtLeast(0.0036) * 1.55
        val north0 = cLat + span0 / 2
        val west0 = cLon - span0 / 2 / lonScale
        val centerLat = north0 - v * span0
        val centerLon = west0 + u * span0 / lonScale
        val span = span0 / 1.8.pow(z) * cover
        val halfLat = span / 2
        val halfLon = span / 2 / lonScale
        return LatLngBounds.from(centerLat + halfLat, centerLon + halfLon, centerLat - halfLat, centerLon - halfLon)
    }

    private fun downsample(pts: List<LatLng>, maxPts: Int): List<LatLng> {
        if (pts.size <= maxPts) return pts
        val step = pts.size.toDouble() / maxPts
        val out = ArrayList<LatLng>(maxPts + 1)
        var i = 0.0
        while (i < pts.size) { out.add(pts[i.toInt()]); i += step }
        if (out.last() != pts.last()) out.add(pts.last())
        return out
    }

    private fun drawTrack(bmp: Bitmap, pts: List<LatLng>, project: (LatLng) -> PointF, density: Float) {
        val c = Canvas(bmp)
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8541C")
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        pts.forEachIndexed { i, p ->
            val q = project(p)
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        c.drawPath(path, casing)
        c.drawPath(path, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val s = project(pts.first())
        val e = project(pts.last())
        c.drawCircle(s.x, s.y, 7f * density, ring)
        dot.color = Color.parseColor("#16A34A"); c.drawCircle(s.x, s.y, 5f * density, dot)
        c.drawCircle(e.x, e.y, 7f * density, ring)
        dot.color = Color.parseColor("#E11D48"); c.drawCircle(e.x, e.y, 5f * density, dot)
    }

    /** No map: the track on black with a simple local projection over the same bounds. */
    private fun plainTrack(px: Int, pts: List<LatLng>, b: LatLngBounds, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.parseColor("#EEF0F2"))
        val north = b.latitudeNorth; val south = b.latitudeSouth
        val west = b.longitudeWest; val east = b.longitudeEast
        drawTrack(bmp, pts, { p ->
            PointF(
                ((p.longitude - west) / (east - west) * px).toFloat(),
                ((north - p.latitude) / (north - south) * px).toFloat(),
            )
        }, density)
        return bmp
    }
}
