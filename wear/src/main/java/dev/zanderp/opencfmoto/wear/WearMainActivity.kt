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
 * Page 1: Android Auto D-pad. Page 2: clock, total ride time, altitude, heading, GPS accuracy,
 * phone battery and media volume. Page 3: recent trips (tap one for its map). The bezel/crown
 * drives the Android Auto knob, except on the trips page where it scrolls the list.
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
    private var tvTotal: TextView? = null
    private var tvAlt: TextView? = null
    private var tvHeading: TextView? = null
    private var tvGps: TextView? = null
    private var tvBattery: TextView? = null
    private var tvVolume: TextView? = null
    private var tvParked: TextView? = null
    private var tvParkedMain: TextView? = null
    private var rowMaxAvg: View? = null
    private var padBox: View? = null
    private var parkPage: View? = null
    private var parkAgo: TextView? = null
    private var parkFind: View? = null

    private var stats: RideStats? = null
    private var statsAt = 0L
    private var ambient = false
    private var photoShown: Boolean? = null
    private var rotaryAcc = 0f

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient = true
                // Ambient redraws about once a minute: stop the 1 Hz stream (watch + phone battery)
                // and ask for a single snapshot on each ambient update instead.
                handler.removeCallbacks(subscribeTick)
                if (::pager.isInitialized) pager.setCurrentItem(0, false)
                render()
            }

            override fun onExitAmbient() {
                ambient = false
                handler.removeCallbacks(subscribeTick)
                handler.post(subscribeTick)
                render()
            }

            override fun onUpdateAmbient() {
                PhoneLink.send(this@WearMainActivity, PhoneLink.PATH_SUB, "once")
                render()
            }
        },
    )

    /** Keep the phone streaming while we are on screen (lease is renewed every few seconds). */
    private val subscribeTick = object : Runnable {
        override fun run() {
            // ";np" = no bike photo here yet: the phone (re)sends it.
            val np = if (PhoneLink.bikePhotoFile(this@WearMainActivity).exists()) "" else ";np"
            PhoneLink.send(this@WearMainActivity, PhoneLink.PATH_SUB, "1$np")
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
        pager.offscreenPageLimit = PAGES - 1
        pager.adapter = PagesAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots.text = (0 until PAGES).joinToString("  ") { if (it == position) "●" else "○" }
                if (position == PAGE_TRIPS) requestTrips()
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
        photoShown = null // the photo may have arrived while we were away
        Wearable.getMessageClient(this).addListener(this)
        handler.removeCallbacks(subscribeTick)
        if (!ambient) handler.post(subscribeTick)
        root.requestFocus()
    }

    override fun onPause() {
        handler.removeCallbacks(subscribeTick)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PhoneLink.PATH_TRIPS) {
            val list = TripSummary.parseList(String(event.data, Charsets.UTF_8))
            runOnUiThread { showTrips(list) }
            return
        }
        if (event.path != PhoneLink.PATH_STATS) return
        PhoneLink.rememberPhone(event.sourceNodeId)
        val raw = String(event.data, Charsets.UTF_8)
        // The phone puts the parking spot in every idle snapshot: always in sync, even if the
        // one-off /ocm/parked message was missed.
        try {
            val o = org.json.JSONObject(raw)
            if (o.has("park")) {
                val park = o.optString("park")
                if (park != (PhoneLink.parkedRaw(this))) {
                    PhoneLink.saveParked(this, park)
                    RideGlance.refresh(this)
                }
            }
        } catch (_: Exception) {}
        val s = RideStats.parse(raw) ?: return
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
            if (pager.currentItem == PAGE_TRIPS) {
                tripsList?.scrollBy(0, (-ev.getAxisValue(MotionEvent.AXIS_SCROLL) * 70 *
                    resources.displayMetrics.density).toInt())
                return true
            }
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

    private fun renderParked(riding: Boolean) {
        // Shown on the main page (where you look) and on the info page; the info one alone sat
        // below the grid, in the round screen's clipped bottom edge, and was never seen.
        val spot = PhoneLink.parked(this)
        val show = spot != null && !riding && !ambient
        // D-pad page: useless without the bike, so it turns into "find my bike" until a ride starts.
        val connected = riding
        padBox?.visibility = if (connected) View.VISIBLE else View.GONE
        parkPage?.visibility = if (connected) View.GONE else View.VISIBLE
        parkAgo?.text = if (spot == null) getString(R.string.find_none) else getString(
            R.string.find_parked_ago,
            android.text.format.DateUtils.getRelativeTimeSpanString(
                spot.third, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ),
        )
        parkFind?.visibility = if (spot == null) View.GONE else View.VISIBLE
        // The main page has no room for one more line on a round screen (it was centred and
        // clipped off the bottom): when idle, the parking pill takes the max/avg row's place.
        rowMaxAvg?.visibility = if (show) View.GONE else View.VISIBLE
        for (tv in listOfNotNull(tvParked, tvParkedMain)) {
            if (!show || spot == null) {
                tv.visibility = View.GONE
                continue
            }
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
                spot.third, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            )
            tv.text = getString(R.string.parked_line, ago)
            tv.visibility = View.VISIBLE
            tv.setOnClickListener { startActivity(android.content.Intent(this, FindBikeActivity::class.java)) }
        }
    }

    private fun render() {
        val s = stats
        val fresh = s != null && SystemClock.elapsedRealtime() - statsAt < (if (ambient) AMBIENT_STALE_MS else STALE_MS)
        renderInfo(if (fresh) s else null)
        val status = tvStatus ?: return

        // Not riding (and not ambient): the Garage bike photo behind, dimmed. Riding: plain black.
        val idlePhoto = !ambient && !(fresh && s != null && s.session)
        if (idlePhoto != photoShown) {
            photoShown = idlePhoto
            val d = if (idlePhoto) PhoneLink.bikePhoto(this, 0.62f) else null
            if (d != null) root.background = d else root.setBackgroundColor(Color.BLACK)
        }
        dots.visibility = if (ambient) View.INVISIBLE else View.VISIBLE

        when {
            !fresh -> {
                status.setText(R.string.status_no_phone)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
            }
            s!!.clockResync -> {
                status.setText(R.string.status_clock_resync)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_AMBER)
            }
            s.phase == "STREAMING" -> {
                status.setText(R.string.status_streaming)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREEN)
            }
            s.session -> {
                status.setText(R.string.status_reconnecting)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_AMBER)
            }
            s.husOff -> {
                status.setText(R.string.status_hus_off)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_AMBER)
            }
            else -> {
                status.setText(R.string.status_idle)
                status.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
            }
        }

        // Android Auto guiding: the status line becomes the next manoeuvre ("↱ 120 m").
        if (fresh && s != null && s.session) {
            PhoneLink.turnText(s.turn)?.let {
                status.text = it
                status.setTextColor(if (ambient) Color.WHITE else COLOR_ACCENT)
            }
        }

        val accent = if (ambient) Color.WHITE else COLOR_ACCENT
        tvSpeed?.setTextColor(Color.WHITE)
        tvSpeedUnit?.setTextColor(if (ambient) Color.WHITE else COLOR_GREY)
        listOf(tvDist, tvTime, tvMax, tvAvg).forEach { it?.setTextColor(if (ambient) Color.WHITE else Color.parseColor("#F1F4EC")) }

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
        tvDist?.text = String.format(Locale.getDefault(), if (km < 100) "%.1f km" else "%.0f km", km)
        tvTime?.text = formatDuration(s.movingMs)
        tvMax?.text = getString(R.string.pill_max, s.maxKmh.toInt())
        tvAvg?.text = getString(R.string.pill_avg, s.avgKmh.toInt())
        tvHint?.visibility = if (s.session && !s.recording && !ambient) View.VISIBLE else View.GONE
    }

    private fun renderInfo(s: RideStats?) {
        val loc = Locale.getDefault()
        renderParked(s?.session == true)
        if (s == null) {
            listOf(tvTotal, tvAlt, tvHeading, tvGps, tvBattery, tvVolume).forEach { it?.text = "--" }
        } else {
            tvTotal?.text = if (s.session) formatDuration(s.elapsedMs) else "--"
            val alt = s.altitudeM
            tvAlt?.text = if (alt == null) "--"
                else String.format(loc, "%d", Math.round(alt).toInt()) + if (s.altitudeRaw) "*" else ""
            val brg = s.bearing
            tvHeading?.text = if (brg == null) "--" else compass(brg)
            tvGps?.text = if (s.fix && s.accuracyM > 0) s.accuracyM.toString() else "--"
            val pct = s.batteryPct
            val temp = s.batteryTempC
            tvBattery?.text = if (pct == null) "--" else
                (if (s.charging) "⚡" else "") + "$pct%" +
                    (if (temp != null) String.format(loc, " · %.0f°", temp) else "")
            val vol = s.volume
            val volMax = s.volumeMax
            tvVolume?.text = if (vol == null || volMax == null) "--" else
                "$vol/$volMax" + if (s.boost > 0) " +${s.boost}" else ""
        }
        // Hot phone (AA + GPS + sun on the mount): flag it.
        val hot = (s?.batteryTempC ?: 0.0) >= 42.0
        tvBattery?.setTextColor(if (ambient) Color.WHITE else if (hot) COLOR_AMBER else COLOR_ACCENT)
        listOf(tvTotal, tvAlt, tvHeading, tvGps, tvVolume).forEach {
            it?.setTextColor(if (ambient) Color.WHITE else COLOR_ACCENT)
        }
    }

    private fun compass(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val i = (((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8
        return names[i]
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
        tvParkedMain = v.findViewById(R.id.tv_parked_main)
        rowMaxAvg = v.findViewById(R.id.row_max_avg)
        render()
    }

    private fun bindInfo(v: View) {
        tvTotal = v.findViewById(R.id.tv_total)
        tvAlt = v.findViewById(R.id.tv_alt)
        tvHeading = v.findViewById(R.id.tv_heading)
        tvGps = v.findViewById(R.id.tv_gps)
        tvBattery = v.findViewById(R.id.tv_battery)
        tvVolume = v.findViewById(R.id.tv_volume)
        tvParked = v.findViewById(R.id.tv_parked)
        render()
    }

    // ---- Trips page ----

    private var tripsList: RecyclerView? = null
    private var tripsEmpty: TextView? = null
    private val tripsAdapter = TripsAdapter { t ->
        startActivity(TripMapActivity.intent(this, t))
    }

    private fun bindTrips(v: View) {
        tripsList = v.findViewById<RecyclerView>(R.id.rv_trips).also {
            it.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            it.adapter = tripsAdapter
        }
        tripsEmpty = v.findViewById(R.id.tv_trips_empty)
    }

    private fun requestTrips() {
        tripsEmpty?.let { if (tripsAdapter.itemCount == 0) it.setText(R.string.trips_loading) }
        // Screen size lets the phone pre-render the newest trips' overview maps right away.
        PhoneLink.send(
            this, PhoneLink.PATH_TRIPS,
            org.json.JSONObject()
                .put("px", resources.displayMetrics.widthPixels)
                .put("density", resources.displayMetrics.density.toDouble())
                // Overviews already cached here: the phone won't render and send them again.
                .put("have", org.json.JSONArray().apply {
                    cacheDir.listFiles { f -> f.name.startsWith("tripmap3_") && f.name.endsWith("_overview.img") }
                        ?.forEach { put(it.name.removePrefix("tripmap3_").removeSuffix("_overview.img")) }
                })
                .toString(),
        )
        handler.postDelayed({
            if (tripsAdapter.itemCount == 0) tripsEmpty?.setText(R.string.trips_no_phone)
        }, 6_000L)
    }

    private fun showTrips(list: List<TripSummary>) {
        tripsAdapter.submit(list)
        tripsEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) tripsEmpty?.setText(R.string.trips_none)
    }

    private fun bindPad(v: View) {
        padBox = v.findViewById(R.id.pad_box)
        parkPage = v.findViewById(R.id.park_page)
        parkAgo = v.findViewById(R.id.park_ago)
        parkFind = v.findViewById(R.id.park_find)
        val openCompass = View.OnClickListener { startActivity(android.content.Intent(this, FindBikeActivity::class.java)) }
        parkPage?.setOnClickListener(openCompass)
        parkFind?.setOnClickListener(openCompass)
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
        v.findViewById<View>(R.id.btn_trip).setOnClickListener { b ->
            b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pager.currentItem = 0
        }
        for ((id, code) in keys) {
            v.findViewById<View>(id).setOnClickListener { b ->
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                PhoneLink.send(this, PhoneLink.PATH_KEY, code.toString())
            }
        }
        render()
    }

    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val layouts = intArrayOf(R.layout.page_trip, R.layout.page_pad, R.layout.page_info, R.layout.page_trips)
        override fun getItemCount() = layouts.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(layouts[viewType], parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                0 -> bindTrip(holder.itemView)
                1 -> bindPad(holder.itemView)
                2 -> bindInfo(holder.itemView)
                else -> bindTrips(holder.itemView)
            }
        }
    }

    companion object {
        private const val PAGES = 4
        private const val PAGE_TRIPS = 3
        private const val SUBSCRIBE_EVERY_MS = 4_000L
        private const val STALE_MS = 6_000L
        private val COLOR_GREEN = Color.parseColor("#66BB6A")
        /** In ambient we only get a snapshot per minute, so data stays "fresh" longer. */
        private const val AMBIENT_STALE_MS = 90_000L
        private val COLOR_AMBER = Color.parseColor("#FFB300")
        private val COLOR_GREY = Color.parseColor("#9E9E9E")
        private val COLOR_ACCENT = Color.parseColor("#D1A955")
    }
}
