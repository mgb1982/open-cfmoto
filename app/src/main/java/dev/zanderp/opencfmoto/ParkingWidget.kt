// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import java.util.Locale

/** Home-screen widget: where the bike is parked (tap → walking directions) and the last ride. */
class ParkingWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread({
            try {
                render(ctx.applicationContext, mgr, ids)
            } finally {
                pending.finish()
            }
        }, "parking-widget").start()
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ParkingWidget::class.java))
            if (ids.isEmpty()) return
            Thread({ render(ctx.applicationContext, mgr, ids) }, "parking-widget").start()
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_parking)
            val spot = Parking.get(ctx)
            val openApp = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val last = runCatching { TripStore.list(ctx).firstOrNull() }.getOrNull()
            val lastLine = last?.let {
                ctx.getString(R.string.widget_last_ride) + ": " +
                    String.format(Locale.getDefault(), "%.1f km", it.distanceKm) + " · " +
                    ctx.getString(R.string.widget_last_ride_sub, it.avgKmh, it.maxKmh)
            }
            if (spot != null) {
                // Parked: where (distance if the phone knows where it is) + when, then the last ride.
                val dist = Parking.distanceFromHere(ctx, spot)
                val ago = DateUtils.getRelativeTimeSpanString(spot.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                v.setTextViewText(R.id.w_title, ctx.getString(R.string.widget_parked))
                v.setTextViewText(R.id.w_main, dist?.let { Parking.distanceText(it) } ?: ago)
                v.setTextViewText(R.id.w_sub, if (dist != null) ago else ctx.getString(R.string.widget_tap_directions))
                v.setTextViewText(R.id.w_ride, lastLine ?: "")
                v.setViewVisibility(R.id.w_ride, if (lastLine != null) android.view.View.VISIBLE else android.view.View.GONE)
                val walk = PendingIntent.getActivity(ctx, 2, Parking.walkIntent(spot), PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.w_root, walk)
            } else {
                v.setTextViewText(R.id.w_title, ctx.getString(R.string.widget_last_ride))
                v.setTextViewText(
                    R.id.w_main,
                    last?.let { String.format(Locale.getDefault(), "%.1f km", it.distanceKm) } ?: "—",
                )
                v.setTextViewText(
                    R.id.w_sub,
                    last?.let { TripNames.cached(ctx, it) ?: ctx.getString(R.string.widget_last_ride_sub, it.avgKmh, it.maxKmh) }
                        ?: ctx.getString(R.string.widget_no_rides),
                )
                v.setViewVisibility(R.id.w_ride, android.view.View.GONE)
                v.setOnClickPendingIntent(R.id.w_root, openApp)
            }
            for (id in ids) mgr.updateAppWidget(id, v)
        }
    }
}
