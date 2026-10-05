// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.wear

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * One ride's map. The phone renders the image (map + track) and sends it as a Data Layer asset;
 * images are cached per trip and zoom step. The bezel zooms (0 = whole ride … 4).
 */
class TripMapActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var loading: TextView
    private lateinit var stats: TextView
    private lateinit var tripId: String
    private var zoom = 0
    private var rotaryAcc = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_map)
        image = findViewById(R.id.iv_map)
        loading = findViewById(R.id.tv_map_loading)
        stats = findViewById(R.id.tv_map_stats)
        tripId = intent.getStringExtra(EXTRA_ID).orEmpty()
        stats.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        // Tap the map to hide/show the numbers.
        image.setOnClickListener {
            stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.root_map).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        show(zoom)
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun cacheFile(z: Int) = File(cacheDir, "tripmap_${tripId}_$z.jpg")

    private fun show(z: Int) {
        val f = cacheFile(z)
        if (f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { setMap(it); return }
        }
        loading.visibility = View.VISIBLE
        loading.setText(R.string.map_loading)
        val req = JSONObject()
            .put("id", tripId)
            .put("z", z)
            .put("px", resources.displayMetrics.widthPixels)
            .put("density", resources.displayMetrics.density.toDouble())
        PhoneLink.send(this, PhoneLink.PATH_TRIPMAP, req.toString())
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (loading.visibility == View.VISIBLE) loading.setText(R.string.map_no_phone)
        }, 25_000L)
    }

    private fun setMap(bmp: Bitmap) {
        image.setImageBitmap(bmp)
        loading.visibility = View.GONE
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (ev in events) {
            if (ev.type != DataEvent.TYPE_CHANGED) continue
            val path = ev.dataItem.uri.path ?: continue
            if (!path.startsWith("${PhoneLink.PATH_TRIPMAP}/$tripId/")) continue
            val z = path.substringAfterLast('/').toIntOrNull() ?: continue
            val dm = DataMapItem.fromDataItem(ev.dataItem.freeze()).dataMap
            val asset = dm.getAsset("map") ?: continue
            val withMap = dm.getBoolean("withMap")
            Wearable.getDataClient(this).getFdForAsset(asset).addOnSuccessListener { resp ->
                Thread({
                    val bytes = try { resp.inputStream.use { it.readBytes() } } catch (_: Exception) { null }
                    if (bytes != null) {
                        // Track-only fallbacks (phone offline) aren't cached, so the map comes later.
                        if (withMap) try { cacheFile(z).writeBytes(bytes) } catch (_: Exception) {}
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) runOnUiThread { if (z == zoom) setMap(bmp) }
                    }
                }, "tripmap-read").start()
            }
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            rotaryAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val steps = rotaryAcc.toInt()
            if (steps != 0) {
                rotaryAcc -= steps
                val nz = (zoom + steps).coerceIn(0, MAX_ZOOM)
                if (nz != zoom) {
                    zoom = nz
                    show(zoom)
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
