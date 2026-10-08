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
 * Unlockable trophies from the saved trips: total km, longest ride, top speed, streaks, number of
 * rides, hours, plus a few one-offs (early bird, night rider, weekend, months). Everything is
 * computed from the trip summaries in date order, so each trophy also knows WHEN it was earned;
 * only "which ones we already announced" is stored.
 */
object Trophies {
    private const val PREFS = "trophies"
    private const val KEY_SEEN = "seen"
    private const val CHANNEL = "trophies"
    private const val NOTIF_ID = 4302
    /** Same GPS-glitch guard as [Records]. */
    private const val MAX_PLAUSIBLE_KMH = 150

    enum class Family(val emoji: String, val titleRes: Int, val descRes: Int, val tiers: List<Int>, val unit: String) {
        DISTANCE("🛣️", R.string.tr_distance, R.string.tr_distance_d, listOf(100, 500, 1000, 2500, 5000, 10000), "km"),
        LONGEST("🧭", R.string.tr_longest, R.string.tr_longest_d, listOf(25, 50, 100, 200), "km"),
        SPEED("⚡", R.string.tr_speed, R.string.tr_speed_d, listOf(70, 90, 110), "km/h"),
        STREAK("🔥", R.string.tr_streak, R.string.tr_streak_d, listOf(3, 7, 14, 30), ""),
        RIDES("🏍️", R.string.tr_rides, R.string.tr_rides_d, listOf(10, 50, 100, 250, 500), ""),
        HOURS("⏱️", R.string.tr_hours, R.string.tr_hours_d, listOf(10, 50, 100, 250), "h"),
        MONTHS("📅", R.string.tr_months, R.string.tr_months_d, listOf(6, 12), ""),
        EARLY("🌅", R.string.tr_early, R.string.tr_early_d, listOf(1), ""),
        NIGHT("🌙", R.string.tr_night, R.string.tr_night_d, listOf(1), ""),
        WEEKEND("🎉", R.string.tr_weekend, R.string.tr_weekend_d, listOf(1), ""),
    }

    data class Trophy(
        val family: Family,
        val tier: Int,          // 0-based
        val target: Int,
        val progress: Double,   // capped at target for display
        val unlockedAt: Long?,  // trip end that earned it, null = locked
    ) {
        val id: String get() = "${family.name}_$target"
        val unlocked: Boolean get() = unlockedAt != null
        /** 🥉🥈🥇 then 🏆 for the top tiers of multi-tier families; one-offs get their own emoji. */
        val medal: String get() = when {
            family.tiers.size == 1 -> family.emoji
            tier >= family.tiers.size - 1 -> "🏆"
            tier == 0 -> "🥉"
            tier == 1 -> "🥈"
            else -> "🥇"
        }
    }

    /** All trophies, in display order (families, then tiers). */
    fun compute(trips: List<Trip>): List<Trophy> {
        val sorted = trips.sortedBy { it.start }
        val unlocked = HashMap<String, Long>()
        var km = 0.0
        var hours = 0.0
        var longest = 0.0
        var top = 0
        var rides = 0
        var streak = 0
        var bestStreak = 0
        var lastDay = Long.MIN_VALUE
        val months = HashSet<Int>()
        val weekendSat = HashSet<Long>()
        val weekendSun = HashSet<Long>()
        var early = false
        var night = false
        var weekend = false

        fun hit(f: Family, value: Double, at: Long) {
            for (t in f.tiers) if (value >= t) unlocked.putIfAbsent("${f.name}_$t", at)
        }

        val cal = Calendar.getInstance()
        for (trip in sorted) {
            val at = trip.end
            rides++
            km += trip.distanceKm
            hours += trip.movingTimeMs / 3_600_000.0
            longest = maxOf(longest, trip.distanceKm)
            if (trip.maxKmh <= MAX_PLAUSIBLE_KMH) top = maxOf(top, trip.maxKmh)

            val day = Records.dayKey(trip.start)
            if (day != lastDay) {
                streak = if (day == lastDay + 1) streak + 1 else 1
                lastDay = day
            }
            bestStreak = maxOf(bestStreak, streak)

            cal.timeInMillis = trip.start
            months += cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
            val startHour = cal.get(Calendar.HOUR_OF_DAY)
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            // A ride is only "early" / "night" if it's a real ride, not a 300 m hop.
            if (trip.distanceKm >= 3.0 && startHour < 7) early = true
            cal.timeInMillis = trip.end
            val endHour = cal.get(Calendar.HOUR_OF_DAY)
            if (trip.distanceKm >= 3.0 && (endHour >= 23 || endHour < 4)) night = true
            // Weekend: a Saturday ride and a Sunday ride of the same weekend (keyed by the Saturday).
            if (dow == Calendar.SATURDAY) weekendSat += day
            if (dow == Calendar.SUNDAY) weekendSun += day - 1
            if (!weekend && weekendSat.any { it in weekendSun }) weekend = true

            hit(Family.DISTANCE, km, at)
            hit(Family.LONGEST, longest, at)
            hit(Family.SPEED, top.toDouble(), at)
            hit(Family.STREAK, bestStreak.toDouble(), at)
            hit(Family.RIDES, rides.toDouble(), at)
            hit(Family.HOURS, hours, at)
            hit(Family.MONTHS, months.size.toDouble(), at)
            if (early) hit(Family.EARLY, 1.0, at)
            if (night) hit(Family.NIGHT, 1.0, at)
            if (weekend) hit(Family.WEEKEND, 1.0, at)
        }

        val current = mapOf(
            Family.DISTANCE to km, Family.LONGEST to longest, Family.SPEED to top.toDouble(),
            Family.STREAK to bestStreak.toDouble(), Family.RIDES to rides.toDouble(), Family.HOURS to hours,
            Family.MONTHS to months.size.toDouble(),
            Family.EARLY to if (early) 1.0 else 0.0, Family.NIGHT to if (night) 1.0 else 0.0,
            Family.WEEKEND to if (weekend) 1.0 else 0.0,
        )
        return Family.values().flatMap { f ->
            f.tiers.mapIndexed { i, t ->
                Trophy(f, i, t, minOf(current[f] ?: 0.0, t.toDouble()), unlocked["${f.name}_$t"])
            }
        }
    }

    /** Family name; the tier is in the medal and the description ("Ride 1,000 km in total"). */
    fun title(ctx: Context, t: Trophy): String = ctx.getString(t.family.titleRes)

    fun description(ctx: Context, t: Trophy): String =
        if (t.family.tiers.size == 1) ctx.getString(t.family.descRes)
        else ctx.getString(t.family.descRes, fmt(t.target))

    fun progressText(t: Trophy): String {
        val u = if (t.family.unit.isNotEmpty()) " ${t.family.unit}" else ""
        return "${fmt(t.progress.toInt())} / ${fmt(t.target)}$u"
    }

    private fun fmt(v: Int): String = String.format(Locale.getDefault(), "%,d", v)

    /** After a trip is saved: announce trophies earned since last time (one notification). */
    fun onTripSaved(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val all = compute(TripStore.summaries(app)).filter { it.unlocked }
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val firstRun = !p.contains(KEY_SEEN)
            val seen = p.getStringSet(KEY_SEEN, emptySet()).orEmpty()
            val fresh = all.filter { it.id !in seen }
            p.edit().putStringSet(KEY_SEEN, all.map { it.id }.toSet()).apply()
            // First time this version runs: everything already earned is "old news" — no flood.
            if (firstRun || fresh.isEmpty()) return
            LogBus.log("[TROPHY] unlocked: ${fresh.joinToString { it.id }}")
            notify(app, fresh.map { "${it.medal} ${title(app, it)}: ${description(app, it)}" })
        } catch (e: Exception) {
            LogBus.log("[TROPHY] check failed: ${e.message}")
        }
    }

    fun unlockedCount(trips: List<Trip>): Pair<Int, Int> {
        val all = compute(trips)
        return all.count { it.unlocked } to all.size
    }

    private fun notify(ctx: Context, lines: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.tr_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 6, Intent(ctx, TrophiesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.tr_unlocked_title))
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
