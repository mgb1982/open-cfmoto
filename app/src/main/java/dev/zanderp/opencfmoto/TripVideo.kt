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

    fun hasVideoAccess(ctx: Context): Boolean {
        val perms = when {
            android.os.Build.VERSION.SDK_INT >= 34 -> listOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            android.os.Build.VERSION.SDK_INT >= 33 -> listOf(android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    /** Fast (one gallery query, no file reads): videos whose recording date falls inside [trip]. */
    fun quickClipCount(ctx: Context, trip: Trip): Int = try {
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DATE_TAKEN} BETWEEN ? AND ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?",
            arrayOf((trip.start - MARGIN_MS).toString(), (trip.end + MARGIN_MS).toString(), "$MADE_PREFIX%"), null,
        )?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    // ── v2.3: videos already made for a trip. They're found by their file name, which carries the
    // trip's start (RideScreen_yyyyMMdd_HHmm.mp4, Android adds " (1)" to repeats), so the ones made
    // before this existed show up too. Our own files are visible without any permission.
    const val MADE_PREFIX = "RideScreen_"

    data class Made(val uri: Uri, val name: String, val durationMs: Long, val sizeBytes: Long, val addedMs: Long)

    fun madeName(trip: Trip) = MADE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(trip.start))

    /** Blocking, one gallery query. Newest first. */
    fun madeFor(ctx: Context, trip: Trip): List<Made> = try {
        val out = ArrayList<Made>()
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf(madeName(trip) + "%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Made(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getLong(4) * 1000L,
                )
            }
        }
        out
    } catch (e: Exception) {
        LogBus.log("[VIDEO] listing made videos failed: $e")
        emptyList()
    }

    fun thumbnail(ctx: Context, uri: Uri): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(uri, android.util.Size(480, 270), null)
    } catch (_: Exception) { null }

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
                    // Our own finished videos ("RideScreen_20261015_1410.mp4", or a copy saved back
                    // from WhatsApp as "… (1).mp4") are not raw clips: never feed them back in.
                    if (name.startsWith("RideScreen_", ignoreCase = true) || name.startsWith("ridescreen-trip", ignoreCase = true)) continue
                    val taken = c.getLong(2)
                    val dur = c.getLong(4)
                    val meta = metadata(ctx, uri)
                    // Phones (and most cameras) name the file after the START of the recording, in
                    // local time — more reliable than the container date, which some apps set to the
                    // end of the clip or to the import time.
                    val fromName = startFromName(name)
                    val start = fromName ?: meta?.first ?: taken.takeIf { it > 0 } ?: continue
                    LogBus.log("[VIDEO] clip $name: name=${fromName?.let { stamp(it) }} file=${meta?.first?.let { stamp(it) }} " +
                        "taken=${if (taken > 0) stamp(taken) else "-"} added=${stamp(c.getLong(3) * 1000)} dur=${(meta?.second ?: dur) / 1000}s → start ${stamp(start)}")
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

    private fun stamp(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** "VID_20261012_141530…", "PXL_20261012_141530123…", "20261012_141530.mp4" → local time. */
    private fun startFromName(name: String): Long? {
        val m = Regex("""(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})""").find(name) ?: return null
        return try {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(m.groupValues.drop(1).joinToString(""))?.time
        } catch (_: Exception) { null }
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

    /** How the video should look (v2.3). */
    data class Look(val style: VideoStyle, val map: VideoMap, val cards: Boolean, val watermark: Boolean)

    /** Size of the first clip as it plays (rotation applied). */
    fun frameSize(clip: Clip): Pair<Int, Int> {
        val rotated = clip.rotation == 90 || clip.rotation == 270
        val cw = if (clip.width > 0) clip.width else 1280
        val ch = if (clip.height > 0) clip.height else 720
        return if (rotated) ch to cw else cw to ch
    }

    /** A still from the middle of [clip], scaled to fit [maxSide] (for the preview and the cards). */
    fun stillOf(ctx: Context, clip: Clip, atMs: Long = clip.durationMs / 2, maxSide: Int = 1080): Bitmap? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, clip.uri)
            val (w, h) = frameSize(clip)
            val s = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * s).toInt(), (h * s).toInt())
        } finally { r.release() }
    } catch (e: Exception) { LogBus.log("[VIDEO] still failed: $e"); null }

    /** Opening / closing card as a PNG of the video's size. */
    private fun card(ctx: Context, look: TripLook, w: Int, h: Int, frame: Bitmap?, outro: Boolean, watermark: Boolean): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (outro) look.drawOutro(c, w, h, frame, watermark) else look.drawIntro(c, w, h, frame)
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, if (outro) "card-outro.png" else "card-intro.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }

    /** Size of the "best of" reel: vertical, for stories / reels. */
    const val REEL_W = 1080
    const val REEL_H = 1920

    /**
     * The edit: [intro card] + clips in order with the overlay (optionally ×[speed], no sound) + [closing card].
     * v2.3: optional [music] (looped under everything; the clips' own sound lowered when [duck]) and
     * [highlights]: only the liveliest pieces ([TripHighlights]), cropped to a vertical reel.
     */
    fun composition(
        ctx: Context, trip: Trip, clips: List<Clip>, speed: Float, offsetMs: Long, look: Look, streets: StreetTiles?,
        withCards: Boolean = look.cards, music: Uri? = null, duck: Boolean = true, highlights: Boolean = false,
    ): Composition {
        val title = TripNames.title(ctx, trip)
        val (w, h) = if (highlights) REEL_W to REEL_H else frameSize(clips.first())
        val sp = if (highlights) 1f else speed
        val items = ArrayList<EditedMediaItem>()
        val firstStill = if (withCards) stillOf(ctx, clips.first(), 0L) else null
        val lastStill = if (withCards) stillOf(ctx, clips.last(), clips.last().durationMs - 500) else null
        val cardLook = TripLook(ctx, trip, title, look.style, look.map, streets)
        if (withCards) items += imageItem(card(ctx, cardLook, w, h, firstStill, outro = false, watermark = false), if (highlights) 1500 else 2500)

        // The pieces to use: whole clips, or the best bits.
        val pieces: List<TripHighlights.Piece> = if (highlights) {
            TripHighlights.pick(trip.points, clips.map { TripHighlights.Source(it.startMs + offsetMs, it.durationMs) })
                .also { LogBus.log("[VIDEO] best-of: ${it.size} piece(s), ${it.sumOf { p -> p.lengthMs } / 1000} s") }
        } else clips.indices.map { TripHighlights.Piece(it, 0L, clips[it].durationMs) }

        val audio = ArrayList<androidx.media3.common.audio.AudioProcessor>()
        if (music != null && duck) {
            // The clips' own sound at a quarter, under the song.
            val mix = androidx.media3.common.audio.ChannelMixingAudioProcessor()
            for (n in 1..2) mix.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(n, n).scaleBy(0.25f))
            audio += mix
        }
        for (piece in pieces) {
            val clip = clips[piece.clip]
            val effects = ArrayList<androidx.media3.common.Effect>()
            if (highlights) effects += androidx.media3.effect.Presentation.createForWidthAndHeight(
                w, h, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            val overlayLook = TripLook(ctx, trip, title, look.style, look.map, streets)
            val shown = clip.copy(startMs = clip.startMs + offsetMs + piece.fromMs)
            effects += OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(
                TripOverlay(overlayLook, shown, if (highlights) w to h else null)))
            if (sp > 1f) effects += SpeedChangeEffect(sp)
            val whole = piece.fromMs == 0L && piece.toMs >= clip.durationMs
            val item = if (whole) MediaItem.fromUri(clip.uri) else MediaItem.Builder().setUri(clip.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(piece.fromMs).setEndPositionMs(piece.toMs).build()).build()
            items += EditedMediaItem.Builder(item)
                .setRemoveAudio(sp > 1f)
                .setEffects(Effects(ImmutableList.copyOf(audio), ImmutableList.copyOf(effects)))
                .build()
        }
        if (withCards) items += imageItem(card(ctx, cardLook, w, h, lastStill, outro = true, watermark = look.watermark), if (highlights) 2500 else 3500)
        firstStill?.recycle(); lastStill?.recycle()
        // Phones often record HDR (HLG/HDR10) while glasses and action cams record SDR; one
        // sequence mixing both fails with VIDEO_FRAME_PROCESSING_FAILED. Bring everything to SDR.
        @Suppress("DEPRECATION")
        val sequences = arrayListOf(EditedMediaItemSequence(items))
        if (music != null) {
            @Suppress("DEPRECATION")
            sequences += EditedMediaItemSequence(ImmutableList.of(EditedMediaItem.Builder(MediaItem.fromUri(music)).build()), /* isLooping = */ true)
        }
        // With a song, every piece of the main sequence needs an audio track to mix with (the cards
        // and the ×4 clips have none): field log 17 Oct, "Asset loader error" until it's forced.
        @Suppress("DEPRECATION")
        return Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .experimentalSetForceAudioTrack(music != null || (withCards && sp <= 1f))
            .build()
    }

    private fun imageItem(f: File, durationMs: Long): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(f)).setMimeType(androidx.media3.common.MimeTypes.IMAGE_PNG)
                .setImageDurationMs(durationMs).build()
        ).setFrameRate(30).build()

    fun outputFile(ctx: Context) = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "ridescreen-trip.mp4")

    /** Copy the finished file into Movies/RideScreen AA. @return its gallery uri. */
    fun publish(ctx: Context, trip: Trip, f: File, suffix: String = ""): Uri? = try {
        val name = madeName(trip) + suffix + ".mp4"
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

    /** Full-frame transparent overlay drawn by [TripLook], redrawn every ~0.5 s of video. */
    private class TripOverlay(private val look: TripLook, private val clip: Clip, private val size: Pair<Int, Int>? = null) : BitmapOverlay() {
        private val w: Int
        private val h: Int
        private var bmp: Bitmap? = null
        private var lastBucket = -1L
        private var baseUs = -1L

        init { val (a, b) = size ?: frameSize(clip); w = a; h = b }

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (baseUs < 0) baseUs = presentationTimeUs
            val tMs = clip.startMs + (presentationTimeUs - baseUs) / 1000L
            val bucket = tMs / 500L
            bmp?.let { if (bucket == lastBucket) return it }
            lastBucket = bucket
            val b = bmp ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
            b.eraseColor(Color.TRANSPARENT)
            look.drawFrame(Canvas(b), w, h, tMs)
            return b
        }
    }
}
