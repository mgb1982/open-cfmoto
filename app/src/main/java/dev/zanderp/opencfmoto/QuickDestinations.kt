// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

/**
 * v2.3 · One-tap destinations under the search box while connected: 🏠 Home and 💼 Work (the first two
 * [SavedPlaces] slots, shared with the handlebar-button actions) and the next calendar events that
 * have a place, e.g. "📅 Dentist · 17:30". The calendar is only read after the rider allows it.
 */
object QuickDestinations {
    const val SLOT_HOME = 0
    const val SLOT_WORK = 1
    private const val LOOKAHEAD_MS = 12 * 60 * 60 * 1000L
    private const val MAX_EVENTS = 2

    data class Event(val title: String, val location: String, val begin: Long)

    fun hasCalendar(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Next events (now − 15 min … +12 h) with a location, soonest first. Empty without permission. */
    fun upcoming(ctx: Context): List<Event> {
        if (!hasCalendar(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000L)
            ContentUris.appendId(it, now + LOOKAHEAD_MS)
        }.build()
        val out = ArrayList<Event>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < MAX_EVENTS) {
                    val loc = c.getString(1)?.trim().orEmpty()
                    if (loc.isEmpty() || c.getInt(3) == 1) continue
                    out += Event(c.getString(0)?.trim().orEmpty(), loc, c.getLong(2))
                }
            }
        } catch (e: Exception) {
            LogBus.log("[DEST] calendar read failed: $e")
        }
        return out
    }

    /**
     * Rebuild the chip row. [navigate] starts the route (same path as the search box); [askCalendar]
     * requests READ_CALENDAR and calls [render] again when granted.
     */
    fun render(
        activity: AppCompatActivity, row: LinearLayout, navigate: (String) -> Unit, askCalendar: () -> Unit,
        pickContact: ((ArrivalNotice.Contact) -> Unit) -> Unit = {},
    ) {
        fun again() = render(activity, row, navigate, askCalendar, pickContact)
        row.removeAllViews()
        fun chip(label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val b = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
                onLong?.let { l -> setOnLongClickListener { l(); true } }
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
            })
        }
        for ((slot, emoji, nameRes) in listOf(Triple(SLOT_HOME, "🏠", R.string.dest_home), Triple(SLOT_WORK, "💼", R.string.dest_work))) {
            val label = "$emoji ${activity.getString(nameRes)}"
            if (SavedPlaces.isSet(activity, slot)) {
                // 📨 marks a place that tells someone you're on your way.
                val tag = if (ArrivalNotice.contact(activity, slot) != null) " 📨" else ""
                chip(label + tag, {
                    ArrivalNotice.onDepart(activity, slot)
                    navigate(SavedPlaces.query(activity, slot))
                }, {
                    editPlace(activity, slot, nameRes, pickContact) { again() }
                })
            } else {
                chip("$label +", { editPlace(activity, slot, nameRes, pickContact) { again() } })
            }
        }
        // Which app opens the route; tap to change it.
        chip("🧭 " + NavApps.label(activity), { NavApps.choose(activity) { again() } })
        if (!hasCalendar(activity)) {
            chip("📅 " + activity.getString(R.string.dest_calendar), askCalendar)
        } else {
            val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
            for (e in upcoming(activity)) {
                val title = e.title.ifBlank { e.location }.take(22)
                chip("📅 $title · ${fmt.format(Date(e.begin))}", { navigate(e.location) })
            }
        }
    }

    /** Set or change Home / Work (an address or place name, whatever Maps understands) and who to tell. */
    private fun editPlace(
        activity: AppCompatActivity, slot: Int, nameRes: Int,
        pickContact: ((ArrivalNotice.Contact) -> Unit) -> Unit, done: () -> Unit,
    ) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            hint = activity.getString(R.string.dest_address_hint)
            setText(SavedPlaces.query(activity, slot))
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        var contact = ArrivalNotice.contact(activity, slot)
        val who = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false }
        fun label() {
            who.text = contact?.let {
                activity.getString(R.string.arrive_who, it.name.ifBlank { it.number }) + " · " +
                    activity.getString(if (it.via == ArrivalNotice.VIA_WHATSAPP) R.string.arrive_via_wa_short else R.string.arrive_via_sms_short)
            } ?: activity.getString(R.string.arrive_none)
        }
        label()
        // After choosing the person: how to tell them (SMS on its own, or WhatsApp with the text ready).
        fun chooseVia(c: ArrivalNotice.Contact) {
            val sms = activity.getString(if (CrashDetector.smsSupported) R.string.arrive_via_sms_auto else R.string.arrive_via_sms_ready)
            if (!ArrivalNotice.whatsAppInstalled(activity)) { contact = c; label(); return }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.arrive_via_title)
                .setItems(arrayOf(sms, activity.getString(R.string.arrive_via_wa))) { _, i ->
                    contact = c.copy(via = if (i == 1) ArrivalNotice.VIA_WHATSAPP else ArrivalNotice.VIA_SMS); label()
                }.show()
        }
        who.setOnClickListener {
            if (contact == null) pickContact { c -> chooseVia(c) }
            else MaterialAlertDialogBuilder(activity)
                .setItems(arrayOf(activity.getString(R.string.arrive_change), activity.getString(R.string.arrive_via_change),
                    activity.getString(R.string.arrive_stop))) { _, i ->
                    when (i) {
                        0 -> pickContact { c -> chooseVia(c) }
                        1 -> contact?.let { chooseVia(it) }
                        else -> { contact = null; label() }
                    }
                }.show()
        }
        val hint = android.widget.TextView(activity).apply {
            text = activity.getString(R.string.arrive_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input); addView(who); addView(hint)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.dest_set_title, activity.getString(nameRes)))
            .setView(box)
            .setPositiveButton(R.string.dest_save) { _, _ ->
                SavedPlaces.set(activity, slot, activity.getString(nameRes), input.text.toString())
                ArrivalNotice.setContact(activity, slot, contact)
                done()
            }
            .setNeutralButton(R.string.dest_clear) { _, _ ->
                SavedPlaces.set(activity, slot, "", ""); ArrivalNotice.setContact(activity, slot, null); done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
