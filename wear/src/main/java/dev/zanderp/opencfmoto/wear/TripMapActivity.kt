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
import kotlin.math.pow

/**
 * One ride's map. The phone renders each view (map + track) and sends it as a Data Layer asset.
 *
 * Coordinates: (u, v) is the centre of what the rider looks at, in the whole-ride frame (0..1 each
 * way; z = 0 shows exactly that frame). The bezel zooms around the current centre (0 … [MAX_ZOOM]).
 * Zoomed images are ~3 screens wide: a finger pans within them, and lifting it near an edge asks the
 * phone for a new image centred where the rider is looking. Swipe-to-dismiss is off (it would fight
 * panning): the back button closes, or a fling right while fully zoomed out.
 */
class TripMapActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var loading: TextView
    private lateinit var stats: TextView
    private lateinit var tripId: String

    private var zoom = 0
    private var u = 0.5
    private var v = 0.5
    private var rotaryAcc = 0f

    /** What the shown bitmap covers: centre and zoom it was requested with. */
    private var imgZ = 0
    private var imgU = 0.5
    private var imgV = 0.5
    private var bmpSize = 0
    private var tx = 0f
    private var ty = 0f

    private var pendingKey: String? = null
    private val pendingParams = HashMap<String, Triple<Int, Double, Double>>()

    private val screen: Int get() = resources.displayMetrics.widthPixels

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
                if (bmpSize <= screen) return false
                tx -= dx
                ty -= dy
                applyMatrix()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (zoom == 0 && bmpSize <= screen && vx > 1200 && abs(vx) > abs(vy)) {
                    finish()
                    return true
                }
                return false
            }
        })
        image.setOnTouchListener { _, ev ->
            val handled = gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) onPanEnd()
            handled
        }
        findViewById<View>(R.id.root_map).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        if (bmpSize == 0) request(zoom, u, v)
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---- Requests ----

    private fun keyFor(z: Int, cu: Double, cv: Double) =
        String.format(Locale.ROOT, "%d_%.3f_%.3f", z, cu, cv)

    /** Only the whole-ride view is kept on disk ("v2": light style). */
    private fun overviewFile() = File(cacheDir, "tripmap2_${tripId}_z0.jpg")

    private fun request(z: Int, cu: Double, cv: Double) {
        val key = keyFor(z, cu, cv)
        pendingKey = key
        pendingParams[key] = Triple(z, cu, cv)
        if (z == 0) {
            val f = overviewFile()
            if (f.exists()) {
                BitmapFactory.decodeFile(f.path)?.let { showBitmap(it, z, cu, cv); return }
            }
        }
        loading.visibility = View.VISIBLE
        loading.setText(R.string.map_loading)
        val req = JSONObject()
            .put("id", tripId)
            .put("key", key)
            .put("z", z)
            .put("u", cu)
            .put("v", cv)
            .put("px", screen)
            .put("density", resources.displayMetrics.density.toDouble())
        PhoneLink.send(this, PhoneLink.PATH_TRIPMAP, req.toString())
        handler.removeCallbacksAndMessages(null)
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
                        if (withMap && params.first == 0) try { overviewFile().writeBytes(bytes) } catch (_: Exception) {}
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) runOnUiThread {
                            if (key == pendingKey) showBitmap(bmp, params.first, params.second, params.third)
                        }
                    }
                }, "tripmap-read").start()
            }
        }
    }

    // ---- Display & panning ----

    private fun showBitmap(bmp: Bitmap, z: Int, cu: Double, cv: Double) {
        // Draw 1 bitmap px = 1 screen px (the matrix offsets are in screen px).
        bmp.density = resources.displayMetrics.densityDpi
        image.setImageBitmap(bmp)
        imgZ = z; imgU = cu; imgV = cv
        bmpSize = bmp.width
        tx = (screen - bmpSize) / 2f
        ty = (screen - bmp.height) / 2f
        applyMatrix()
        loading.visibility = View.GONE
    }

    private fun applyMatrix() {
        if (bmpSize > screen) {
            tx = tx.coerceIn((screen - bmpSize).toFloat(), 0f)
            ty = ty.coerceIn((screen - bmpSize).toFloat(), 0f)
        }
        image.imageMatrix = Matrix().apply { setTranslate(tx, ty) }
    }

    /** Where the screen centre is, in whole-ride (u, v), given the shown image and its offset. */
    private fun viewCentre(): Pair<Double, Double> {
        if (bmpSize == 0) return u to v
        val iu = (screen / 2f - tx) / bmpSize
        val iv = (screen / 2f - ty) / bmpSize
        // The image spans (bmp / screen) screens; one screen at zoom z is 1/1.8^z of the frame.
        val extent = (bmpSize.toDouble() / screen) / 1.8.pow(imgZ)
        return (imgU + (iu - 0.5) * extent) to (imgV + (iv - 0.5) * extent)
    }

    private fun onPanEnd() {
        if (bmpSize <= screen) return
        val (cu, cv) = viewCentre()
        u = cu; v = cv
        val iu = (screen / 2f - tx) / bmpSize
        val iv = (screen / 2f - ty) / bmpSize
        // Near an edge of what we have: fetch a fresh image centred here (current one stays up).
        if (abs(iu - 0.5f) > 0.22f || abs(iv - 0.5f) > 0.22f) request(zoom, u, v)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotaryAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val steps = rotaryAcc.toInt()
            if (steps != 0) {
                rotaryAcc -= steps
                val nz = (zoom + steps).coerceIn(0, MAX_ZOOM)
                if (nz != zoom) {
                    val (cu, cv) = viewCentre()
                    zoom = nz
                    if (zoom == 0) { u = 0.5; v = 0.5 } else { u = cu; v = cv }
                    request(zoom, u, v)
                }
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    companion object {
        private const val EXTRA_ID = "trip_id"
        private const val EXTRA_LABEL = "trip_label"
        private const val MAX_ZOOM = 4

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
