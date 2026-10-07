// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Saved rides grouped by calendar day. Prev/Next flips the day like a day calendar;
 * each trip card can open the map, export GPX, or long-press delete.
 */
class TripsListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView
    private lateinit var dayLabel: TextView
    private lateinit var daySummary: TextView
    private lateinit var todayBtn: MaterialButton

    /** Local midnight of the day currently shown. */
    private var dayStartMs: Long = 0L
    private var allTrips: List<Trip> = emptyList()
    private var didInitialDayPick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trips)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trips_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        container = findViewById(R.id.trips_container)
        empty = findViewById(R.id.trips_empty)
        dayLabel = findViewById(R.id.trips_day_label)
        daySummary = findViewById(R.id.trips_day_summary)
        todayBtn = findViewById(R.id.trips_today)

        findViewById<MaterialButton>(R.id.trips_day_prev).setOnClickListener {
            shiftDay(-1)
        }
        findViewById<MaterialButton>(R.id.trips_day_next).setOnClickListener {
            shiftDay(1)
        }
        findViewById<MaterialButton>(R.id.trips_all_map).setOnClickListener { TripMapActivity.startAll(this) }
        findViewById<MaterialButton>(R.id.trips_year).setOnClickListener { YearInReviewActivity.start(this) }
        todayBtn.setOnClickListener {
            dayStartMs = startOfDay(System.currentTimeMillis())
            renderDay()
        }

        dayStartMs = startOfDay(System.currentTimeMillis())
    }

    override fun onResume() {
        super.onResume()
        // Summaries only (cheap index); full tracks are loaded just for the day on screen.
        allTrips = TripStore.summaries(this)
        // First open: if today has no rides, land on the most recent ride day.
        if (!didInitialDayPick) {
            didInitialDayPick = true
            val today = startOfDay(System.currentTimeMillis())
            if (allTrips.none { startOfDay(it.start) == today }) {
                allTrips.firstOrNull()?.let { dayStartMs = startOfDay(it.start) }
            }
        }
        renderDay()
    }

    private fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
        cal.add(Calendar.DAY_OF_YEAR, delta)
        dayStartMs = startOfDay(cal.timeInMillis)
        renderDay()
    }

    private fun renderDay() {
        container.removeAllViews()
        val dayEnd = dayStartMs + 24L * 60L * 60L * 1000L
        val dayTrips = allTrips.filter { it.start in dayStartMs until dayEnd }
            .sortedByDescending { it.start }
            .map { TripStore.get(this, it.id) ?: it }

        dayLabel.text = DAY_FMT.format(Date(dayStartMs))
        val today = startOfDay(System.currentTimeMillis())
        todayBtn.visibility = if (dayStartMs == today) View.GONE else View.VISIBLE

        if (dayTrips.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (allTrips.isEmpty()) {
                getString(R.string.trips_no_trips_yet)
            } else {
                getString(R.string.trips_no_rides_browse_days)
            }
            daySummary.text = getString(R.string.trips_no_rides_summary)
        } else {
            empty.visibility = View.GONE
            val km = dayTrips.sumOf { it.distanceKm }
            val n = dayTrips.size
            daySummary.text = resources.getQuantityString(
                R.plurals.trips_day_summary,
                n,
                n,
                km,
            )
            for (trip in dayTrips) container.addView(buildCard(trip))
            TripNames.ensure(this, dayTrips) { runOnUiThread { if (!isFinishing) renderDay() } }
        }
        renderRecords()
    }

    /** 🏆 card at the top: longest ride, top speed, best day, longest streak. */
    private fun renderRecords() {
        val tv = findViewById<TextView?>(R.id.trips_records) ?: return
        if (allTrips.size < 2) {
            tv.visibility = View.GONE
            return
        }
        val r = Records.compute(allTrips)
        tv.text = getString(
            R.string.records_line,
            Records.km(r.longestKm), r.topKmh, Records.km(r.bestDayKm), r.bestStreak,
        )
        tv.visibility = View.VISIBLE
    }

    private fun buildCard(trip: Trip): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@TripsListActivity, R.color.surface))
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { TripMapActivity.start(this@TripsListActivity, trip.id) }
            setOnLongClickListener { confirmDelete(trip); true }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (trip.points.size >= 2) {
            val thumbH = dp(112)
            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbH,
                ).apply { bottomMargin = dp(10) }
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = getString(R.string.trips_route_preview)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                    }
                }
                setBackgroundColor(ContextCompat.getColor(this@TripsListActivity, R.color.surface_high))
            }
            preview.post {
                val w = preview.width.coerceAtLeast(dp(240))
                preview.setImageBitmap(
                    TripRouteThumb.render(
                        trip = trip,
                        widthPx = w,
                        heightPx = thumbH,
                        strokeColor = ContextCompat.getColor(this, R.color.brand_orange),
                        bgColor = ContextCompat.getColor(this, R.color.surface_high),
                        startColor = ContextCompat.getColor(this, R.color.status_live),
                        endColor = ContextCompat.getColor(this, R.color.status_error),
                    ),
                )
            }
            col.addView(preview)
        }

        TripNames.cached(this, trip)?.let { name ->
            col.addView(TextView(this).apply {
                text = name
                setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.brand_orange))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        col.addView(TextView(this).apply {
            text = trip.timeRangeText()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        stats.addView(stat(getString(R.string.trip_distance), trip.distanceText()))
        stats.addView(stat(getString(R.string.trips_stat_time), trip.durationText()))
        stats.addView(stat(getString(R.string.trips_stat_avg), "${trip.avgKmh} km/h"))
        stats.addView(stat(getString(R.string.trips_stat_max), "${trip.maxKmh} km/h"))
        col.addView(stats)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            gravity = Gravity.END
        }
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.share_button)
                textSize = 13f
                setOnClickListener { RideShare.shareTrip(this@TripsListActivity, trip) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
            },
        )
        actions.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.trips_export_gpx)
                textSize = 13f
                setOnClickListener { exportGpx(trip) }
            },
        )
        col.addView(actions)

        card.addView(col)
        return card
    }

    private fun exportGpx(trip: Trip) {
        try {
            if (trip.points.isEmpty()) {
                Toast.makeText(this, getString(R.string.trips_no_gps_points), Toast.LENGTH_SHORT).show()
                return
            }
            val dir = File(cacheDir, "trip-export").apply { mkdirs() }
            val file = File(dir, TripGpx.suggestedFileName(trip))
            file.writeText(TripGpx.toXml(trip), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.trips_export_gpx)))
            LogBus.log("→ Trip GPX exported: ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.trips_export_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun stat(label: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_secondary))
            textSize = 10f
            gravity = Gravity.START
        })
        box.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@TripsListActivity, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return box
    }

    private fun confirmDelete(trip: Trip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trips_delete_trip_title)
            .setMessage("${trip.dateText()} · ${trip.distanceText()}")
            .setPositiveButton(R.string.trips_delete) { _, _ ->
                TripStore.delete(this, trip.id)
                allTrips = TripStore.summaries(this)
                renderDay()
            }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val DAY_FMT = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripsListActivity::class.java))
        }

        fun startOfDay(epochMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
