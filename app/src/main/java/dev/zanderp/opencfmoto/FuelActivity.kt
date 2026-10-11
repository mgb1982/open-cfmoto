// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import java.util.concurrent.Executors

/** ⛽ Nearby petrol stations with their SP95 price (Spain). Tap one → navigation, shown on the dash. */
class FuelActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var sub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnCheap: MaterialButton
    private lateinit var btnNear: MaterialButton
    private var result: FuelStations.Result? = null
    private var byDistance = false
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.fuel_title)
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        sub = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(sub)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCheap = pill(getString(R.string.fuel_sort_price)) { byDistance = false; render() }
        btnNear = pill(getString(R.string.fuel_sort_distance)) { byDistance = true; render() }
        bar.addView(btnCheap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        bar.addView(btnNear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        progress = ProgressBar(this)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(30)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun load() {
        sub.setText(R.string.fuel_locating)
        progress.visibility = View.VISIBLE
        val recent = RideExtras.lastLocation(this, 5 * 60_000L)
        if (recent != null) { fetch(recent); return }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { fail(getString(R.string.fuel_no_location)); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            LocationManagerCompat.getCurrentLocation(lm, provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                val l = loc ?: RideExtras.lastLocation(this, 60 * 60_000L)
                if (l == null) fail(getString(R.string.fuel_no_location)) else fetch(l)
            }
        } catch (e: Exception) {
            val l = RideExtras.lastLocation(this, 60 * 60_000L)
            if (l == null) fail(getString(R.string.fuel_no_location)) else fetch(l)
        }
    }

    private fun fetch(here: Location) {
        sub.setText(R.string.fuel_loading)
        io.execute {
            val r = try { FuelStations.near(here) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r == null) { fail(getString(R.string.fuel_offline)); return@runOnUiThread }
                result = r
                LogBus.log("[FUEL] ${r.stations.size} stations within ${r.radiusKm} km (source ${r.sourceTime})")
                render()
            }
        }
    }

    private fun fail(msg: String) {
        progress.visibility = View.GONE
        sub.text = msg
    }

    private fun render() {
        val r = result ?: return
        progress.visibility = View.GONE
        btnCheap.alpha = if (byDistance) 0.55f else 1f
        btnNear.alpha = if (byDistance) 1f else 0.55f
        list.removeAllViews()
        if (r.outsideSpain) { sub.setText(R.string.fuel_only_spain); return }
        if (r.stations.isEmpty()) { sub.text = getString(R.string.fuel_none, r.radiusKm); return }
        sub.text = getString(R.string.fuel_subtitle, r.radiusKm, r.sourceTime.substringAfter(' ').substringBeforeLast(':').ifBlank { r.sourceTime })
        val cheapest = r.stations.minOf { it.price }
        val shown = (if (byDistance) r.stations.sortedBy { it.distanceM } else r.stations).take(15)
        shown.forEach { list.addView(card(it, it.price == cheapest)) }
    }

    private fun card(s: FuelStations.Station, cheapest: Boolean): View {
        val gold = ContextCompat.getColor(this, R.color.brand_gold)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
            if (cheapest) { strokeColor = gold; strokeWidth = dp(1) }
            setOnClickListener { navigate(s) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = if (cheapest) "${s.brand.ifBlank { "—" }}  · ${getString(R.string.fuel_cheapest)}" else s.brand.ifBlank { "—" }
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = listOf(s.address, s.town).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        if (s.hours.isNotBlank()) col.addView(TextView(this).apply {
            text = s.hours
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(TextView(this).apply {
            text = String.format(Locale.getDefault(), "%.3f €", s.price)
            textSize = 20f
            setTextColor(gold)
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        right.addView(TextView(this).apply {
            text = if (s.distanceM < 1000) "${(s.distanceM / 10).toInt() * 10} m" else String.format(Locale.getDefault(), "%.1f km", s.distanceM / 1000f)
            textSize = 13f
            gravity = Gravity.END
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        row.addView(right)
        card.addView(row)
        return card
    }

    /** Google Maps turn-by-turn → Android Auto shows it on the dash. */
    private fun navigate(s: FuelStations.Station) {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${s.lat},${s.lon}")).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.brand)})")),
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
    }

    private fun pill(label: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, FuelActivity::class.java))
    }
}
