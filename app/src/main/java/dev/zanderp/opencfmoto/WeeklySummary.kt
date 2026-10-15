// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale

/**
 * v2.3 · 📊 Sunday summary. Every Sunday at ~[HOUR]:00 a notification sums up the week (Monday to
 * Sunday): km, rides, time in the saddle, the longest ride, the change against last week, the
 * current streak and, if refuels are logged in Maintenance, what the fuel cost. No ride that week →
 * no notification. Scheduled with an inexact alarm (no special permission), re-armed every time
 * the app starts, after each summary and after a reboot.
 */
object WeeklySummary {
    private const val PREFS = "weekly_summary"
    private const val KEY_ON = "on"
    private const val CHANNEL = "weekly"
    private const val NOTIF_ID = 4501
    private const val HOUR = 20

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        schedule(ctx)
    }

    data class Week(
        val rides: Int, val km: Double, val movingMs: Long, val longestKm: Double,
        val prevKm: Double, val streak: Int, val fuelEuros: Double?, val fuelLiters: Double?,
    )

    /** Monday 00:00 of the week containing [now] (local time). */
    fun weekStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    fun compute(ctx: Context, now: Long = System.currentTimeMillis()): Week {
        val all = TripStore.summaries(ctx)
        val from = weekStart(now)
        val prevFrom = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.DAY_OF_MONTH, -7) }.timeInMillis
        val week = all.filter { it.start in from..now }
        val prev = all.filter { it.start in prevFrom until from }
        val km = week.sumOf { it.distanceKm }
        val cons = try { Maintenance.consumption(Maintenance.load(ctx)) } catch (_: Exception) { null }
        return Week(
            rides = week.size, km = km, movingMs = week.sumOf { it.movingTimeMs },
            longestKm = week.maxOfOrNull { it.distanceKm } ?: 0.0, prevKm = prev.sumOf { it.distanceKm },
            streak = Trophies.currentStreak(all, now),
            fuelEuros = cons?.second?.let { it * km }, fuelLiters = cons?.first?.let { it * km / 100.0 },
        )
    }

    fun text(ctx: Context, w: Week): String {
        val lines = ArrayList<String>()
        val h = w.movingMs / 3_600_000L; val m = (w.movingMs / 60_000L) % 60
        lines += ctx.getString(R.string.week_line_main, String.format(Locale.getDefault(), "%.0f", w.km), w.rides,
            if (h > 0) "$h h $m min" else "$m min")
        if (w.prevKm > 0.5) {
            val pct = ((w.km - w.prevKm) / w.prevKm * 100).toInt()
            lines += ctx.getString(if (pct >= 0) R.string.week_line_more else R.string.week_line_less, kotlin.math.abs(pct))
        }
        if (w.longestKm >= 1) lines += ctx.getString(R.string.week_line_longest, String.format(Locale.getDefault(), "%.1f", w.longestKm))
        if (w.fuelEuros != null) lines += ctx.getString(R.string.week_line_fuel_eur, String.format(Locale.getDefault(), "%.2f", w.fuelEuros),
            String.format(Locale.getDefault(), "%.1f", w.fuelLiters ?: 0.0))
        else if (w.fuelLiters != null) lines += ctx.getString(R.string.week_line_fuel_l, String.format(Locale.getDefault(), "%.1f", w.fuelLiters))
        if (w.streak >= 2) lines += ctx.getString(R.string.week_line_streak, w.streak)
        return lines.joinToString("\n")
    }

    fun notify(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        val w = compute(app)
        LogBus.log("[WEEK] ${w.rides} rides, ${w.km.toInt()} km")
        if (w.rides == 0) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.week_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 5, Intent(app, TripsListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val body = text(app, w)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(app.getString(R.string.week_title))
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    /** Next Sunday [HOUR]:00 (today if it's Sunday before then). */
    fun nextFire(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** On app start: arm it unless an alarm is already pending (so a start at 20:05 doesn't skip it). */
    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        val existing = PendingIntent.getBroadcast(app, 0, Intent(app, WeeklySummaryReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (existing == null) schedule(app)
    }

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, WeeklySummaryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (!enabled(app)) return
        // Inexact on purpose: no "alarms & reminders" permission needed; a 30 min window is fine.
        try { am.setWindow(AlarmManager.RTC_WAKEUP, nextFire(), 30 * 60_000L, pi) } catch (e: Exception) {
            LogBus.log("[WEEK] schedule failed: $e")
        }
    }
}

class WeeklySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == null) {
            try { WeeklySummary.notify(ctx) } catch (e: Exception) { LogBus.log("[WEEK] failed: $e") }
        }
        WeeklySummary.schedule(ctx)   // next Sunday (also after a reboot / app update)
    }
}
