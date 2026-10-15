// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * v2.3 · Which app the search box, the quick destinations and the handlebar nav buttons open:
 * Google Maps or Waze (both work on the dash through Android Auto). Asked the first time from the
 * phone, with "remember"; without a remembered choice, background launches use Google Maps.
 */
object NavApps {
    const val GOOGLE = "google"
    const val WAZE = "waze"
    const val GOOGLE_PKG = "com.google.android.apps.maps"
    const val WAZE_PKG = "com.waze"
    private const val PREFS = "nav_app"
    private const val KEY = "app"
    @Volatile private var sessionChoice: String? = null

    fun remembered(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Remembered choice, else this session's one-off choice, else Google Maps (or Waze if it's the only one). */
    fun current(ctx: Context): String = remembered(ctx) ?: sessionChoice
        ?: if (!installed(ctx, GOOGLE_PKG) && installed(ctx, WAZE_PKG)) WAZE else GOOGLE

    fun label(ctx: Context): String = if (current(ctx) == WAZE) "Waze" else "Google Maps"

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    /** Run [then] once an app is chosen: asks only if nothing is remembered and both apps are installed. */
    fun ensureChosen(activity: Activity, then: () -> Unit) {
        val both = installed(activity, GOOGLE_PKG) && installed(activity, WAZE_PKG)
        if (remembered(activity) != null || !both || sessionChoice != null) { then(); return }
        choose(activity, then)
    }

    /** The chooser itself (also reachable from the 🧭 chip to change a remembered choice). */
    fun choose(activity: Activity, then: (() -> Unit)? = null) {
        val remember = CheckBox(activity).apply {
            text = activity.getString(R.string.nav_app_remember)
            isChecked = true
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { setPadding(pad, pad / 2, pad, 0); addView(remember) }
        fun pick(app: String) {
            if (remember.isChecked) activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, app).apply()
            else { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply(); sessionChoice = app }
            LogBus.log("[NAV] navigation app → $app (remember=${remember.isChecked})")
            then?.invoke()
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.nav_app_title)
            .setView(box)
            .setPositiveButton("Waze") { _, _ -> pick(WAZE) }
            .setNegativeButton("Google Maps") { _, _ -> pick(GOOGLE) }
            .show()
    }
}
