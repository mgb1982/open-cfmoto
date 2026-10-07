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

    /** Resized: the background photo is cropped to the widget's shape, so redraw it. */
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        updateAll(ctx)
    }

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
        /**
         * The Garage photo as the widget background: centre-cropped to the widget's aspect, small
         * enough for RemoteViews (≤ 640 px wide), darkened at the bottom so the text stays readable,
         * with the card's rounded corners and gold edge baked in.
         */
        private fun background(ctx: Context, path: String, opts: android.os.Bundle): android.graphics.Bitmap? {
            val dm = ctx.resources.displayMetrics
            // Portrait home screen: min width × max height (dp) is the size actually shown.
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).coerceAtLeast(80)
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).coerceAtLeast(60)
            val outW = minOf((wDp * dm.density).toInt(), 640)
            val outH = (outW * hDp.toFloat() / wDp).toInt().coerceIn(80, 900)

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= outW && bounds.outHeight / (sample * 2) >= outH) sample *= 2
            val src = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null

            // Centre-crop to the output aspect.
            val target = outW.toFloat() / outH
            val cropW: Int; val cropH: Int
            if (src.width.toFloat() / src.height > target) { cropH = src.height; cropW = (src.height * target).toInt() }
            else { cropW = src.width; cropH = (src.width / target).toInt() }
            val srcRect = android.graphics.Rect((src.width - cropW) / 2, (src.height - cropH) / 2,
                (src.width + cropW) / 2, (src.height + cropH) / 2)

            val out = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val r = 22f * dm.density * outW / (wDp * dm.density)
            val rect = android.graphics.RectF(0f, 0f, outW.toFloat(), outH.toFloat())
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            // Rounded mask, then the photo inside it.
            c.drawRoundRect(rect, r, r, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            c.drawBitmap(src, srcRect, rect, paint)
            paint.xfermode = null
            src.recycle()
            // Readability: dark gradient from the middle down, plus a light overall dim.
            paint.shader = android.graphics.LinearGradient(0f, outH * 0.35f, 0f, outH.toFloat(),
                0x33000000, 0xD9101612.toInt(), android.graphics.Shader.TileMode.CLAMP)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            c.drawRect(rect, paint)
            paint.shader = null
            paint.xfermode = null
            // Gold edge, like the plain card.
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1.5f * dm.density * outW / (wDp * dm.density)
            paint.color = androidx.core.content.ContextCompat.getColor(ctx, R.color.brand_orange)
            val inset = paint.strokeWidth / 2
            c.drawRoundRect(android.graphics.RectF(inset, inset, outW - inset, outH - inset), r, r, paint)
            return out
        }

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
            val last = runCatching { TripStore.summaries(ctx).firstOrNull() }.getOrNull()
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
            val photo = BikeMemory.selected(ctx)?.photoPath
            for (id in ids) {
                // Each widget instance may have its own size: crop the photo to it.
                val bg = photo?.let { runCatching { background(ctx, it, mgr.getAppWidgetOptions(id)) }.getOrNull() }
                if (bg != null) {
                    v.setImageViewBitmap(R.id.w_bg, bg)
                    v.setViewVisibility(R.id.w_bg, android.view.View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.w_bg, android.view.View.GONE)
                }
                mgr.updateAppWidget(id, v)
            }
        }
    }
}
