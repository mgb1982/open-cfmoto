// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Shows one saved ride's route on an OpenStreetMap (osmdroid — no API key). Draws the GPS track as a
 * polyline with start/end markers and frames it. Tiles are fetched over the internet and cached by
 * osmdroid; a route with no points just centres on a default location.
 */
class TripMapActivity : AppCompatActivity() {

    private lateinit var map: MapView

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

        findViewById<TextView>(R.id.map_title).text = trip.dateText()
        findViewById<TextView>(R.id.map_stats).text =
            "${trip.distanceText()} · ${trip.durationText()} · avg ${trip.avgKmh} · max ${trip.maxKmh} km/h"

        renderRoute(trip)
        findViewById<android.view.View>(R.id.map_share).setOnClickListener { RideShare.shareTrip(this, trip) }
    }

    /** "Everything I've ridden": every saved trip on one map. */
    private fun showAll() {
        findViewById<TextView>(R.id.map_title).setText(R.string.all_rides_title)
        findViewById<TextView>(R.id.map_stats).setText(R.string.trips_loading_all)
        Thread({
            val trips = try { TripStore.list(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val km = trips.sumOf { it.distanceKm }
                findViewById<TextView>(R.id.map_stats).text =
                    getString(R.string.all_rides_stats, trips.size, String.format(java.util.Locale.getDefault(), "%.0f", km))
                val gold = ContextCompat.getColor(this, R.color.brand_orange)
                val all = ArrayList<GeoPoint>()
                for (t in trips) {
                    if (t.points.size < 2) continue
                    val step = maxOf(1, t.points.size / 500)
                    val geo = t.points.filterIndexed { i, _ -> i % step == 0 }.map { GeoPoint(it.lat, it.lon) }
                    all.addAll(geo)
                    map.overlays.add(Polyline(map).apply {
                        setPoints(geo)
                        outlinePaint.color = gold
                        outlinePaint.alpha = 170
                        outlinePaint.strokeWidth = 7f
                    })
                }
                map.invalidate()
                if (all.isNotEmpty()) {
                    val bbox = BoundingBox.fromGeoPoints(all)
                    map.post {
                        try { map.zoomToBoundingBox(bbox.increaseByScale(1.2f), false, 48) } catch (_: Exception) {}
                    }
                } else {
                    map.controller.setZoom(4.0)
                }
                findViewById<android.view.View>(R.id.map_share).setOnClickListener { RideShare.shareAll(this, trips) }
            }
        }, "all-rides").start()
    }

    private fun renderRoute(trip: Trip) {
        val geo = trip.points.map { GeoPoint(it.lat, it.lon) }
        if (geo.isEmpty()) {
            map.controller.setZoom(4.0)
            return
        }

        val line = Polyline(map).apply {
            setPoints(geo)
            outlinePaint.color = ContextCompat.getColor(this@TripMapActivity, R.color.brand_orange)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(line)

        addMarker(geo.first(), "Start", Color.GREEN)
        if (geo.size > 1) addMarker(geo.last(), "End", Color.RED)

        // Frame the whole route once the map has been laid out.
        val bbox = BoundingBox.fromGeoPoints(geo)
        map.post {
            try {
                map.zoomToBoundingBox(bbox.increaseByScale(1.4f), false, 48)
            } catch (_: Exception) {
                map.controller.setZoom(15.0)
                map.controller.setCenter(geo.first())
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

        fun startAll(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra(EXTRA_ALL, true))
        }

        fun start(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, TripMapActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
