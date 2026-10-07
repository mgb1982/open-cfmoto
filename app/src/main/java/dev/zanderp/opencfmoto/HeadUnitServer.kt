// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android Auto 17.4+ only projects when its "head unit server" is running (Android Auto settings →
 * tap Version 10× → ⋮ → Start head unit server). It stops after a phone restart, so riders often
 * hit a silent failure. We check it up front and offer a shortcut to Android Auto's settings.
 * Nothing can start it for the rider: Android Auto doesn't expose that to other apps.
 */
object HeadUnitServer {
    private const val GEARHEAD = "com.google.android.projection.gearhead"
    private const val PORT = 5277

    /** null = can't tell right now (e.g. bound to the bike Wi-Fi, which hides loopback). */
    @Volatile var lastKnown: Boolean? = null
        private set

    /** Blocking (≤ 0.4 s): is something listening on Android Auto's head unit server port? */
    fun probe(): Boolean? {
        if (ConnectionState.phase.busy || ConnectionState.phase == Phase.STREAMING) return lastKnown
        val ok = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 400) }
            true
        } catch (_: Exception) {
            false
        }
        lastKnown = ok
        return ok
    }

    fun androidAutoInstalled(activity: Activity): Boolean = try {
        activity.packageManager.getPackageInfo(GEARHEAD, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Best effort to land on Android Auto's settings; logs which way worked on this phone. */
    fun openSettings(activity: Activity) {
        val pm = activity.packageManager
        val candidates = ArrayList<Pair<String, Intent>>()
        candidates += "app-preferences" to Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(GEARHEAD)
        // Exported activities of Android Auto whose name says "Settings" (names change between versions).
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(GEARHEAD, PackageManager.GET_ACTIVITIES)
            info.activities.orEmpty()
                .filter { it.exported && it.name.contains("Settings", ignoreCase = true) }
                .sortedBy { if (it.name.endsWith(".SettingsActivity")) 0 else 1 }
                .forEach { a -> candidates += a.name to Intent().setComponent(ComponentName(GEARHEAD, a.name)) }
        } catch (_: Exception) {
        }
        for ((label, intent) in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) == null) continue
            try {
                activity.startActivity(intent)
                LogBus.log("[HUS] opened Android Auto settings via $label")
                return
            } catch (_: Exception) {
            }
        }
        LogBus.log("[HUS] no Android Auto settings entry — opening app info")
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GEARHEAD"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(activity, R.string.hus_fallback_toast, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun showHowTo(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.hus_how_title)
            .setMessage(R.string.hus_how_steps)
            .setPositiveButton(R.string.hus_open) { _, _ -> openSettings(activity) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
