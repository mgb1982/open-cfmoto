// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists recorded rides as one JSON file per trip under `filesDir/trips/`.
 *
 * A file per trip keeps writes atomic and cheap (no rewriting a growing master list), and makes
 * delete a single unlink. Each file holds the summary plus the full GPS track so the map can redraw
 * the route offline. Points are stored as compact `[lat, lon, timeMs, speedMs]` arrays.
 */
object TripStore {
    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "trips").apply { if (!exists()) mkdirs() }

    // RideScreen AA: a small index of trip summaries (no GPS points) so lists, records, the widget
    // and the watch don't parse every track. Lives outside trips/ so list() never sees it.
    private fun indexFile(ctx: Context) = File(ctx.applicationContext.filesDir, "trips-index.json")
    @Volatile private var summaryCache: List<Trip>? = null

    /** All trips without their points (cheap), most recent first. Use [get] for a full track. */
    @Synchronized
    fun summaries(ctx: Context): List<Trip> {
        summaryCache?.let { return it }
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        val ids = files.map { it.name.removeSuffix(".json") }.toSet()
        val fromIndex = runCatching { readIndex(indexFile(ctx)) }.getOrNull()
        val result = if (fromIndex != null && fromIndex.map { it.id }.toSet() == ids) {
            fromIndex
        } else {
            // First run (or files changed behind our back): rebuild once from the full files.
            files.mapNotNull { f -> parse(f)?.copy(points = emptyList()) }.also { writeIndex(ctx, it) }
        }.sortedByDescending { it.start }
        summaryCache = result
        return result
    }

    @Synchronized
    private fun updateIndex(ctx: Context, change: (MutableList<Trip>) -> Unit) {
        val current = summaryCache ?: runCatching { readIndex(indexFile(ctx)) }.getOrNull()
        if (current == null) {
            summaryCache = null // rebuilt lazily by summaries()
            return
        }
        val list = current.toMutableList()
        change(list)
        val sorted = list.sortedByDescending { it.start }
        writeIndex(ctx, sorted)
        summaryCache = sorted
    }

    private fun readIndex(f: File): List<Trip>? {
        if (!f.exists()) return null
        val a = JSONArray(f.readText())
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Trip(
                id = o.getString("id"), start = o.optLong("start"), end = o.optLong("end"),
                distanceMeters = o.optDouble("distanceMeters"), movingTimeMs = o.optLong("movingTimeMs"),
                maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(), points = emptyList(),
            )
        }
    }

    private fun writeIndex(ctx: Context, trips: List<Trip>) {
        val a = JSONArray()
        for (t in trips) {
            a.put(
                JSONObject().put("id", t.id).put("start", t.start).put("end", t.end)
                    .put("distanceMeters", t.distanceMeters).put("movingTimeMs", t.movingTimeMs)
                    .put("maxSpeedMs", t.maxSpeedMs.toDouble())
            )
        }
        runCatching { indexFile(ctx).writeText(a.toString()) }
    }

    fun save(ctx: Context, trip: Trip) {
        val obj = JSONObject()
            .put("id", trip.id)
            .put("start", trip.start)
            .put("end", trip.end)
            .put("distanceMeters", trip.distanceMeters)
            .put("movingTimeMs", trip.movingTimeMs)
            .put("maxSpeedMs", trip.maxSpeedMs.toDouble())
        val pts = JSONArray()
        for (p in trip.points) {
            pts.put(JSONArray().put(p.lat).put(p.lon).put(p.time).put(p.speedMs.toDouble()))
        }
        obj.put("points", pts)
        runCatching { File(dir(ctx), "${trip.id}.json").writeText(obj.toString()) }
        updateIndex(ctx) { l -> l.removeAll { it.id == trip.id }; l.add(trip.copy(points = emptyList())) }
    }

    /** All saved trips, most recent first. */
    fun list(ctx: Context): List<Trip> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.start }
    }

    fun get(ctx: Context, id: String): Trip? {
        val f = File(dir(ctx), "$id.json")
        return if (f.exists()) parse(f) else null
    }

    fun delete(ctx: Context, id: String) {
        runCatching { File(dir(ctx), "$id.json").delete() }
        updateIndex(ctx) { l -> l.removeAll { it.id == id } }
    }

    private fun parse(file: File): Trip? = runCatching {
        val o = JSONObject(file.readText())
        val ptsArr = o.optJSONArray("points") ?: JSONArray()
        val points = ArrayList<TrackPoint>(ptsArr.length())
        for (i in 0 until ptsArr.length()) {
            val a = ptsArr.optJSONArray(i) ?: continue
            points.add(
                TrackPoint(
                    lat = a.optDouble(0),
                    lon = a.optDouble(1),
                    time = a.optLong(2),
                    speedMs = a.optDouble(3).toFloat(),
                )
            )
        }
        Trip(
            id = o.optString("id"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            distanceMeters = o.optDouble("distanceMeters"),
            movingTimeMs = o.optLong("movingTimeMs"),
            maxSpeedMs = o.optDouble("maxSpeedMs").toFloat(),
            points = points,
        )
    }.getOrNull()
}
