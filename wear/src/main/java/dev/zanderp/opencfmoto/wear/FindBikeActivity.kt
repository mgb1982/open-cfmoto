// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale

/**
 * "Where's my bike?" on the watch: an arrow pointing at the parked bike plus the distance, from the
 * watch's own GPS and compass (works with the phone in a bag). Tap the pill for walking directions.
 */
class FindBikeActivity : Activity(), SensorEventListener {

    private lateinit var arrow: ImageView
    private lateinit var dist: TextView
    private lateinit var sub: TextView

    private var target: Location? = null
    private var savedAt = 0L
    private var here: Location? = null
    private var azimuth = Float.NaN
    private var smoothed = Float.NaN
    private var declination = 0f

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val lm by lazy { getSystemService(LocationManager::class.java) }
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    private val gps = LocationListener { l ->
        here = l
        declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_bike)
        arrow = findViewById(R.id.fb_arrow)
        dist = findViewById(R.id.fb_dist)
        sub = findViewById(R.id.fb_sub)
        val spot = PhoneLink.parked(this)
        if (spot == null) {
            dist.text = "—"
            sub.setText(R.string.find_none)
            arrow.visibility = View.INVISIBLE
            findViewById<View>(R.id.fb_maps).visibility = View.GONE
            return
        }
        target = Location("parked").apply { latitude = spot.first; longitude = spot.second }
        savedAt = spot.third
        findViewById<View>(R.id.fb_maps).setOnClickListener {
            val uri = Uri.parse("google.navigation:q=${spot.first},${spot.second}&mode=w")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${spot.first},${spot.second}?q=${spot.first},${spot.second}")))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, R.string.parked_no_maps, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
    }

    override fun onResume() {
        super.onResume()
        if (target == null) return
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startGps()
        render()
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(this)
        try { lm?.removeUpdates(gps) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        // Approximate-only is allowed too: rougher distance, but the arrow still works.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val m = lm ?: return
        // Fused first (the phone's fix when it's near), the watch's own GPS as well.
        for (p in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (m.allProviders.contains(p)) m.requestLocationUpdates(p, 2_000L, 1f, gps, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        for (p in listOf("fused", LocationManager.GPS_PROVIDER)) {
            try {
                m.getLastKnownLocation(p)?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { gps.onLocationChanged(it) }
            } catch (_: Exception) {
            }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        azimuth = ((Math.toDegrees(ori[0].toDouble()).toFloat() + declination) + 360f) % 360f
        render()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun render() {
        val t = target ?: return
        val h = here
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
            savedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )
        if (h == null) {
            dist.text = "--"
            sub.text = getString(R.string.find_waiting_gps) + "\n" + getString(R.string.find_parked_ago, ago)
            arrow.alpha = 0.3f
            return
        }
        val meters = h.distanceTo(t)
        dist.text = when {
            meters < 1000 -> "${(meters / 5).toInt() * 5} m"
            else -> String.format(Locale.getDefault(), "%.1f km", meters / 1000f)
        }
        val acc = if (h.hasAccuracy()) h.accuracy.toInt() else 0
        sub.text = when {
            meters <= maxOf(15f, acc.toFloat()) -> getString(R.string.find_here)
            else -> getString(R.string.find_parked_ago, ago) + if (acc > 0) " · ±$acc m" else ""
        }
        if (azimuth.isNaN()) {
            arrow.alpha = 0.3f
            return
        }
        arrow.alpha = 1f
        val target = (h.bearingTo(t) - azimuth + 360f) % 360f
        // Smooth the needle and take the short way round (no 359° → 1° spins).
        smoothed = if (smoothed.isNaN()) target else {
            var d = target - smoothed
            if (d > 180) d -= 360f
            if (d < -180) d += 360f
            (smoothed + d * 0.25f + 360f) % 360f
        }
        arrow.rotation = smoothed
    }
}
