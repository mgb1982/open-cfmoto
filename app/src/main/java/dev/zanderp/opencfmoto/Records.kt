// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Locale

/**
 * Personal records over all saved trips: longest ride, top speed, most km in a day, longest streak of
 * consecutive riding days. A new trip that beats one gets a 🏆 notification (which Wear OS also puts
 * on the watch) and a badge on its share card.
 */
object Records {
    /** GPS can spike; a "record" above this on a 125 is a glitch, not a ride. */
    private const val MAX_PLAUSIBLE_KMH = 150

    private const val CHANNEL = "records"
    private const val NOTIF_ID = 4301
    private const val PREFS = "records"

    data class Summary(
        val longestKm: Double,
        val longestTrip: Trip?,
        val topKmh: Int,
        val topTrip: Trip?,
        val bestDayKm: Double,
        val bestDay: Long,
        val bestStreak: Int,
    )

    fun compute(trips: List<Trip>): Summary {
        val longest = trips.maxByOrNull { it.distanceKm }
        val top = trips.filter { it.maxKmh <= MAX_PLAUSIBLE_KMH }.maxByOrNull { it.maxKmh }
        val byDay = trips.groupBy { dayKey(it.start) }
        val bestDay = byDay.maxByOrNull { (_, l) -> l.sumOf { it.distanceKm } }
        // Streak of consecutive calendar days with at least one ride.
        val days = byDay.keys.sorted()
        var best = if (days.isEmpty()) 0 else 1
        var run = best
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return Summary(
            longest?.distanceKm ?: 0.0, longest,
            top?.maxKmh ?: 0, top,
            bestDay?.value?.sumOf { it.distanceKm } ?: 0.0,
            bestDay?.value?.first()?.start ?: 0L,
            best,
        )
    }

    /** Days since epoch in local time (for day grouping and streaks). */
    fun dayKey(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val offset = c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)
        return Math.floorDiv(ms + offset, 86_400_000L)
    }

    /** Called right after a trip is saved: celebrate any record it broke. */
    fun onTripSaved(ctx: Context, trip: Trip) {
        val app = ctx.applicationContext
        Thread({
            try {
                val all = TripStore.summaries(app)
                val before = compute(all.filter { it.id != trip.id })
                val after = compute(all)
                val broken = ArrayList<String>()
                if (all.size > 1 && trip.distanceKm > before.longestKm && trip.distanceKm >= 1.0) {
                    broken += app.getString(R.string.record_longest, km(trip.distanceKm))
                }
                if (all.size > 1 && trip.maxKmh in (before.topKmh + 1)..MAX_PLAUSIBLE_KMH) {
                    broken += app.getString(R.string.record_top_speed, trip.maxKmh)
                }
                if (all.size > 1 && after.bestDayKm > before.bestDayKm + 0.05 && dayKey(after.bestDay) == dayKey(trip.start)) {
                    broken += app.getString(R.string.record_best_day, km(after.bestDayKm))
                }
                if (after.bestStreak > before.bestStreak && after.bestStreak >= 3) {
                    broken += app.getString(R.string.record_streak, after.bestStreak)
                }
                if (broken.isEmpty()) return@Thread
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("badge_${trip.id}", broken.first()).apply()
                LogBus.log("[RECORD] ${broken.joinToString(" · ")}")
                notify(app, broken)
            } catch (e: Exception) {
                LogBus.log("[RECORD] check failed: ${e.message}")
            }
        }, "records").start()
    }

    /** The record this trip set, for its share card (null if none). */
    fun badge(ctx: Context, trip: Trip): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("badge_${trip.id}", null)

    fun km(v: Double): String = String.format(Locale.getDefault(), "%.1f km", v)

    private fun notify(ctx: Context, lines: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.record_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, TripsListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.record_title))
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
