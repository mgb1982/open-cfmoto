// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.wear

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * One ride's map. The phone renders each view (map + track) and sends it as a Data Layer asset.
 *
 * The VIEW is (zoom, u, v): zoom 0 … [MAX_ZOOM] (continuous; each unit is ×[ZOOM_BASE]) and the
 * screen centre (u, v) in the whole-ride frame (0..1 each way; zoom 0 shows exactly that frame).
 * The shown bitmap knows which view it was rendered for, so any view is drawn at once by scaling and
 * shifting it (blurry or with blank edges until the sharp image arrives). Gestures: drag pans,
 * pinch or bezel zooms, tap hides/shows the numbers. When a gesture ends and the bitmap no longer
 * covers the view sharply, the phone is asked for a new one. Swipe-to-dismiss is off (it would fight
 * panning): the back button closes, or a fast fling right while fully zoomed out.
 */
class TripMapActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var loading: TextView
    private lateinit var stats: TextView
    private lateinit var tripId: String

    // The view.
    private var zoom = 0.0
    private var u = 0.5
    private var v = 0.5
    private var rotaryAcc = 0f

    // What the shown bitmap was rendered for.
    private var bmp: Bitmap? = null
    private var imgZ = 0.0
    private var imgU = 0.5
    private var imgV = 0.5

    private var pendingKey: String? = null
    private val pendingParams = HashMap<String, Triple<Double, Double, Double>>()
    private var scaling = false

    private val screen: Int get() = resources.displayMetrics.widthPixels

    /** Whole-ride frame units per screen pixel at a zoom. */
    private fun unitsPerPx(z: Double) = 1.0 / (ZOOM_BASE.pow(z) * screen)

    private val requestSoon = Runnable { requestIfNeeded() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_map)
        image = findViewById(R.id.iv_map)
        loading = findViewById(R.id.tv_map_loading)
        stats = findViewById(R.id.tv_map_stats)
        tripId = intent.getStringExtra(EXTRA_ID).orEmpty()
        stats.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        image.scaleType = ImageView.ScaleType.MATRIX

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaling || zoom < 0.05) return false
                val k = unitsPerPx(zoom)
                u = (u + dx * k).coerceIn(-0.25, 1.25)
                v = (v + dy * k).coerceIn(-0.25, 1.25)
                applyView()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zoom < 0.05 && vx > 1200 && abs(vx) > abs(vy)) {
                    finish()
                    return true
                }
                return false
            }
        })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAround(zoom + ln(d.scaleFactor.toDouble()) / ln(ZOOM_BASE), d.focusX, d.focusY)
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false }
        })
        image.setOnTouchListener { _, ev ->
            pinch.onTouchEvent(ev)
            val handled = gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(requestSoon)
                handler.postDelayed(requestSoon, 150)
            }
            handled || scaling
        }
        findViewById<View>(R.id.root_map).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        if (bmp == null) request()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---- View math ----

    /** Zoom to [nz], keeping the map point under (fx, fy) where it is. */
    private fun zoomAround(nz: Double, fx: Float, fy: Float) {
        val z2 = nz.coerceIn(0.0, MAX_ZOOM)
        val ox = fx - screen / 2.0
        val oy = fy - screen / 2.0
        val fu = u + ox * unitsPerPx(zoom)
        val fv = v + oy * unitsPerPx(zoom)
        zoom = z2
        if (zoom < 0.05) { zoom = 0.0; u = 0.5; v = 0.5 } else {
            u = (fu - ox * unitsPerPx(zoom)).coerceIn(-0.25, 1.25)
            v = (fv - oy * unitsPerPx(zoom)).coerceIn(-0.25, 1.25)
        }
        applyView()
    }

    /** Draw the current view from the bitmap we have (scaled/shifted as needed). */
    private fun applyView() {
        val b = bmp ?: return
        // Bitmap px → frame units, for the view it was rendered at.
        val imgUnitsPerPx = (b.width.toDouble() / screen) / ZOOM_BASE.pow(imgZ) / b.width
        val s = (imgUnitsPerPx / unitsPerPx(zoom)).toFloat()
        val ix = ((u - imgU) / imgUnitsPerPx + b.width / 2.0).toFloat()
        val iy = ((v - imgV) / imgUnitsPerPx + b.height / 2.0).toFloat()
        image.imageMatrix = Matrix().apply {
            setTranslate(-ix, -iy)
            postScale(s, s)
            postTranslate(screen / 2f, screen / 2f)
        }
        image.invalidate()
    }

    /** Is the bitmap sharp for this view and does it cover the whole screen? */
    private fun bitmapFits(): Boolean {
        val b = bmp ?: return false
        if (abs(zoom - imgZ) > 0.08) return false
        val imgUnitsPerPx = (b.width.toDouble() / screen) / ZOOM_BASE.pow(imgZ) / b.width
        val halfView = screen / 2.0 * unitsPerPx(zoom)
        val halfImg = b.width / 2.0 * imgUnitsPerPx
        return abs(u - imgU) + halfView <= halfImg + 1e-6 && abs(v - imgV) + halfView <= halfImg + 1e-6
    }

    // ---- Requests ----

    private fun keyFor(z: Double, cu: Double, cv: Double) =
        if (z < 0.05) WearKeys.OVERVIEW else String.format(Locale.ROOT, "%.2f_%.3f_%.3f", z, cu, cv)

    private fun requestIfNeeded() {
        if (!bitmapFits()) request()
    }

    private fun request() {
        val z = zoom
        val cu = u
        val cv = v
        val key = keyFor(z, cu, cv)
        if (key == pendingKey && loading.visibility == View.VISIBLE) return
        pendingKey = key
        pendingParams[key] = Triple(z, cu, cv)
        if (key == WearKeys.OVERVIEW) {
            val f = WearKeys.overviewFile(this, tripId)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.path)?.let { showBitmap(it, 0.0, 0.5, 0.5); return }
            }
        }
        loading.visibility = View.VISIBLE
        loading.setText(R.string.map_loading)
        // Keep the label readable over a blurry preview: only show it centred when there's no map.
        loading.alpha = if (bmp == null) 1f else 0.85f
        val req = JSONObject()
            .put("id", tripId)
            .put("key", key)
            .put("z", z)
            .put("u", cu)
            .put("v", cv)
            .put("px", screen)
            .put("density", resources.displayMetrics.density.toDouble())
        PhoneLink.send(this, PhoneLink.PATH_TRIPMAP, req.toString())
        handler.postDelayed({
            if (loading.visibility == View.VISIBLE && pendingKey == key) loading.setText(R.string.map_no_phone)
        }, 25_000L)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            if (ev.type != DataEvent.TYPE_CHANGED) continue
            if (ev.dataItem.uri.path != "${PhoneLink.PATH_TRIPMAP}/$tripId") continue
            val dm = DataMapItem.fromDataItem(ev.dataItem.freeze()).dataMap
            val key = dm.getString("key") ?: continue
            val params = pendingParams[key] ?: continue
            val asset = dm.getAsset("map") ?: continue
            val withMap = dm.getBoolean("withMap")
            Wearable.getDataClient(this).getFdForAsset(asset).addOnSuccessListener { resp ->
                Thread({
                    val bytes = try { resp.inputStream.use { it.readBytes() } } catch (_: Exception) { null }
                    if (bytes != null) {
                        // Track-only fallbacks (phone offline) aren't cached, so the map comes later.
                        if (withMap && key == WearKeys.OVERVIEW) {
                            try { WearKeys.overviewFile(this, tripId).writeBytes(bytes) } catch (_: Exception) {}
                        }
                        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (b != null) runOnUiThread {
                            if (key == pendingKey) showBitmap(b, params.first, params.second, params.third)
                        }
                    }
                }, "tripmap-read").start()
            }
        }
    }

    private fun showBitmap(b: Bitmap, z: Double, cu: Double, cv: Double) {
        // Draw 1 bitmap px = 1 screen px before our own matrix.
        b.density = resources.displayMetrics.densityDpi
        bmp = b
        imgZ = z; imgU = cu; imgV = cv
        image.setImageBitmap(b)
        applyView()
        loading.visibility = View.GONE
    }

    // ---- Bezel: half a zoom step per click, request once it settles ----

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotaryAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val steps = rotaryAcc.toInt()
            if (steps != 0) {
                rotaryAcc -= steps
                zoomAround(zoom + steps * 0.5, screen / 2f, screen / 2f)
                handler.removeCallbacks(requestSoon)
                handler.postDelayed(requestSoon, 350)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    companion object {
        private const val EXTRA_ID = "trip_id"
        private const val EXTRA_LABEL = "trip_label"
        private const val MAX_ZOOM = 4.0
        private const val ZOOM_BASE = 1.8

        fun intent(ctx: Context, t: TripSummary): Intent =
            Intent(ctx, TripMapActivity::class.java)
                .putExtra(EXTRA_ID, t.id)
                .putExtra(
                    EXTRA_LABEL,
                    "${t.timeRange()} · ${t.kmText()}\n" +
                        ctx.getString(R.string.trip_line, t.movingText(), t.avgKmh, t.maxKmh),
                )
    }
}

/** Shared by the map screen and the background listener that stores pre-rendered overviews. */
object WearKeys {
    const val OVERVIEW = "overview"
    fun overviewFile(ctx: Context, tripId: String) = File(ctx.cacheDir, "tripmap3_${tripId}_overview.img")
}
