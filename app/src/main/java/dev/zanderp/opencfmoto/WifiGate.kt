// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Phone Wi‑Fi must be on before we can join the bike AP / P2P group. When it's off, join silently
 * times out — so we block early and ask the rider to turn Wi‑Fi on (dialog when the UI is up;
 * notification only as a fallback with no Activity).
 */
object WifiGate {
    private const val CHANNEL_ID = "opencfmoto_wifi"
    private const val NOTIF_ID = 4

    /** Avoid stacking multiple Wi‑Fi dialogs / re-notifying while it stays off. */
    @Volatile private var promptShowing = false
    @Volatile private var notifiedWhileOff = false

    fun isWifiEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return true // can't tell — don't block
        @Suppress("DEPRECATION")
        return wm.isWifiEnabled
    }

    /**
     * Foreground path: show a modal with **Wi‑Fi settings**. Prefer this from activities.
     * @return true when phone Wi‑Fi is on and the caller may proceed.
     */
    fun ensureEnabledOrPrompt(activity: Activity): Boolean {
        if (isWifiEnabled(activity)) {
            clearOffState(activity)
            return true
        }
        LogBus.log("[WIFI] phone Wi‑Fi is OFF — prompting for Wi‑Fi settings")
        ConnectionState.set(Phase.ERROR, activity.getString(R.string.conn_detail_wifi_off))
        showDialog(activity)
        return false
    }

    /**
     * No Activity available (background callback): post a notification that opens Wi‑Fi settings.
     */
    fun ensureEnabledOrNotify(context: Context): Boolean {
        if (isWifiEnabled(context)) {
            clearOffState(context)
            return true
        }
        LogBus.log("[WIFI] phone Wi‑Fi is OFF — posting Wi‑Fi settings notification")
        ConnectionState.set(Phase.ERROR, context.getString(R.string.conn_detail_wifi_off))
        if (!notifiedWhileOff) {
            notifiedWhileOff = true
            postNotification(context)
        }
        return false
    }

    fun cancelNotification(context: Context) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java)
                .cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    /** Android 10+: the quick Wi‑Fi panel over the app (one switch, no settings app). */
    fun openWifiPanel(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } catch (_: Exception) {
            openWifiSettings(activity)
        }
    }

    fun openWifiSettings(context: Context) {
        try {
            val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(flags))
        } catch (_: Exception) {
            try {
                val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags))
            } catch (_: Exception) {
            }
        }
    }

    private fun clearOffState(context: Context) {
        promptShowing = false
        notifiedWhileOff = false
        cancelNotification(context)
    }

    private fun showDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) {
            ensureEnabledOrNotify(activity)
            return
        }
        if (promptShowing) return
        promptShowing = true
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.wifi_off_title)
                .setMessage(R.string.wifi_off_message)
                .setPositiveButton(R.string.wifi_off_turn_on) { _, _ ->
                    promptShowing = false
                    openWifiPanel(activity)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    promptShowing = false
                }
                .setOnCancelListener { promptShowing = false }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            promptShowing = false
            LogBus.log("[WIFI] dialog failed: $e — falling back to notification")
            ensureEnabledOrNotify(activity)
        }
    }

    private fun postNotification(context: Context) {
        val app = context.applicationContext
        try {
            ensureChannel(app)
            val openWifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                app,
                0,
                openWifi,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Turn on Wi‑Fi")
                .setContentText("OpenCfMoto can’t reach the bike — tap to open Wi‑Fi settings.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            LogBus.log("[WIFI] notification failed: $e")
        }
    }

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wi‑Fi required",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when phone Wi‑Fi is off during Connect"
            }
        )
    }
}
