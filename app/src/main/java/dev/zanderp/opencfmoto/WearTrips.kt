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
    private const val MAX_ZOOM_STEP = 4

    private val main = Handler(Looper.getMainLooper())

    /** Reply to the watch with the last [MAX_TRIPS] rides (summary only). */
    fun sendList(ctx: Context, node: String) {
        Thread({
            val arr = JSONArray()
            try {
                for (t in TripStore.list(ctx).take(MAX_TRIPS)) {
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
        }, "wear-trips").start()
    }

    /** Request JSON: {"id": "...", "z": 0..4, "px": 450, "density": 2.0}. */
    fun sendMap(ctx: Context, requestJson: String) {
        val req = try { JSONObject(requestJson) } catch (_: Exception) { return }
        val id = req.optString("id")
        val z = req.optInt("z", 0).coerceIn(0, MAX_ZOOM_STEP)
        val px = req.optInt("px", 450).coerceIn(200, 1000)
        val density = req.optDouble("density", 2.0).toFloat().coerceIn(1f, 4f)
        Thread({
            val trip = try { TripStore.get(ctx, id) } catch (_: Exception) { null }
            if (trip == null || trip.points.size < 2) {
                LogBus.log("[WEAR] map for trip $id: no track")
                return@Thread
            }
            val pts = downsample(trip.points.map { LatLng(it.lat, it.lon) }, 600)
            val bounds = frame(pts, z)
            main.post { render(ctx.applicationContext, id, z, px, density, pts, bounds) }
        }, "wear-tripmap").start()
    }

    private fun render(
        ctx: Context, id: String, z: Int, px: Int, density: Float,
        pts: List<LatLng>, bounds: LatLngBounds,
    ) {
        var done = false
        fun finish(base: Bitmap?, project: ((LatLng) -> PointF)?, withMap: Boolean) {
            if (done) return
            done = true
            Thread({
                val bmp = if (base != null && project != null) {
                    base.copy(Bitmap.Config.ARGB_8888, true).also { drawTrack(it, pts, project, density) }
                } else {
                    plainTrack(px, pts, bounds, density)
                }
                publish(ctx, id, z, bmp, withMap)
            }, "wear-tripmap-draw").start()
        }

        val logical = (px / density).toInt()
        val snapshotter = try {
            val opts = MapSnapshotter.Options(logical, logical)
                .withStyleBuilder(Style.Builder().fromUri(MapLibreDashController.STYLE_NIGHT))
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

    private fun publish(ctx: Context, id: String, z: Int, bmp: Bitmap, withMap: Boolean) {
        try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val req = PutDataMapRequest.create("$PATH_TRIPMAP/$id/$z")
            req.dataMap.putAsset("map", Asset.createFromBytes(out.toByteArray()))
            req.dataMap.putBoolean("withMap", withMap)
            req.dataMap.putLong("t", System.currentTimeMillis())
            Wearable.getDataClient(ctx).putDataItem(req.asPutDataRequest().setUrgent())
            LogBus.log("[WEAR] trip map $id z=$z sent (${out.size() / 1024} KB, ${if (withMap) "map" else "track only"})")
        } catch (e: Exception) {
            LogBus.log("[WEAR] trip map send failed: ${e.message}")
        }
    }

    /** Track bounds, padded so the whole ride fits inside the ROUND screen, then zoomed in by [z]. */
    private fun frame(pts: List<LatLng>, z: Int): LatLngBounds {
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
        var span = max(maxLat - minLat, (maxLon - minLon) * lonScale).coerceAtLeast(0.0036) * 1.55
        span /= 1.8.pow(z)
        val halfLat = span / 2
        val halfLon = span / 2 / lonScale
        return LatLngBounds.from(cLat + halfLat, cLon + halfLon, cLat - halfLat, cLon - halfLon)
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
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF6B2C")
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
        c.drawPath(path, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val s = project(pts.first())
        val e = project(pts.last())
        c.drawCircle(s.x, s.y, 7f * density, ring)
        dot.color = Color.parseColor("#22C55E"); c.drawCircle(s.x, s.y, 5f * density, dot)
        c.drawCircle(e.x, e.y, 7f * density, ring)
        dot.color = Color.parseColor("#F43F5E"); c.drawCircle(e.x, e.y, 5f * density, dot)
    }

    /** No map: the track on black with a simple local projection over the same bounds. */
    private fun plainTrack(px: Int, pts: List<LatLng>, b: LatLngBounds, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.parseColor("#0B0D10"))
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
