// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * One saved ride's route (coloured by speed), or "everything I've ridden": every trip as routes or
 * as a heat map, for this month / this year / ever. OpenStreetMap tiles via osmdroid (no API key).
 */
class TripMapActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val density get() = resources.displayMetrics.density

    // "All" mode state.
    private var allTrips: List<Trip> = emptyList()
    private var heatMode = false
    private var period = Period.ALL
    private var framed = false

    enum class Period { MONTH, YEAR, ALL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid needs a non-default user agent or OSM tile servers reject the requests.
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setContentView(R.layout.activity_trip_map)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trip_map_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)

        if (intent.getBooleanExtra(EXTRA_ALL, false)) {
            showAll()
            return
        }
        val id = intent.getStringExtra(EXTRA_ID)
        val trip = id?.let { TripStore.get(this, it) }
        if (trip == null) {
            Toast.makeText(this, "Trip not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.map_title).text = TripNames.title(this, trip)
        findViewById<TextView>(R.id.map_stats).text =
            "${trip.distanceText()} · ${trip.durationText()} · avg ${trip.avgKmh} · max ${trip.maxKmh} km/h\n" +
            getString(R.string.map_speed_legend)

        renderRoute(trip)
        findViewById<View>(R.id.map_share).setOnClickListener { RideShare.shareTrip(this, trip) }
    }

    // ---------------------------------------------------------------- single trip

    /** The route coloured by speed (blue = slow → red = fast, relative to this trip's top speed). */
    private fun renderRoute(trip: Trip) {
        val pts = trip.points
        if (pts.isEmpty()) {
            map.controller.setZoom(4.0)
            return
        }
        val geo = pts.map { GeoPoint(it.lat, it.lon) }
        map.overlays.add(casing(geo))
        val top = max(trip.maxSpeedMs, 1f)
        // Group consecutive points into runs of the same colour bucket (few overlays, smooth look).
        var run = ArrayList<GeoPoint>()
        var bucket = -1
        fun flush() {
            if (run.size >= 2) map.overlays.add(line(run, HeatGrid.color(bucket / 9f), LINE_DP))
        }
        for (i in pts.indices) {
            val b = ((pts[i].speedMs / top).coerceIn(0f, 1f) * 9).toInt()
            if (b != bucket && run.isNotEmpty()) {
                run.add(geo[i])
                flush()
                run = arrayListOf(geo[i])
            } else {
                run.add(geo[i])
            }
            bucket = b
        }
        flush()

        addMarker(geo.first(), "Start", Color.GREEN)
        if (geo.size > 1) addMarker(geo.last(), "End", Color.RED)
        frame(geo, 1.4f)
    }

    // ---------------------------------------------------------------- everything I've ridden

    private fun showAll() {
        findViewById<TextView>(R.id.map_title).setText(R.string.all_rides_title)
        findViewById<TextView>(R.id.map_stats).setText(R.string.trips_loading_all)
        findViewById<View>(R.id.map_controls).visibility = View.VISIBLE
        dimBaseMap()
        mapOf<Int, () -> Unit>(
            R.id.map_mode_routes to { heatMode = false },
            R.id.map_mode_heat to { heatMode = true },
            R.id.map_period_month to { period = Period.MONTH; framed = false },
            R.id.map_period_year to { period = Period.YEAR; framed = false },
            R.id.map_period_all to { period = Period.ALL; framed = false },
        ).forEach { (id, action) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                action()
                renderAll()
            }
        }
        findViewById<View>(R.id.map_share).setOnClickListener {
            RideShare.shareAll(this, filtered(), heatMode)
        }
        Thread({
            val trips = try { TripStore.list(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allTrips = trips
                renderAll()
            }
        }, "all-rides").start()
    }

    private fun filtered(): List<Trip> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val since = when (period) {
            Period.ALL -> 0L
            Period.YEAR -> cal.apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            Period.MONTH -> cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        }
        return allTrips.filter { it.start >= since && it.points.size >= 2 }
    }

    private fun renderAll() {
        highlight(R.id.map_mode_routes, !heatMode)
        highlight(R.id.map_mode_heat, heatMode)
        highlight(R.id.map_period_month, period == Period.MONTH)
        highlight(R.id.map_period_year, period == Period.YEAR)
        highlight(R.id.map_period_all, period == Period.ALL)

        val trips = filtered()
        val km = trips.sumOf { it.distanceKm }
        findViewById<TextView>(R.id.map_stats).text =
            getString(R.string.all_rides_stats, trips.size, String.format(Locale.getDefault(), "%.0f", km))

        map.overlays.clear()
        val all = ArrayList<GeoPoint>()
        if (heatMode) {
            val grid = HeatGrid.build(trips)
            grid.cells.forEach { all.add(GeoPoint(it.lat, it.lon)) }
            map.overlays.add(HeatOverlay(grid))
        } else {
            val gold = ContextCompat.getColor(this, R.color.brand_orange)
            val lines = trips.map { t ->
                val step = maxOf(1, t.points.size / 500)
                t.points.filterIndexed { i, _ -> i % step == 0 || i == t.points.lastIndex }
                    .map { GeoPoint(it.lat, it.lon) }
            }
            lines.forEach { all.addAll(it) }
            lines.forEach { map.overlays.add(casing(it)) } // all casings first so lines sit on top
            lines.forEach { map.overlays.add(line(it, gold, LINE_DP)) }
        }
        map.invalidate()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.all_rides_none, Toast.LENGTH_SHORT).show()
        } else if (!framed) {
            framed = true
            frame(all, 1.2f)
        }
    }

    private fun highlight(id: Int, on: Boolean) {
        findViewById<MaterialButton>(id).alpha = if (on) 1f else 0.5f
    }

    /** Desaturate and darken the OSM tiles a bit so our routes / heat are what stands out. */
    private fun dimBaseMap() {
        val m = ColorMatrix().apply { setSaturation(0.25f) }
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.82f, 0f, 0f, 0f, 0f,
                    0f, 0.82f, 0f, 0f, 0f,
                    0f, 0f, 0.82f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(m))
    }

    /** Heat cells: a soft halo plus a solid core, blue → red, hottest on top. */
    private inner class HeatOverlay(private val grid: HeatGrid) : Overlay() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val p = Point()
        private val q = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || grid.cells.isEmpty()) return
            val proj = mapView.projection
            val dLat = grid.sizeDeg.first
            val first = grid.cells.first()
            proj.toPixels(GeoPoint(first.lat, first.lon), p)
            proj.toPixels(GeoPoint(first.lat + dLat, first.lon), q)
            val cellPx = abs(p.y - q.y).toFloat()
            val r = max(cellPx * 0.75f, 4f * density)
            val w = mapView.width
            val h = mapView.height
            for (cell in grid.cells) {
                proj.toPixels(GeoPoint(cell.lat, cell.lon), p)
                if (p.x < -r * 2 || p.y < -r * 2 || p.x > w + r * 2 || p.y > h + r * 2) continue
                paint.color = HeatGrid.color(cell.heat, 70)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 1.9f, paint)
                paint.color = HeatGrid.color(cell.heat, 210)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun casing(points: List<GeoPoint>) = line(points, Color.parseColor("#14201A"), LINE_DP + 3.5f)

    private fun line(points: List<GeoPoint>, color: Int, widthDp: Float) = Polyline(map).apply {
        setPoints(points)
        outlinePaint.color = color
        outlinePaint.strokeWidth = widthDp * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        infoWindow = null
    }

    private fun frame(points: List<GeoPoint>, scale: Float) {
        val bbox = BoundingBox.fromGeoPoints(points)
        map.post {
            try {
                map.zoomToBoundingBox(bbox.increaseByScale(scale), false, (24 * density).toInt())
            } catch (_: Exception) {
                map.controller.setZoom(15.0)
                map.controller.setCenter(points.first())
            }
        }
    }

    private fun addMarker(point: GeoPoint, label: String, tint: Int) {
        val marker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = label
            icon?.setTint(tint)
        }
        map.overlays.add(marker)
    }

    override fun onResume() {
        super.onResume()
        if (this::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (this::map.isInitialized) map.onPause()
    }

    companion object {
        private const val EXTRA_ID = "trip_id"
        private const val EXTRA_ALL = "all_trips"
        private const val LINE_DP = 4f

        fun start(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra(EXTRA_ID, id))
        }

        fun startAll(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra(EXTRA_ALL, true))
        }
    }
}
