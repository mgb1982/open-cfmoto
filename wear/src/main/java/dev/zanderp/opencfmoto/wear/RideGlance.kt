// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.Locale

/**
 * What the tile and the watch-face complication show. The phone pushes a small summary on
 * /ocm/tile about once a minute while riding (Wear OS throttles tile refreshes, so no live speed),
 * plus once when the ride ends.
 */
object RideGlance {
    private const val PREFS = "glance"

    data class State(val riding: Boolean, val distanceM: Double, val movingMs: Long, val avgKmh: Double, val at: Long)

    fun save(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // End of ride: the phone's recorder may already be reset — keep the last numbers we had.
        if (!o.optBoolean("session") && o.optDouble("dist", 0.0) <= 0.0) {
            p.edit().putBoolean("riding", false).putLong("at", System.currentTimeMillis()).apply()
            refresh(ctx)
            return
        }
        p.edit()
            .putBoolean("riding", o.optBoolean("session"))
            .putFloat("dist", o.optDouble("dist", 0.0).toFloat())
            .putLong("mov", o.optLong("mov"))
            .putFloat("avg", o.optDouble("avg", 0.0).toFloat())
            .putLong("at", System.currentTimeMillis())
            .apply()
        refresh(ctx)
    }

    fun load(ctx: Context): State? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("at")) return null
        // A "riding" state older than 5 min means we missed the end of the ride.
        val at = p.getLong("at", 0)
        val riding = p.getBoolean("riding", false) && System.currentTimeMillis() - at < 5 * 60_000L
        return State(riding, p.getFloat("dist", 0f).toDouble(), p.getLong("mov", 0), p.getFloat("avg", 0f).toDouble(), at)
    }

    fun refresh(ctx: Context) {
        try {
            TileService.getUpdater(ctx).requestUpdate(RideTileService::class.java)
        } catch (_: Exception) {
        }
        try {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, RideComplicationService::class.java))
                .requestUpdateAll()
        } catch (_: Exception) {
        }
    }

    fun km(m: Double): String = String.format(Locale.getDefault(), if (m < 100_000) "%.1f" else "%.0f", m / 1000.0)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d h", s / 3600, (s % 3600) / 60)
        else String.format(Locale.ROOT, "%d min", s / 60)
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 7,
        Intent(ctx, WearMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Tile: current ride (km · time · average) or, when not riding, where the bike is parked. */
class RideTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { c ->
            c.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RES_VERSION)
                    .setFreshnessIntervalMillis(60_000)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                    .build()
            )
            "ride-tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { c ->
            c.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "ride-tile-res"
        }

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val s = RideGlance.load(this)
        val parked = PhoneLink.parked(this)
        val (head, big, sub) = when {
            s != null && s.riding -> Triple(
                getString(R.string.tile_riding),
                "${RideGlance.km(s.distanceM)} km",
                "${RideGlance.duration(s.movingMs)} · ${getString(R.string.pill_avg, s.avgKmh.toInt())}",
            )
            parked != null -> Triple(
                getString(R.string.tile_parked),
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    parked.third, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
                getString(R.string.tile_tap_directions),
            )
            s != null -> Triple(
                getString(R.string.tile_last_ride),
                "${RideGlance.km(s.distanceM)} km",
                "${RideGlance.duration(s.movingMs)} · ${getString(R.string.pill_avg, s.avgKmh.toInt())}",
            )
            else -> Triple("RideScreen AA", "—", getString(R.string.tile_no_data))
        }
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(WearMainActivity::class.java.name)
                    .build()
            ).build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(head, 13f, GOLD, false))
            .addContent(text(big, 30f, WHITE, true))
            .addContent(text(sub, 13f, GREY, false))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(launch).build())
                    .build()
            )
            .addContent(column)
            .build()
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(sizeSp))
                    .setColor(ColorBuilders.argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()

    companion object {
        private const val RES_VERSION = "1"
        private const val GOLD = 0xFFD1A955.toInt()
        private const val WHITE = 0xFFF1F4EC.toInt()
        private const val GREY = 0xFF9E9E9E.toInt()
    }
}

/** Watch-face complication (short text): km of the current or last ride. */
class RideComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(build(request.complicationType, RideGlance.load(this)))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, RideGlance.State(true, 23_400.0, 1_900_000, 41.0, 0))

    private fun build(type: ComplicationType, s: RideGlance.State?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val value = s?.let { RideGlance.km(it.distanceM) } ?: "--"
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(value).build(),
            PlainComplicationText.Builder(getString(R.string.complication_desc)).build(),
        )
            .setTitle(PlainComplicationText.Builder("km").build())
            .setTapAction(RideGlance.openApp(this))
            .build()
    }
}
