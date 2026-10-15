// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v2.3 · 📨 "On my way, I'll be there at 19:42." When the rider taps 🏠 Home or 💼 Work, the contact
 * chosen for that place gets a text with the arrival time: route time from OSRM (straight line at
 * an urban average if there's no network). GitHub build with SMS allowed → sent on its own; Play
 * build or no permission → the SMS app opens with the text ready, one tap to send. Not repeated
 * for the same place within [REPEAT_MS].
 */
object ArrivalNotice {
    private const val PREFS = "arrival_notice"
    private const val REPEAT_MS = 30 * 60_000L
    private const val NEAR_M = 300.0
    private const val FALLBACK_KMH = 25.0
    private const val ROAD_FACTOR = 1.3

    const val VIA_SMS = "sms"
    const val VIA_WHATSAPP = "wa"

    data class Contact(val name: String, val number: String, val via: String = VIA_SMS)

    fun whatsAppInstalled(ctx: Context) = listOf("com.whatsapp", "com.whatsapp.w4b").any {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun contact(ctx: Context, slot: Int): Contact? {
        val p = prefs(ctx)
        val n = p.getString("num$slot", null)?.takeIf { it.isNotBlank() } ?: return null
        return Contact(p.getString("name$slot", "").orEmpty(), n, p.getString("via$slot", VIA_SMS) ?: VIA_SMS)
    }

    fun setContact(ctx: Context, slot: Int, c: Contact?) {
        prefs(ctx).edit().apply {
            if (c == null) { remove("num$slot"); remove("name$slot"); remove("via$slot") }
            else { putString("num$slot", c.number); putString("name$slot", c.name); putString("via$slot", c.via) }
        }.apply()
    }

    /** Called when the rider sets off to [slot] (Home / Work chip). Does nothing without a contact. */
    fun onDepart(activity: Activity, slot: Int) {
        val c = contact(activity, slot) ?: return
        val p = prefs(activity)
        val now = System.currentTimeMillis()
        if (now - p.getLong("sent$slot", 0L) < REPEAT_MS) { LogBus.log("[ARRIVE] already told ${c.name} recently"); return }
        val query = SavedPlaces.query(activity, slot)
        val app = activity.applicationContext
        Thread {
            val here = RideExtras.lastLocation(app, 10 * 60_000L)
            if (here == null) { LogBus.log("[ARRIVE] no location, not sending"); return@Thread }
            val dest = destination(app, slot, query, here.latitude, here.longitude)
            if (dest == null) { LogBus.log("[ARRIVE] couldn't find \"$query\""); return@Thread }
            val straight = metres(here.latitude, here.longitude, dest.first, dest.second)
            if (straight < NEAR_M) { LogBus.log("[ARRIVE] already there"); return@Thread }
            val seconds = try {
                OsrmRouter.route(here.latitude, here.longitude, dest.first, dest.second).durationSec
            } catch (e: Exception) {
                LogBus.log("[ARRIVE] route failed ($e), estimating")
                straight * ROAD_FACTOR / (FALLBACK_KMH / 3.6)
            }
            val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now + seconds.toLong() * 1000))
            val text = app.getString(if (slot == QuickDestinations.SLOT_HOME) R.string.arrive_sms_home else R.string.arrive_sms_work, at, minutes)
            p.edit().putLong("sent$slot", now).apply()
            activity.runOnUiThread { send(activity, c, text, at) }
        }.start()
    }

    private fun send(activity: Activity, c: Contact, text: String, at: String) {
        val who = c.name.ifBlank { c.number }
        if (c.via == VIA_WHATSAPP && openWhatsApp(activity, c, text)) return
        if (c.via == VIA_SMS && CrashDetector.canSms(activity)) {
            try {
                val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(text), null, null)
                LogBus.log("[ARRIVE] SMS sent (arrival $at)")
                Toast.makeText(activity, activity.getString(R.string.arrive_sent, who, at), Toast.LENGTH_LONG).show()
                return
            } catch (e: Exception) {
                LogBus.log("[ARRIVE] SMS failed: $e — opening the SMS app")
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", text))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * WhatsApp can't be sent by another app: open the chat with the text written, one tap on Send.
     * wa.me needs the international number, so local numbers get the SIM's country code.
     */
    private fun openWhatsApp(activity: Activity, c: Contact, text: String): Boolean {
        val country = try {
            (activity.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                ?.let { it.simCountryIso.ifBlank { it.networkCountryIso } }?.uppercase()
        } catch (_: Exception) { null }?.ifBlank { null } ?: java.util.Locale.getDefault().country.ifBlank { "ES" }
        val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(c.number, country)
            ?: c.number.filter { it.isDigit() || it == '+' }
        val digits = e164.filter { it.isDigit() }
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { if (pkg != null) setPackage(pkg) })
                LogBus.log("[ARRIVE] WhatsApp opened with the text")
                return true
            } catch (_: Exception) {}
        }
        LogBus.log("[ARRIVE] WhatsApp not available, falling back to SMS")
        return false
    }

    /** Coordinates of Home / Work, geocoded once per address and remembered. */
    private fun destination(ctx: Context, slot: Int, query: String, nearLat: Double, nearLon: Double): Pair<Double, Double>? {
        val p = prefs(ctx)
        if (p.getString("q$slot", null) == query) {
            val lat = p.getString("lat$slot", null)?.toDoubleOrNull(); val lon = p.getString("lon$slot", null)?.toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        val hit = try { NominatimSearch.search(query, nearLat, nearLon).firstOrNull() } catch (_: Exception) { null } ?: return null
        p.edit().putString("q$slot", query).putString("lat$slot", hit.lat.toString()).putString("lon$slot", hit.lon.toString()).apply()
        return hit.lat to hit.lon
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}
