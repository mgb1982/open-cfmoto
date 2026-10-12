// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 🎬 Trip video (v2.2): the clips you filmed during a trip (Ray-Ban Meta via the Meta AI app's
 * import, an action cam, the phone…) found in the gallery by their recording time, joined in order,
 * with the trip drawn on top — mini-map with your position, speed, time and the trip's name.
 */
@OptIn(UnstableApi::class)
object TripVideo {
    private const val MARGIN_MS = 2 * 60_000L

    data class Clip(val uri: Uri, val name: String, val startMs: Long, val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    /** Blocking. Gallery videos recorded during [trip] (container date first, gallery date as fallback). */
    fun findClips(ctx: Context, trip: Trip): List<Clip> {
        val out = ArrayList<Clip>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        // Imported later is fine (the Meta AI app copies clips after the ride): look at everything
        // added since the trip started, then trust the recording date inside the file.
        val sel = "${MediaStore.Video.Media.DATE_ADDED} >= ?"
        val args = arrayOf(((trip.start - MARGIN_MS) / 1000L).toString())
        try {
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    val name = c.getString(1).orEmpty()
                    val taken = c.getLong(2)
                    val dur = c.getLong(4)
                    val meta = metadata(ctx, uri)
                    val start = meta?.first ?: taken.takeIf { it > 0 } ?: continue
                    val durMs = meta?.second?.takeIf { it > 0 } ?: dur
                    // Some cameras stamp the END of the recording: accept either reading.
                    val overlaps = (start in (trip.start - MARGIN_MS)..(trip.end + MARGIN_MS)) ||
                        (start - durMs in (trip.start - MARGIN_MS)..(trip.end + MARGIN_MS))
                    if (!overlaps || durMs < 1000) continue
                    out += Clip(uri, name, start, durMs, meta?.third?.get(0) ?: 0, meta?.third?.get(1) ?: 0, meta?.third?.get(2) ?: 0)
                }
            }
        } catch (e: Exception) {
            LogBus.log("[VIDEO] gallery query failed: $e")
        }
        return out.sortedBy { it.startMs }
    }

    /** (recording start epoch ms, duration ms, [w, h, rotation]) from the file itself. */
    private fun metadata(ctx: Context, uri: Uri): Triple<Long?, Long, IntArray>? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            Triple(parseDate(date), dur, intArrayOf(w, h, rot))
        } finally { r.release() }
    } catch (_: Exception) { null }

    /** "20261011T083102.000Z" (MP4 creation_time, UTC); 1904/1970 placeholders → null. */
    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (f in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val t = fmt.parse(s)?.time ?: continue
                return if (t < 1_262_304_000_000L) null else t   // before 2010 = not set
            } catch (_: Exception) {}
        }
        return null
    }

    /** The edit: clips in order, overlay on each, optionally ×[speed] with no sound. */
    fun composition(ctx: Context, trip: Trip, clips: List<Clip>, speed: Float): Composition {
        val title = TripNames.title(ctx, trip)
        val items = clips.map { clip ->
            val effects = ArrayList<androidx.media3.common.Effect>()
            effects += OverlayEffect(ImmutableList.of(TripOverlay(trip, clip, title)))
            if (speed > 1f) effects += SpeedChangeEffect(speed)
            EditedMediaItem.Builder(MediaItem.fromUri(clip.uri))
                .setRemoveAudio(speed > 1f)
                .setEffects(Effects(ImmutableList.of<androidx.media3.common.audio.AudioProcessor>(), ImmutableList.copyOf(effects)))
                .build()
        }
        @Suppress("DEPRECATION")
        return Composition.Builder(EditedMediaItemSequence(items)).build()
    }

    fun outputFile(ctx: Context) = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "ridescreen-trip.mp4")

    /** Copy the finished file into Movies/RideScreen AA. @return its gallery uri. */
    fun publish(ctx: Context, trip: Trip, f: File): Uri? = try {
        val name = "RideScreen_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(trip.start)) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RideScreen AA")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    } catch (e: Exception) {
        LogBus.log("[VIDEO] save to gallery failed: $e")
        null
    }

    /**
     * Full-frame transparent overlay, redrawn every ~0.5 s of video: name + clock top-left, mini-map
     * top-right, big speed bottom-left. Frame size comes from the clip (rotation-aware).
     */
    private class TripOverlay(private val trip: Trip, private val clip: Clip, private val title: String) : BitmapOverlay() {
        private val w: Int
        private val h: Int
        private var bmp: Bitmap? = null
        private var lastBucket = -1L
        private var baseUs = -1L
        private val pts = trip.points
        private val minLat = pts.minOf { it.lat }; private val maxLat = pts.maxOf { it.lat }
        private val minLon = pts.minOf { it.lon }; private val maxLon = pts.maxOf { it.lon }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(6f, 0f, 2f, Color.BLACK) }
        private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(255, 193, 7); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
        private val panel = Paint().apply { color = Color.argb(110, 0, 0, 0) }

        init {
            val rotated = clip.rotation == 90 || clip.rotation == 270
            val cw = if (clip.width > 0) clip.width else 1280
            val ch = if (clip.height > 0) clip.height else 720
            w = if (rotated) ch else cw
            h = if (rotated) cw else ch
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (baseUs < 0) baseUs = presentationTimeUs
            val tMs = clip.startMs + (presentationTimeUs - baseUs) / 1000L
            val bucket = tMs / 500L
            bmp?.let { if (bucket == lastBucket) return it }
            lastBucket = bucket
            val b = bmp ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
            b.eraseColor(Color.TRANSPARENT)
            draw(Canvas(b), tMs)
            return b
        }

        private fun draw(c: Canvas, tMs: Long) {
            val u = minOf(w, h) / 100f
            // Where we are at tMs (nearest point).
            var idx = pts.binarySearchBy(tMs) { it.time }
            if (idx < 0) idx = (-idx - 1).coerceIn(0, pts.lastIndex)
            val p = pts[idx]
            // Top-left: name + clock.
            text.textSize = 4.2f * u
            text.isFakeBoldText = true
            c.drawText(title, 3 * u, 7 * u, text)
            text.isFakeBoldText = false
            text.textSize = 3.4f * u
            c.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tMs)), 3 * u, 12 * u, text)
            // Bottom-left: speed.
            val kmh = (p.speedMs * 3.6f).toInt().coerceAtLeast(0)
            text.textSize = 12 * u
            text.isFakeBoldText = true
            c.drawText("$kmh", 3 * u, h - 6 * u, text)
            val numW = text.measureText("$kmh")
            text.textSize = 4 * u
            text.isFakeBoldText = false
            c.drawText("km/h", 4.5f * u + numW, h - 6 * u, text)
            // Top-right: mini-map.
            val size = 30 * u
            val box = RectF(w - size - 3 * u, 3 * u, w - 3 * u, 3 * u + size)
            c.drawRoundRect(box, 2 * u, 2 * u, panel)
            val spanLat = (maxLat - minLat).coerceAtLeast(1e-4)
            val spanLon = (maxLon - minLon).coerceAtLeast(1e-4) * kotlin.math.cos(Math.toRadians(minLat))
            val scale = (size - 4 * u) / maxOf(spanLat, spanLon)
            fun x(lon: Double) = (box.centerX() + ((lon - (minLon + maxLon) / 2) * kotlin.math.cos(Math.toRadians(minLat)) * scale)).toFloat()
            fun y(lat: Double) = (box.centerY() - ((lat - (minLat + maxLat) / 2) * scale)).toFloat()
            val path = Path()
            val step = (pts.size / 400).coerceAtLeast(1)
            for (i in pts.indices step step) { val q = pts[i]; if (i == 0) path.moveTo(x(q.lon), y(q.lat)) else path.lineTo(x(q.lon), y(q.lat)) }
            route.strokeWidth = 0.7f * u
            c.drawPath(path, route)
            c.drawCircle(x(p.lon), y(p.lat), 1.4f * u, dot)
        }
    }
}
