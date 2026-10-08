// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Stop everything from the background, the way the Stop button does, when the bike has been gone
 * for [BikeWifi.GIVE_UP_AFTER_MS] (switched off and the rider walked away). Leaves a quiet
 * notification so the rider knows why the app isn't waiting any more.
 */
object RideStop {
    private const val CHANNEL = "bike_gone"
    private const val NOTIF_ID = 4401

    fun install() {
        BikeWifi.onGiveUp = { ctx -> stopAll(ctx) }
    }

    fun stopAll(ctx: Context) {
        val app = ctx.applicationContext
        LogBus.log("→ stopping everything (bike not seen for ${BikeWifi.GIVE_UP_AFTER_MS / 60_000} min)")
        try { AaVideoBridge.onSteadyVideo = null } catch (_: Exception) {}
        try { AndroidAutoService.stop(app) } catch (_: Exception) {}
        try { BikeLink.prober?.stop() } catch (_: Exception) {}
        try { ProjectionHolder.projection?.stop() } catch (_: Exception) {}
        ProjectionHolder.projection = null
        try { GpxSession.clear() } catch (_: Exception) {}
        try { ProjectionService.stop(app) } catch (_: Exception) {}
        try { DashClockBle.stop() } catch (_: Exception) {}
        try { BikeWifi.leave(app, LogBus::log) } catch (_: Exception) {}
        ConnectionState.set(Phase.STOPPED, "")
        notifyStopped(app)
    }

    private fun notifyStopped(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.bike_gone_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            ctx, 5, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.bike_gone_title))
            .setContentText(ctx.getString(R.string.bike_gone_text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
