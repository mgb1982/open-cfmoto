// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 🚦 Your red lights + same-trip route comparison (see [TrafficLights]). */
class TrafficLightsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var map: MapView
    private lateinit var info: TextView
    private lateinit var lights: LinearLayout
    private lateinit var routes: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private var result: TrafficLights.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(28)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.tl_title)
            textSize = 24f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        status = body(getString(R.string.tl_progress_trips))
        root.addView(status)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(13.0)
            controller.setCenter(GeoPoint(41.3874, 2.1686))
        }
        // The map scrolls itself; don't let the page steal its drags.
        map.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        val mapCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            addView(map, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        }
        root.addView(mapCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(10) })
        info = body(getString(R.string.tl_tap_hint))
        root.addView(info)

        root.addView(header(getString(R.string.tl_worst_title)))
        lights = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(lights)
        root.addView(header(getString(R.string.tl_routes_title)))
        routes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(routes)
        root.addView(body(getString(R.string.tl_footer)))

        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        load()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun load() {
        io.execute {
            val r = try {
                TrafficLights.analyse(this) { msg -> runOnUiThread { status.text = msg } }
            } catch (e: Exception) {
                LogBus.log("[TL] analysis failed: $e")
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result = r
                render()
            }
            // Street names for the top junctions (slow: 1 request/s), then refresh the list.
            val top = r?.junctions?.take(10).orEmpty()
            var fetched = false
            for (j in top) {
                if (Thread.currentThread().isInterrupted) return@execute
                if (TrafficLights.cachedName(this, j.junction) == null) { TrafficLights.fetchName(this, j.junction); fetched = true }
            }
            if (fetched) runOnUiThread { if (!isFinishing && !isDestroyed) renderLights() }
        }
    }

    private fun render() {
        val r = result
        if (r == null) { status.setText(R.string.tl_failed); return }
        if (r.tripsUsed == 0) { status.setText(R.string.tl_no_trips); return }
        status.text = getString(R.string.tl_summary, r.tripsUsed, r.junctions.size) +
            if (r.offline) "\n" + getString(R.string.tl_partial) else ""
        map.overlays.clear()
        map.overlays.add(JunctionOverlay(r.junctions))
        if (r.junctions.isNotEmpty()) {
            val lats = r.junctions.map { it.junction.lat }; val lons = r.junctions.map { it.junction.lon }
            map.post {
                try {
                    map.zoomToBoundingBox(BoundingBox(lats.max() + 0.003, lons.max() + 0.003, lats.min() - 0.003, lons.min() - 0.003), false, dp(16))
                } catch (_: Exception) {}
            }
        }
        map.invalidate()
        renderLights()
        renderRoutes(r)
    }

    private fun junctionLabel(j: TrafficLights.JunctionStats): String =
        TrafficLights.cachedName(this, j.junction) ?: String.format(java.util.Locale.ROOT, "%.4f, %.4f", j.junction.lat, j.junction.lon)

    private fun describe(j: TrafficLights.JunctionStats): String {
        val m = j.main
        val dir = resources.getStringArray(R.array.tl_dirs)[m.dir]
        var s = getString(R.string.tl_line, j.stops, j.passes, (j.stopRate * 100).roundToInt(), j.avgWaitS) +
            "\n" + getString(R.string.tl_main_dir, dir, m.stops, m.passes)
        m.cycleS?.let { s += "\n" + getString(R.string.tl_cycle, it, (m.cycleConfidence * 100).roundToInt()) }
        return s
    }

    private fun renderLights() {
        val r = result ?: return
        lights.removeAllViews()
        val top = r.junctions.filter { it.stops > 0 }.take(10)
        if (top.isEmpty()) { lights.addView(body(getString(R.string.tl_no_stops))); return }
        for (j in top) {
            lights.addView(card(junctionLabel(j), describe(j), rateColor(j.stopRate)) {
                map.controller.animateTo(GeoPoint(j.junction.lat, j.junction.lon), 17.0, 600L)
                info.text = "${junctionLabel(j)}\n${describe(j)}"
            })
        }
    }

    private fun renderRoutes(r: TrafficLights.Result) {
        routes.removeAllViews()
        val compare = r.routes.filter { it.variants.size >= 2 }
        if (compare.isEmpty()) { routes.addView(body(getString(R.string.tl_no_routes))); return }
        for (g in compare.take(8)) {
            val best = g.variants.minByOrNull { it.medianMin }!!
            val text = g.variants.mapIndexed { i, v ->
                (if (v === best) "🏆 " else "") + getString(
                    R.string.tl_variant, i + 1, v.count, v.medianMin, v.medianStops, String.format("%.1f", v.medianKm),
                )
            }.joinToString("\n")
            routes.addView(card(g.name, text + "\n" + getString(R.string.tl_variant_hint), color(R.color.brand_gold)) {
                // Show each variant's latest trip, one after another: start with the best.
                TripMapActivity.start(this, best.sample.id)
            })
        }
    }

    private fun rateColor(rate: Float): Int = when {
        rate >= 0.6f -> Color.rgb(229, 57, 53)
        rate >= 0.3f -> Color.rgb(251, 192, 45)
        else -> Color.rgb(67, 160, 71)
    }

    private inner class JunctionOverlay(private val items: List<TrafficLights.JunctionStats>) : Overlay() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
        private val pt = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val proj = mapView.projection
            for (j in items) {
                proj.toPixels(GeoPoint(j.junction.lat, j.junction.lon), pt)
                val rad = dp(5) + (dp(9) * (j.passes.coerceAtMost(20) / 20f))
                fill.color = rateColor(j.stopRate)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rad, fill)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rad, ring)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            val proj = mapView.projection
            var best: TrafficLights.JunctionStats? = null
            var bestD = dp(28).toFloat()
            for (j in items) {
                proj.toPixels(GeoPoint(j.junction.lat, j.junction.lon), pt)
                val d = kotlin.math.hypot(pt.x - e.x, pt.y - e.y)
                if (d < bestD) { bestD = d; best = j }
            }
            val j = best ?: return false
            info.text = "${junctionLabel(j)}\n${describe(j)}"
            return true
        }
    }

    private fun card(title: String, text: String, accent: Int, onClick: () -> Unit) = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        radius = dp(16).toFloat()
        setCardBackgroundColor(color(R.color.surface))
        setContentPadding(dp(14), dp(10), dp(14), dp(10))
        strokeColor = accent; strokeWidth = dp(1)
        setOnClickListener { onClick() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = title; textSize = 15f
                setTextColor(color(R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                this.text = text; textSize = 13f
                setTextColor(color(R.color.text_secondary))
            })
        })
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 16f
        setTextColor(color(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, TrafficLightsActivity::class.java))
    }
}
