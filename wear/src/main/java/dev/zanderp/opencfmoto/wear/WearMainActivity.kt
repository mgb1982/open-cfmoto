// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.wear

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.wear.ambient.AmbientLifecycleObserver
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/**
 * Page 0 (default): current ride — speed, distance, moving time, max and average.
 * Page 1: Android Auto D-pad. The bezel/crown drives the Android Auto knob on either page.
 */
class WearMainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pager: ViewPager2
    private lateinit var root: View
    private lateinit var dots: TextView

    private var tvStatus: TextView? = null
    private var tvSpeed: TextView? = null
    private var tvSpeedUnit: TextView? = null
    private var tvDist: TextView? = null
    private var tvTime: TextView? = null
    private var tvMax: TextView? = null
    private var tvAvg: TextView? = null
    private var tvHint: TextView? = null

    private var stats: RideStats? = null
    private var statsAt = 0L
    private var ambient = false
    private var rotaryAcc = 0f

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient = true
                if (::pager.isInitialized) pager.setCurrentItem(0, false)
                render()
            }

            override fun onExitAmbient() {
                ambient = false
                render()
            }

            override fun onUpdateAmbient() = render()
        },
    )

    /** Keep the phone streaming while we are on screen (lease is renewed every few seconds). */
    private val subscribeTick = object : Runnable {
        override fun run() {
            PhoneLink.send(this@WearMainActivity, PhoneLink.PATH_SUB, "1")
            render()   // also ages the "no data" state
            handler.postDelayed(this, SUBSCRIBE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        dots = findViewById(R.id.tv_dots)
        pager = findViewById(R.id.pager)
        pager.offscreenPageLimit = 1
        pager.adapter = PagesAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots.text = if (position == 0) "●  ○" else "○  ●"
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        handler.removeCallbacks(subscribeTick)
        handler.post(subscribeTick)
        root.requestFocus()
    }

    override fun onPause() {
        handler.removeCallbacks(subscribeTick)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PhoneLink.PATH_STATS) return
        PhoneLink.rememberPhone(event.sourceNodeId)
        val s = RideStats.parse(String(event.data, Charsets.UTF_8)) ?: return
        runOnUiThread {
            stats = s
            statsAt = SystemClock.elapsedRealtime()
            render()
        }
    }

    // ---- Bezel / crown → Android Auto knob ----

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            // Clockwise reports a negative AXIS_SCROLL; clockwise = knob forward (+1).
            rotaryAcc += -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val steps = rotaryAcc.toInt()
            if (steps != 0) {
                rotaryAcc -= steps
                PhoneLink.send(this, PhoneLink.PATH_SCROLL, steps.toString())
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- Rendering ----

    private fun render() {
        val s = stats
        val fresh = s != null && SystemClock.elapsedRealtime() - statsAt < STALE_MS
        val status = tvStatus ?: return

        root.setBackgroundColor(Color.BLACK)
        dots.visibility = if (ambient) View.INVISIBLE else View.VISIBLE

        when {
            !fresh -> {
                status.setText(R.string.status_no_phone)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
            }
            s!!.phase == "STREAMING" -> {
                status.setText(R.string.status_streaming)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREEN)
            }
            s.session -> {
                status.setText(R.string.status_reconnecting)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_AMBER)
            }
            else -> {
                status.setText(R.string.status_idle)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
            }
        }

        val accent = if (ambient) Color.WHITE else COLOR_ACCENT
        tvSpeed?.setTextColor(Color.WHITE)
        tvSpeedUnit?.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
        listOf(tvDist, tvTime, tvMax, tvAvg).forEach { it?.setTextColor(accent) }

        if (!fresh || s == null) {
            tvSpeed?.text = "--"
            tvDist?.text = "--"
            tvTime?.text = "--"
            tvMax?.text = "--"
            tvAvg?.text = "--"
            tvHint?.visibility = View.GONE
            return
        }
        tvSpeed?.text = if (s.fix) s.speedKmh.toString() else "--"
        val km = s.distanceM / 1000.0
        tvDist?.text = String.format(Locale.getDefault(), if (km < 100) "%.1f" else "%.0f", km)
        tvTime?.text = formatDuration(s.movingMs)
        tvMax?.text = String.format(Locale.getDefault(), "%.0f", s.maxKmh)
        tvAvg?.text = String.format(Locale.getDefault(), "%.0f", s.avgKmh)
        tvHint?.visibility = if (s.session && !s.recording && !ambient) View.VISIBLE else View.GONE
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    // ---- Pages ----

    private fun bindTrip(v: View) {
        tvStatus = v.findViewById(R.id.tv_status)
        tvSpeed = v.findViewById(R.id.tv_speed)
        tvSpeedUnit = v.findViewById(R.id.tv_speed_unit)
        tvDist = v.findViewById(R.id.tv_dist)
        tvTime = v.findViewById(R.id.tv_time)
        tvMax = v.findViewById(R.id.tv_max)
        tvAvg = v.findViewById(R.id.tv_avg)
        tvHint = v.findViewById(R.id.tv_hint)
        render()
    }

    private fun bindPad(v: View) {
        val keys = mapOf(
            R.id.btn_up to PhoneLink.KEY_UP,
            R.id.btn_down to PhoneLink.KEY_DOWN,
            R.id.btn_left to PhoneLink.KEY_LEFT,
            R.id.btn_right to PhoneLink.KEY_RIGHT,
            R.id.btn_ok to PhoneLink.KEY_ENTER,
            R.id.btn_back to PhoneLink.KEY_BACK,
            R.id.btn_home to PhoneLink.KEY_HOME,
            R.id.btn_assistant to PhoneLink.KEY_ASSISTANT,
        )
        for ((id, code) in keys) {
            v.findViewById<View>(id).setOnClickListener { b ->
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                PhoneLink.send(this, PhoneLink.PATH_KEY, code.toString())
            }
        }
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val layouts = intArrayOf(R.layout.page_trip, R.layout.page_pad)
        override fun getItemCount() = layouts.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) bindTrip(holder.itemView) else bindPad(holder.itemView)
        }
    }

    companion object {
        private const val SUBSCRIBE_EVERY_MS = 4_000L
        private const val STALE_MS = 6_000L
        private val COLOR_GREEN = Color.parseColor("#66BB6A")
        private val COLOR_AMBER = Color.parseColor("#FFB300")
        private val COLOR_GREY = Color.parseColor("#9E9E9E")
        private val COLOR_ACCENT = Color.parseColor("#4FC3F7")
    }
}
