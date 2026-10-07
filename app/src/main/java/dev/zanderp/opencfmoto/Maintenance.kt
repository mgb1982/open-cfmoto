// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Maintenance logbook per bike (Garage): an estimated odometer (the rider's reading + every ride
 * recorded since), tasks with km and/or time intervals, refuels with real consumption, and a
 * heads-up when something is due as you connect to the bike.
 *
 * Intervals are generic starting points, not the manufacturer's — the rider adjusts them.
 */
object Maintenance {
    private const val PREFS = "maintenance"
    private const val CHANNEL = "maintenance"
    private const val NOTIF_ID = 4401
    const val DUE_SOON_KM = 300
    const val DUE_SOON_DAYS = 15

    data class Task(
        val id: String,
        val name: String,
        val everyKm: Int?,
        val everyMonths: Int?,
        val lastKm: Double?,
        val lastAt: Long?,
        val enabled: Boolean = true,
    )

    data class Entry(val at: Long, val km: Double, val kind: String, val taskId: String?, val liters: Double?, val euros: Double?)

    data class Book(
        val bikeKey: String,
        val odoBaseKm: Double?,
        val kmSinceBase: Double,
        val tasks: List<Task>,
        val log: List<Entry>,
        /** km recorded by the app for this bike, never reset (refuel maths survive odometer fixes). */
        val appKm: Double = 0.0,
    ) {
        val odometer: Double? get() = odoBaseKm?.let { it + kmSinceBase }
    }

    enum class Level { OK, SOON, DUE, UNKNOWN }

    data class Status(val task: Task, val level: Level, val kmLeft: Double?, val daysLeft: Int?, val fraction: Float)

    // ---------------------------------------------------------------- storage

    fun bikeKey(ctx: Context): String = BikeMemory.selected(ctx)?.raw?.hashCode()?.toString() ?: "default"

    fun load(ctx: Context, key: String = bikeKey(ctx)): Book {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("book_$key", null)
            ?: return Book(key, null, 0.0, defaultTasks(ctx), emptyList())
        return try {
            val o = JSONObject(raw)
            Book(
                key,
                if (o.has("odoBase")) o.getDouble("odoBase") else null,
                o.optDouble("kmSince", 0.0),
                o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { taskFrom(a.getJSONObject(it)) } } ?: defaultTasks(ctx),
                o.optJSONArray("log")?.let { a -> (0 until a.length()).map { entryFrom(a.getJSONObject(it)) } } ?: emptyList(),
                o.optDouble("appKm", 0.0),
            )
        } catch (_: Exception) {
            Book(key, null, 0.0, defaultTasks(ctx), emptyList())
        }
    }

    @Synchronized
    fun save(ctx: Context, b: Book) {
        val o = JSONObject()
        b.odoBaseKm?.let { o.put("odoBase", it) }
        o.put("kmSince", b.kmSinceBase)
        o.put("appKm", b.appKm)
        o.put("tasks", JSONArray().apply { b.tasks.forEach { put(taskJson(it)) } })
        o.put("log", JSONArray().apply { b.log.takeLast(300).forEach { put(entryJson(it)) } })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("book_${b.bikeKey}", o.toString()).apply()
    }

    private fun defaultTasks(ctx: Context) = listOf(
        Task("oil", ctx.getString(R.string.maint_oil), 3000, 12, null, null),
        Task("air", ctx.getString(R.string.maint_air), 6000, 12, null, null),
        Task("belt", ctx.getString(R.string.maint_belt), 12000, null, null, null),
        Task("brakes", ctx.getString(R.string.maint_brakes), 6000, null, null, null),
        Task("tyres", ctx.getString(R.string.maint_tyres), 5000, null, null, null),
        Task("service", ctx.getString(R.string.maint_service), 6000, 12, null, null),
        Task("itv", ctx.getString(R.string.maint_itv), null, 24, null, null),
        Task("insurance", ctx.getString(R.string.maint_insurance), null, 12, null, null),
    )

    private fun taskJson(t: Task) = JSONObject().put("id", t.id).put("name", t.name).put("on", t.enabled).apply {
        t.everyKm?.let { put("km", it) }
        t.everyMonths?.let { put("months", it) }
        t.lastKm?.let { put("lastKm", it) }
        t.lastAt?.let { put("lastAt", it) }
    }

    private fun taskFrom(o: JSONObject) = Task(
        o.getString("id"), o.optString("name"),
        if (o.has("km")) o.getInt("km") else null,
        if (o.has("months")) o.getInt("months") else null,
        if (o.has("lastKm")) o.getDouble("lastKm") else null,
        if (o.has("lastAt")) o.getLong("lastAt") else null,
        o.optBoolean("on", true),
    )

    private fun entryJson(e: Entry) = JSONObject().put("at", e.at).put("km", e.km).put("kind", e.kind).apply {
        e.taskId?.let { put("task", it) }
        e.liters?.let { put("l", it) }
        e.euros?.let { put("eur", it) }
    }

    private fun entryFrom(o: JSONObject) = Entry(
        o.getLong("at"), o.optDouble("km", 0.0), o.optString("kind"),
        o.optString("task").ifBlank { null },
        if (o.has("l")) o.getDouble("l") else null,
        if (o.has("eur")) o.getDouble("eur") else null,
    )

    // ---------------------------------------------------------------- actions

    fun setOdometer(ctx: Context, km: Double) {
        val b = load(ctx)
        save(ctx, b.copy(odoBaseKm = km, kmSinceBase = 0.0))
    }

    fun markDone(ctx: Context, taskId: String, atKm: Double? = null) {
        val b = load(ctx)
        // Unknown km stays unknown (not 0): otherwise setting the odometer later makes it look overdue.
        val km = atKm ?: b.odometer
        val now = System.currentTimeMillis()
        save(
            ctx,
            b.copy(
                tasks = b.tasks.map { if (it.id == taskId) it.copy(lastKm = km, lastAt = now) else it },
                log = b.log + Entry(now, km ?: 0.0, "task", taskId, null, null),
            ),
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("notified_${b.bikeKey}_$taskId").apply()
    }

    fun updateTask(ctx: Context, task: Task) {
        val b = load(ctx)
        save(ctx, b.copy(tasks = b.tasks.map { if (it.id == task.id) task else it }))
    }

    fun addTask(ctx: Context, name: String, everyKm: Int?, everyMonths: Int?) {
        val b = load(ctx)
        val id = "custom_${System.currentTimeMillis()}"
        save(ctx, b.copy(tasks = b.tasks + Task(id, name, everyKm, everyMonths, b.odometer, System.currentTimeMillis())))
    }

    fun deleteTask(ctx: Context, taskId: String) {
        val b = load(ctx)
        save(ctx, b.copy(tasks = b.tasks.filter { it.id != taskId }))
    }

    fun addRefuel(ctx: Context, liters: Double, euros: Double?) {
        val b = load(ctx)
        // Refuels are positioned on the never-reset app km, so "Correct km" doesn't skew consumption.
        save(ctx, b.copy(log = b.log + Entry(System.currentTimeMillis(), b.appKm, "fuel", null, liters, euros)))
    }

    /** Every saved ride adds its km to the selected bike's odometer estimate. */
    fun onTripSaved(ctx: Context, trip: Trip) {
        val b = load(ctx)
        save(ctx, b.copy(kmSinceBase = b.kmSinceBase + trip.distanceKm, appKm = b.appKm + trip.distanceKm))
    }

    // ---------------------------------------------------------------- status

    fun status(b: Book, t: Task, now: Long = System.currentTimeMillis()): Status {
        val odo = b.odometer
        var kmLeft: Double? = null
        var daysLeft: Int? = null
        var frac = 0f
        if (t.everyKm != null && t.lastKm != null && odo != null) {
            kmLeft = t.lastKm + t.everyKm - odo
            frac = maxOf(frac, ((odo - t.lastKm) / t.everyKm).toFloat())
        }
        if (t.everyMonths != null && t.lastAt != null) {
            val due = Calendar.getInstance().apply { timeInMillis = t.lastAt; add(Calendar.MONTH, t.everyMonths) }.timeInMillis
            daysLeft = ((due - now) / 86_400_000L).toInt()
            frac = maxOf(frac, ((now - t.lastAt).toFloat() / (due - t.lastAt).coerceAtLeast(1)))
        }
        val level = when {
            kmLeft == null && daysLeft == null -> Level.UNKNOWN
            (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> Level.DUE
            (kmLeft != null && kmLeft <= DUE_SOON_KM) || (daysLeft != null && daysLeft <= DUE_SOON_DAYS) -> Level.SOON
            else -> Level.OK
        }
        return Status(t, level, kmLeft, daysLeft, frac.coerceIn(0f, 1f))
    }

    fun statusText(ctx: Context, s: Status): String = when {
        s.level == Level.UNKNOWN -> ctx.getString(R.string.maint_status_unknown)
        s.kmLeft != null && (s.daysLeft == null || s.kmLeft / 30.0 < s.daysLeft) ->
            if (s.kmLeft <= 0) ctx.getString(R.string.maint_status_over_km, fmtKm(-s.kmLeft))
            else ctx.getString(R.string.maint_status_km_left, fmtKm(s.kmLeft))
        s.daysLeft != null ->
            if (s.daysLeft <= 0) ctx.getString(R.string.maint_status_over_days, -s.daysLeft)
            else ctx.getString(R.string.maint_status_days_left, s.daysLeft)
        else -> ""
    }

    /** The most urgent known item, for the main-screen pill. */
    fun mostUrgent(ctx: Context): Status? {
        val b = load(ctx)
        return b.tasks.filter { it.enabled }.map { status(b, it) }
            .filter { it.level != Level.UNKNOWN }
            .maxByOrNull { it.fraction }
    }

    /** Real consumption (full-to-full refuels): litres after the first fill / km between first and last. */
    fun consumption(b: Book): Triple<Double, Double?, Int>? {
        val fills = b.log.filter { it.kind == "fuel" && it.liters != null }.sortedBy { it.at }
        if (fills.size < 2) return null
        val km = fills.last().km - fills.first().km
        if (km < 20) return null
        val liters = fills.drop(1).sumOf { it.liters ?: 0.0 }
        val euros = fills.drop(1).mapNotNull { it.euros }.takeIf { it.size == fills.size - 1 }?.sum()
        return Triple(liters / km * 100.0, euros?.let { it / km }, fills.size)
    }

    fun fmtKm(v: Double): String = String.format(Locale.getDefault(), "%,.0f km", v)

    /** On connecting to the bike: one heads-up per item that is due or close (not repeated daily). */
    fun checkDue(ctx: Context) {
        val app = ctx.applicationContext
        val b = load(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = b.tasks.filter { it.enabled }.map { status(b, it) }
            .filter { it.level == Level.DUE || it.level == Level.SOON }
            .filter { s -> prefs.getString("notified_${b.bikeKey}_${s.task.id}", null) != s.level.name }
        if (due.isEmpty()) return
        prefs.edit().apply { due.forEach { putString("notified_${b.bikeKey}_${it.task.id}", it.level.name) } }.apply()
        val lines = due.map { "${it.task.name}: ${statusText(app, it)}" }
        LogBus.log("[MAINT] due: ${lines.joinToString(" · ")}")
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.maint_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            app, 4, Intent(app, MaintenanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(app.getString(R.string.maint_notif_title))
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
