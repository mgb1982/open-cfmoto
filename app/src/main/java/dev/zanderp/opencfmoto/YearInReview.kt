// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos

/** "Your year on the bike": numbers for a year or a month, the story cards, and the share image. */
object YearInReview {
    private const val BCN_MADRID_KM = 620.0
    private const val MAX_PLAUSIBLE_KMH = 150
    /** Marker for the "your zone" card, which shows the heat map instead of a number. */
    const val ZONE = "🗺️"

    data class Stats(
        val label: String,
        val trips: List<Trip>,
        val km: Double,
        val days: Int,
        val hours: Double,
        val longest: Trip?,
        val topKmh: Int,
        val bestMonth: Int?,      // 0..11 (year view only)
        val bestMonthKm: Double,
        val timeOfDay: Int,       // 0 morning, 1 afternoon, 2 evening/night
        val streak: Int,
        val heat: HeatGrid,
        val liters: Double?,
        val euros: Double?,
    )

    data class Card(val emoji: String, val big: String, val label: String, val sub: String)

    fun compute(ctx: Context, all: List<Trip>, year: Int, month: Int?): Stats {
        val cal = Calendar.getInstance()
        val trips = all.filter {
            cal.timeInMillis = it.start
            cal.get(Calendar.YEAR) == year && (month == null || cal.get(Calendar.MONTH) == month)
        }
        val km = trips.sumOf { it.distanceKm }
        val days = trips.map { Records.dayKey(it.start) }.toSet().size
        val hours = trips.sumOf { it.movingTimeMs } / 3_600_000.0
        val byMonth = trips.groupBy { cal.timeInMillis = it.start; cal.get(Calendar.MONTH) }
        val best = byMonth.maxByOrNull { (_, l) -> l.sumOf { it.distanceKm } }
        val tod = trips.groupBy {
            cal.timeInMillis = it.start
            when (cal.get(Calendar.HOUR_OF_DAY)) { in 6..11 -> 0; in 12..19 -> 1; else -> 2 }
        }.maxByOrNull { (_, l) -> l.size }?.key ?: 1
        val fills = Maintenance.load(ctx).log.filter { e ->
            e.kind == "fuel" && run { cal.timeInMillis = e.at; cal.get(Calendar.YEAR) == year && (month == null || cal.get(Calendar.MONTH) == month) }
        }
        val label = if (month == null) year.toString()
            else DateFormatSymbols.getInstance().months[month].replaceFirstChar { it.titlecase(Locale.getDefault()) } + " $year"
        return Stats(
            label, trips, km, days, hours,
            trips.maxByOrNull { it.distanceKm },
            trips.filter { it.maxKmh <= MAX_PLAUSIBLE_KMH }.maxOfOrNull { it.maxKmh } ?: 0,
            if (month == null) best?.key else null,
            best?.value?.sumOf { it.distanceKm } ?: 0.0,
            tod,
            Records.compute(trips).bestStreak,
            HeatGrid.build(trips.filter { it.points.size >= 2 }),
            fills.takeIf { it.isNotEmpty() }?.sumOf { it.liters ?: 0.0 },
            fills.mapNotNull { it.euros }.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    fun cards(ctx: Context, s: Stats): List<Card> {
        val loc = Locale.getDefault()
        val out = ArrayList<Card>()
        val comp = if (s.km >= BCN_MADRID_KM)
            ctx.getString(R.string.yir_km_times, String.format(loc, "%.1f", s.km / BCN_MADRID_KM))
        else ctx.getString(R.string.yir_km_percent, (s.km / BCN_MADRID_KM * 100).toInt())
        out += Card("🏍️", String.format(loc, "%,.0f", s.km), ctx.getString(R.string.yir_km), comp)
        out += Card("📅", s.days.toString(), ctx.getString(R.string.yir_days),
            ctx.getString(R.string.yir_days_sub, s.trips.size, String.format(loc, "%.0f", s.hours)))
        if (s.streak >= 2) out += Card("🔥", s.streak.toString(), ctx.getString(R.string.yir_streak), ctx.getString(R.string.yir_streak_sub))
        s.longest?.let { t ->
            out += Card("🛣️", String.format(loc, "%.1f km", t.distanceKm), ctx.getString(R.string.yir_longest),
                TripNames.title(ctx, t))
        }
        if (s.topKmh > 0) out += Card("⚡", "${s.topKmh} km/h", ctx.getString(R.string.yir_top), ctx.getString(R.string.yir_top_sub))
        s.bestMonth?.let { m ->
            out += Card("🗓️", DateFormatSymbols.getInstance().months[m].replaceFirstChar { it.titlecase(loc) },
                ctx.getString(R.string.yir_best_month), String.format(loc, "%.0f km", s.bestMonthKm))
        }
        val tod = arrayOf(R.string.yir_tod_morning, R.string.yir_tod_afternoon, R.string.yir_tod_night)[s.timeOfDay]
        out += Card(arrayOf("🌅", "☀️", "🌙")[s.timeOfDay], ctx.getString(tod), ctx.getString(R.string.yir_tod), "")
        if (s.liters != null) {
            out += Card("⛽", String.format(loc, "%.0f L", s.liters), ctx.getString(R.string.yir_fuel),
                s.euros?.let { String.format(loc, "%.0f €", it) } ?: "")
        }
        out += Card(ZONE, "", ctx.getString(R.string.yir_zone), ctx.getString(R.string.yir_zone_sub))
        return out
    }

    // ---------------------------------------------------------------- share (story 1080×1920)

    fun share(activity: Activity, s: Stats) {
        Thread({
            try {
                val bmp = storyImage(activity, s)
                val dir = File(activity.cacheDir, "share").apply { mkdirs() }
                val f = File(dir, "RideScreen-${s.label.replace(' ', '-')}.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                activity.runOnUiThread {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", f)
                    activity.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("image/jpeg")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_text))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            activity.getString(R.string.share_chooser),
                        )
                    )
                }
            } catch (e: Exception) {
                LogBus.log("[YIR] share failed: ${e.message}")
                activity.runOnUiThread { Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show() }
            }
        }, "yir-share").start()
    }

    private fun storyImage(ctx: Context, s: Stats): Bitmap {
        val w = 1080; val h = 1920
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val gold = Color.parseColor("#D1A955"); val text = Color.parseColor("#F1F4EC"); val muted = Color.parseColor("#AEBAAA")
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#22302A"), Color.parseColor("#111813"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val black = ResourcesCompat.getFont(ctx, R.font.archivo_black) ?: Typeface.DEFAULT_BOLD
        val regular = ResourcesCompat.getFont(ctx, R.font.archivo) ?: Typeface.DEFAULT

        // Heat "glow" of the period on the top half, no map: just where you rode.
        drawHeat(c, s.heat, 60f, 220f, w - 60f, 900f)

        p.typeface = black; p.textSize = 44f; p.color = text
        c.drawText("RideScreen", 70f, 120f, p)
        p.color = gold; c.drawText("AA", 70f + p.measureText("RideScreen "), 120f, p)
        p.typeface = black; p.textSize = 96f; p.color = text
        c.drawText(ctx.getString(R.string.yir_story_title, s.label), 70f, 1030f, p)

        val loc = Locale.getDefault()
        val rows = listOfNotNull(
            String.format(loc, "%,.0f", s.km) to ctx.getString(R.string.yir_km),
            s.days.toString() to ctx.getString(R.string.yir_days),
            String.format(loc, "%.0f", s.hours) to ctx.getString(R.string.share_hours),
            s.longest?.let { String.format(loc, "%.0f", it.distanceKm) to ctx.getString(R.string.yir_longest_short) },
            (if (s.topKmh > 0) s.topKmh.toString() to ctx.getString(R.string.share_max) else null),
            (if (s.streak >= 2) s.streak.toString() to ctx.getString(R.string.yir_streak) else null),
        ).take(6)
        rows.forEachIndexed { i, (v, l) ->
            val x = 70f + (i % 2) * 480f
            val y = 1180f + (i / 2) * 210f
            p.typeface = black; p.textSize = 100f; p.color = text
            c.drawText(v, x, y, p)
            p.typeface = regular; p.textSize = 32f; p.color = gold; p.letterSpacing = 0.1f
            c.drawText(l.uppercase(loc), x + 4f, y + 50f, p)
            p.letterSpacing = 0f
        }
        p.typeface = regular; p.textSize = 28f; p.color = muted
        val foot = ctx.getString(R.string.share_footer)
        c.drawText(foot, (w - p.measureText(foot)) / 2, h - 60f, p)
        return out
    }

    /** Heat cells on a plain dark band (equirectangular, aspect-correct), glowing blue → red. */
    fun drawHeat(c: Canvas, g: HeatGrid, left: Float, top: Float, right: Float, bottom: Float) {
        if (g.cells.isEmpty()) return
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        g.cells.forEach {
            minLat = minOf(minLat, it.lat); maxLat = maxOf(maxLat, it.lat)
            minLon = minOf(minLon, it.lon); maxLon = maxOf(maxLon, it.lon)
        }
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(0.002)
        val spanY = (maxLat - minLat).coerceAtLeast(0.002)
        val bw = right - left; val bh = bottom - top
        val scale = minOf(bw / spanX, bh / spanY) * 0.92
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        val midLat = (minLat + maxLat) / 2; val midLon = (minLon + maxLon) / 2
        val r = (g.sizeDeg.first * scale * 0.8).toFloat().coerceIn(3f, 18f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (cell in g.cells) {
            val x = (cx + (cell.lon - midLon) * lonScale * scale).toFloat()
            val y = (cy - (cell.lat - midLat) * scale).toFloat()
            p.color = HeatGrid.color(cell.heat, 60); c.drawCircle(x, y, r * 2.2f, p)
            p.color = HeatGrid.color(cell.heat, 230); c.drawCircle(x, y, r, p)
        }
    }

    // ---------------------------------------------------------------- December heads-up

    fun maybeNotify(ctx: Context) {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.MONTH) != Calendar.DECEMBER || cal.get(Calendar.DAY_OF_MONTH) < 15) return
        val year = cal.get(Calendar.YEAR)
        val prefs = ctx.getSharedPreferences("yir", Context.MODE_PRIVATE)
        if (prefs.getInt("notified", 0) == year) return
        prefs.edit().putInt("notified", year).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel("yir", ctx.getString(R.string.yir_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 5, Intent(ctx, YearInReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            nm.notify(
                4501,
                NotificationCompat.Builder(ctx, "yir")
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(ctx.getString(R.string.yir_notif_title, year))
                    .setContentText(ctx.getString(R.string.yir_notif_text))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }
}
