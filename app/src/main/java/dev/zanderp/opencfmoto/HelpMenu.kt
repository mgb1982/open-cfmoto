// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** ❓ Help pill on the main screen: install guide, share logs, what's new, report a problem. */
object HelpMenu {
    const val GUIDE_URL = "https://mgb1982.github.io/ridescreen-aa/guia/"
    const val WATCH_GUIDE_URL = "https://mgb1982.github.io/ridescreen-aa/reloj/"

    fun show(activity: Activity, shareLogs: () -> Unit, reportProblem: () -> Unit, extras: List<Pair<String, () -> Unit>> = emptyList()) {
        val items = listOf(
            activity.getString(R.string.help_guide) to { openGuide(activity) },
            activity.getString(R.string.help_watch_guide) to { openUrl(activity, WATCH_GUIDE_URL) },
            activity.getString(R.string.help_share_logs) to shareLogs,
            activity.getString(R.string.help_whats_new) to { WhatsNew.show(activity) },
            activity.getString(R.string.help_report) to reportProblem,
        ) + extras
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.help_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    fun openGuide(ctx: Context) = openUrl(ctx, GUIDE_URL)

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * "What's new" sheet, shown once after updating to a version with new [RELEASE] notes. A fresh
 * install doesn't get it (everything is new); it's always reachable from Help.
 */
object WhatsNew {
    /** Bump together with whats_new_body / whats_new_title for the next feature release. */
    private const val RELEASE = "2.2"
    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen"

    fun maybeShow(activity: Activity) {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getString(KEY_SEEN, null)
        if (seen == RELEASE) return
        val freshInstall = try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        } catch (_: Exception) { false }
        p.edit().putString(KEY_SEEN, RELEASE).apply()
        if (!freshInstall) show(activity)
    }

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.whats_new_title)
            .setMessage(R.string.whats_new_body)
            .setPositiveButton(R.string.whats_new_ok, null)
            .show()
    }
}
