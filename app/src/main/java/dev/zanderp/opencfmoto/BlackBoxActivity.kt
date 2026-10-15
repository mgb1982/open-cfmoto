// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.3 · 🎥 Black box: the impacts the crash detector recorded (newest first). Each one shows the
 * figures of the minute before (speed at impact, speed 5 s earlier, hardest braking, peak g, lean),
 * a speed + g chart from −60 s to +30 s, and can be shared as a text report + GPX track.
 */
class BlackBoxActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        val file = intent.getStringExtra(EXTRA_FILE)?.let { File(BlackBox.dir(this), it) }
        if (file != null && file.exists()) showEvent(file) else showList()
    }

    private fun showList() {
        root.removeAllViews()
        root.addView(title(getString(R.string.bb_title)))
        root.addView(body(getString(R.string.bb_intro)))
        val files = BlackBox.list(this)
        if (files.isEmpty()) { root.addView(body(getString(R.string.bb_empty))); return }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (f in files) {
            val s = BlackBox.load(f) ?: continue
            val speed = s.kmhAtImpact?.let { "${it.toInt()} km/h" } ?: "? km/h"
            root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                text = "${outcomeEmoji(s.outcome)} ${fmt.format(Date(s.impact))}\n${outcomeLabel(s.outcome)} · $speed · ${"%.1f".format(s.peakG)} g"
                setOnClickListener { startActivity(Intent(this@BlackBoxActivity, BlackBoxActivity::class.java).putExtra(EXTRA_FILE, f.name)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun showEvent(file: File) {
        val s = BlackBox.load(file) ?: run { showList(); return }
        root.removeAllViews()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM)
        root.addView(title("${outcomeEmoji(s.outcome)} ${getString(R.string.bb_event)}"))
        root.addView(body(fmt.format(Date(s.impact)) + " · " + outcomeLabel(s.outcome)))
        root.addView(ChartView(this, s), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(14) })
        root.addView(body(getString(R.string.bb_chart_legend)))
        root.addView(header(getString(R.string.bb_figures)))
        root.addView(fact(R.string.bb_speed_impact, s.kmhAtImpact?.let { "${it.toInt()} km/h" }))
        root.addView(fact(R.string.bb_speed_5s, s.kmh5sBefore?.let { "${it.toInt()} km/h" }))
        root.addView(fact(R.string.bb_braking, s.maxDecel?.let { "%.0f km/h/s".format(it) }))
        root.addView(fact(R.string.bb_peak_g, "%.1f g".format(s.peakG)))
        root.addView(fact(R.string.bb_tilt_impact, s.tiltAtImpact?.let { "${it.toInt()}°" }))
        root.addView(fact(R.string.bb_tilt_after, s.tiltAfter?.let { "${it.toInt()}°" }))
        root.addView(fact(R.string.bb_heading, s.bearing?.let { compass(it) }))
        root.addView(fact(R.string.bb_place, if (s.lat != null && s.lon != null) "%.5f, %.5f".format(Locale.US, s.lat, s.lon) else null))
        root.addView(body(getString(R.string.bb_disclaimer)))
        root.addView(button(getString(R.string.bb_share)) { share(s) })
        if (s.lat != null && s.lon != null) root.addView(button(getString(R.string.bb_map)) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}"))) }
            catch (_: Exception) { Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show() }
        })
        root.addView(button(getString(R.string.bb_delete)) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.bb_delete_confirm)
                .setPositiveButton(R.string.bb_delete) { _, _ -> file.delete(); finish() }
                .setNegativeButton(android.R.string.cancel, null).show()
        })
    }

    /** Plain-text report (what an insurer or an accident report asks for) + the GPX of the minute. */
    private fun share(s: BlackBox.Summary) {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM)
        val dash = "—"
        val text = buildString {
            appendLine("RideScreen AA · ${getString(R.string.bb_event)}")
            appendLine(fmt.format(Date(s.impact)))
            appendLine()
            appendLine("${getString(R.string.bb_speed_impact)}: ${s.kmhAtImpact?.let { "${it.toInt()} km/h" } ?: dash}")
            appendLine("${getString(R.string.bb_speed_5s)}: ${s.kmh5sBefore?.let { "${it.toInt()} km/h" } ?: dash}")
            appendLine("${getString(R.string.bb_braking)}: ${s.maxDecel?.let { "%.0f km/h/s".format(it) } ?: dash}")
            appendLine("${getString(R.string.bb_peak_g)}: ${"%.1f g".format(s.peakG)}")
            appendLine("${getString(R.string.bb_tilt_impact)}: ${s.tiltAtImpact?.let { "${it.toInt()}°" } ?: dash}")
            appendLine("${getString(R.string.bb_heading)}: ${s.bearing?.let { compass(it) } ?: dash}")
            if (s.lat != null && s.lon != null) appendLine("${getString(R.string.bb_place)}: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, s.lat, s.lon))
            appendLine()
            appendLine(getString(R.string.bb_disclaimer))
        }
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val gpx = File(dir, "caja-negra-${s.impact}.gpx").apply { writeText(BlackBox.gpx(s)) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", gpx)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(gpx.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.bb_share)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            LogBus.log("[BLACKBOX] share failed: $e")
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun outcomeEmoji(o: String) = when (o) { "alarm" -> "🆘"; "upright" -> "🚦"; "moving" -> "↗️"; else -> "🎥" }
    private fun outcomeLabel(o: String) = getString(when (o) {
        "alarm" -> R.string.bb_outcome_alarm; "upright" -> R.string.bb_outcome_upright
        "moving" -> R.string.bb_outcome_moving; else -> R.string.bb_outcome_other
    })
    private fun compass(b: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return "${dirs[((b + 22.5f) / 45f).toInt().mod(8)]} (${b.toInt()}°)"
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 24f; setTextColor(color(R.color.text_primary)); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private fun header(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(color(R.color.text_primary)); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(4)) }
    private fun body(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(color(R.color.text_secondary)); setPadding(0, dp(6), 0, 0) }
    private fun fact(label: Int, value: String?) = TextView(this).apply {
        text = "${getString(label)}: ${value ?: "—"}"; textSize = 15f; setTextColor(color(R.color.text_primary)); setPadding(0, dp(4), 0, 0)
    }
    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }

    /** Speed (gold line, km/h) and g-force (bars) from −60 s to +30 s, impact marked in red. */
    private class ChartView(ctx: Context, private val s: BlackBox.Summary) : View(ctx) {
        private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.brand_gold); strokeWidth = 5f; style = Paint.Style.STROKE }
        private val bar = Paint().apply { color = 0x66AEBAAA }
        private val grid = Paint().apply { color = 0x333D4F42; strokeWidth = 2f }
        private val red = Paint().apply { color = ContextCompat.getColor(ctx, R.color.status_error); strokeWidth = 4f }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.text_secondary); textSize = 28f }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val bottom = h - 34f
            val t0 = -BlackBox.BEFORE_MS.toFloat(); val t1 = BlackBox.AFTER_MS.toFloat()
            fun x(t: Long) = (t - t0) / (t1 - t0) * w
            val maxKmh = maxOf(30f, s.gps.mapNotNull { it.kmh }.maxOrNull() ?: 0f) * 1.15f
            val maxG = maxOf(3f, s.accel.maxOfOrNull { it.g } ?: 0f)
            for (k in listOf(-60_000L, -30_000L, 0L, 30_000L)) {
                c.drawLine(x(k), 0f, x(k), bottom, grid)
                c.drawText("${k / 1000} s", x(k).coerceAtMost(w - 60f) + 4f, h - 6f, txt)
            }
            for (a in s.accel) {
                val bh = (a.g / maxG) * bottom * 0.5f
                c.drawRect(x(a.t), bottom - bh, x(a.t) + 2f, bottom, bar)
            }
            val path = Path(); var started = false
            for (f in s.gps) {
                val v = f.kmh ?: continue
                val px = x(f.t); val py = bottom - v / maxKmh * bottom
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            c.drawPath(path, gold)
            c.drawLine(x(0), 0f, x(0), bottom, red)
            c.drawText("${maxKmh.toInt()} km/h", 6f, 30f, txt)
        }
    }

    companion object {
        const val EXTRA_FILE = "file"
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, BlackBoxActivity::class.java))
    }
}
