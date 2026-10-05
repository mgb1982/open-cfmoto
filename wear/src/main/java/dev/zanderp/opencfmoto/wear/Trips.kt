// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.wear

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved ride as listed by the phone (WearTrips.sendList). */
data class TripSummary(
    val id: String,
    val start: Long,
    val end: Long,
    val distanceM: Double,
    val movingMs: Long,
    val maxKmh: Int,
    val avgKmh: Int,
) {
    fun dateText(): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(start))
    fun timeRange(): String {
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${f.format(Date(start))}–${f.format(Date(end))}"
    }
    fun kmText(): String = String.format(Locale.getDefault(), "%.2f km", distanceM / 1000.0)
    fun movingText(): String {
        val s = movingMs / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    companion object {
        fun parseList(json: String): List<TripSummary> = try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                TripSummary(
                    id = o.optString("id"),
                    start = o.optLong("start"),
                    end = o.optLong("end"),
                    distanceM = o.optDouble("dist", 0.0),
                    movingMs = o.optLong("mov"),
                    maxKmh = o.optInt("max"),
                    avgKmh = o.optInt("avg"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class TripsAdapter(private val onPick: (TripSummary) -> Unit) : RecyclerView.Adapter<TripsAdapter.Holder>() {
    private var items: List<TripSummary> = emptyList()

    fun submit(list: List<TripSummary>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = items[position]
        holder.date.text = "${t.dateText()} · ${t.timeRange()}"
        holder.km.text = t.kmText()
        holder.detail.text = holder.itemView.context.getString(
            R.string.trip_line, t.movingText(), t.avgKmh, t.maxKmh,
        )
        holder.itemView.setOnClickListener { onPick(t) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val date: TextView = v.findViewById(R.id.tv_trip_date)
        val km: TextView = v.findViewById(R.id.tv_trip_km)
        val detail: TextView = v.findViewById(R.id.tv_trip_detail)
    }
}
