// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * Shareable ride cards (1080×1350, Instagram/WhatsApp friendly) in the app's matte green + gold:
 * the route on a dark map, the bike photo and name, and the numbers. One trip, or every trip at once
 * ("everything I've ridden").
 */
object RideShare {
    private const val W = 1080
    private const val H = 1350
    private const val MAP_TOP = 150
    private const val MAP_H = 760
    private const val SNAPSHOT_TIMEOUT_MS = 15_000L
    private const val MAX_POINTS_PER_TRACK = 400

    private val BG = Color.parseColor("#18211B")
    private val GOLD = Color.parseColor("#D1A955")
    private val TEXT = Color.parseColor("#F1F4EC")
    private val MUTED = Color.parseColor("#AEBAAA")

    private val main = Handler(Looper.getMainLooper())

    fun shareTrip(activity: Activity, trip: Trip) {
        if (trip.points.size < 2) {
            Toast.makeText(activity, R.string.trips_no_gps_points, Toast.LENGTH_SHORT).show()
            return
        }
        val stats = listOf(
            activity.getString(R.string.share_km) to String.format(Locale.getDefault(), "%.1f", trip.distanceKm),
            activity.getString(R.string.share_time) to trip.durationText().removePrefix("00:"),
            activity.getString(R.string.share_avg) to trip.avgKmh.toString(),
            activity.getString(R.string.share_max) to trip.maxKmh.toString(),
        )
        val date = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault()).format(Date(trip.start))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        val name = TripNames.cached(activity, trip)
        val badge = Records.badge(activity, trip)
        val title = listOfNotNull(name, date, badge?.let { "🏆 $it" }).joinToString(" · ")
        build(activity, listOf(trip.points.map { LatLng(it.lat, it.lon) }), title, stats, "ride-${trip.id}")
    }

    fun shareAll(activity: Activity, trips: List<Trip>, heat: Boolean = false) {
        val tracks = trips.filter { it.points.size >= 2 }.map { t -> t.points.map { LatLng(it.lat, it.lon) } }
        if (tracks.isEmpty()) {
            Toast.makeText(activity, R.string.trips_no_gps_points, Toast.LENGTH_SHORT).show()
            return
        }
        val km = trips.sumOf { it.distanceKm }
        val hours = trips.sumOf { it.movingTimeMs } / 3_600_000.0
        val since = trips.minOf { it.start }
        val stats = listOf(
            activity.getString(R.string.share_km) to String.format(Locale.getDefault(), "%.0f", km),
            activity.getString(R.string.share_rides) to trips.size.toString(),
            activity.getString(R.string.share_hours) to String.format(Locale.getDefault(), "%.0f", hours),
            activity.getString(R.string.share_max) to (trips.maxOfOrNull { it.maxKmh } ?: 0).toString(),
        )
        val title = activity.getString(
            R.string.share_all_title,
            SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(since)),
        )
        if (!heat) {
            build(activity, tracks, title, stats, "all-rides", null)
            return
        }
        Thread({
            val grid = HeatGrid.build(trips.filter { it.points.size >= 2 })
            main.post { build(activity, tracks, title, stats, "heat", grid) }
        }, "heat-grid").start()
    }

    // ---- composition ----

    private fun build(
        activity: Activity,
        tracksIn: List<List<LatLng>>,
        title: String,
        stats: List<Pair<String, String>>,
        fileStem: String,
        heat: HeatGrid? = null,
    ) {
        Toast.makeText(activity, R.string.share_preparing, Toast.LENGTH_SHORT).show()
        val tracks = tracksIn.map { downsample(it, MAX_POINTS_PER_TRACK) }
        val bounds = frame(tracks.flatten())
        val app = activity.applicationContext
        val density = 2f
        var done = false

        fun finish(map: Bitmap?, project: ((LatLng) -> PointF)?) {
            if (done) return
            done = true
            Thread({
                try {
                    val card = compose(activity, map, tracks, project, bounds, title, stats, heat)
                    val dir = File(app.cacheDir, "share").apply { mkdirs() }
                    val file = File(dir, "RideScreen-$fileStem.jpg")
                    file.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    main.post { send(activity, file) }
                } catch (e: Exception) {
                    LogBus.log("[SHARE] failed: ${e.message}")
                    main.post { Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show() }
                }
            }, "ride-share").start()
        }

        main.post {
            val snap = try {
                MapSnapshotter(
                    app,
                    MapSnapshotter.Options((W / density).toInt(), (MAP_H / density).toInt())
                        .withStyleBuilder(Style.Builder().fromUri(MapLibreDashController.STYLE_NIGHT))
                        .withRegion(bounds)
                        .withPixelRatio(density)
                        .withLogo(false),
                )
            } catch (e: Exception) {
                finish(null, null)
                return@post
            }
            main.postDelayed({
                if (!done) {
                    try { snap.cancel() } catch (_: Exception) {}
                    finish(null, null)
                }
            }, SNAPSHOT_TIMEOUT_MS)
            try {
                snap.start(
                    object : MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: MapSnapshot) {
                            // Two corners pin a Web-Mercator projection valid for any point (tracks or
                            // heat cells), usable off the main thread once the snapshot is gone.
                            val nw = snapshot.pixelForLatLng(LatLng(bounds.latitudeNorth, bounds.longitudeWest))
                            val se = snapshot.pixelForLatLng(LatLng(bounds.latitudeSouth, bounds.longitudeEast))
                            val mN = merc(bounds.latitudeNorth)
                            val mS = merc(bounds.latitudeSouth)
                            val w = bounds.longitudeWest
                            val e = bounds.longitudeEast
                            finish(snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, false)) { p ->
                                PointF(
                                    (nw.x + (p.longitude - w) / (e - w) * (se.x - nw.x)).toFloat(),
                                    (nw.y + (mN - merc(p.latitude)) / (mN - mS) * (se.y - nw.y)).toFloat(),
                                )
                            }
                        }
                    },
                    object : MapSnapshotter.ErrorHandler {
                        override fun onError(error: String) = finish(null, null)
                    },
                )
            } catch (_: Exception) {
                finish(null, null)
            }
        }
    }

    private fun compose(
        activity: Activity,
        map: Bitmap?,
        tracks: List<List<LatLng>>,
        projectIn: ((LatLng) -> PointF)?,
        b: LatLngBounds,
        title: String,
        stats: List<Pair<String, String>>,
        heat: HeatGrid? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(BG)
        val black = ResourcesCompat.getFont(activity, R.font.archivo_black) ?: Typeface.DEFAULT_BOLD
        val regular = ResourcesCompat.getFont(activity, R.font.archivo) ?: Typeface.DEFAULT

        // Header: wordmark + title.
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = black; p.textSize = 40f; p.color = TEXT
        c.drawText("RideScreen", 60f, 80f, p)
        val wm = p.measureText("RideScreen ")
        p.color = GOLD
        c.drawText("AA", 60f + wm, 80f, p)
        p.typeface = regular; p.textSize = 30f; p.color = MUTED
        c.drawText(fit(p, title, W - 120f), 60f, 124f, p)

        // Map band (or a plain dark band with the track when the map can't load offline).
        val mapRect = RectF(0f, MAP_TOP.toFloat(), W.toFloat(), (MAP_TOP + MAP_H).toFloat())
        val project: (LatLng) -> PointF
        if (map != null && projectIn != null) {
            val scaled = Bitmap.createScaledBitmap(map, W, MAP_H, true)
            c.drawBitmap(scaled, 0f, MAP_TOP.toFloat(), null)
            val sx = W.toFloat() / map.width
            val sy = MAP_H.toFloat() / map.height
            project = { ll -> projectIn(ll).let { PointF(it.x * sx, MAP_TOP + it.y * sy) } }
        } else {
            p.shader = null; p.color = Color.parseColor("#202B23"); p.style = Paint.Style.FILL
            c.drawRect(mapRect, p)
            project = { ll ->
                PointF(
                    ((ll.longitude - b.longitudeWest) / (b.longitudeEast - b.longitudeWest) * W).toFloat(),
                    (MAP_TOP + (b.latitudeNorth - ll.latitude) / (b.latitudeNorth - b.latitudeSouth) * MAP_H).toFloat(),
                )
            }
        }
        c.save()
        c.clipRect(mapRect)
        if (heat != null) drawHeat(c, heat, project) else drawTracks(c, tracks, project)
        c.restore()
        // Fade the map into the background at the bottom.
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, MAP_TOP + MAP_H - 220f, 0f, MAP_TOP + MAP_H.toFloat(), Color.TRANSPARENT, BG, Shader.TileMode.CLAMP)
        c.drawRect(0f, MAP_TOP + MAP_H - 220f, W.toFloat(), MAP_TOP + MAP_H.toFloat(), p)
        p.shader = null

        // Bike: round photo + name.
        val bike = BikeMemory.selected(activity)
        val photo = bike?.photoPath?.let { path ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 400) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
        val rowY = MAP_TOP + MAP_H - 70f
        var nameX = 60f
        if (photo != null) {
            val r = 80f
            val cx = 60f + r
            val cy = rowY
            val side = minOf(photo.width, photo.height)
            val crop = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
            val sc = Bitmap.createScaledBitmap(crop, (r * 2).toInt(), (r * 2).toInt(), true)
            val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(sc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setTranslate(cx - r, cy - r) })
                }
            }
            c.drawCircle(cx, cy, r, ps)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = GOLD }
            c.drawCircle(cx, cy, r, ring)
            nameX = cx + r + 30f
        }
        p.typeface = black; p.textSize = 52f; p.color = GOLD
        c.drawText(fit(p, bike?.name ?: "RideScreen AA", W - nameX - 60f), nameX, rowY + 18f, p)

        // Stats 2×2.
        val top = MAP_TOP + MAP_H + 70f
        val colW = (W - 120f) / 2
        stats.forEachIndexed { i, (label, value) ->
            val x = 60f + (i % 2) * colW
            val y = top + (i / 2) * 170f
            p.typeface = black; p.textSize = 96f; p.color = TEXT
            c.drawText(value, x, y + 80f, p)
            p.typeface = regular; p.textSize = 30f; p.color = GOLD; p.letterSpacing = 0.12f
            c.drawText(label.uppercase(Locale.getDefault()), x + 4f, y + 125f, p)
            p.letterSpacing = 0f
        }

        // Footer.
        p.typeface = regular; p.textSize = 24f; p.color = MUTED
        val foot = activity.getString(R.string.share_footer)
        c.drawText(foot, (W - p.measureText(foot)) / 2, H - 36f, p)
        return out
    }

    private fun merc(lat: Double): Double =
        kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))

    private fun drawHeat(c: Canvas, grid: HeatGrid, project: (LatLng) -> PointF) {
        if (grid.cells.isEmpty()) return
        val dLat = grid.sizeDeg.first
        val f = grid.cells.first()
        val a = project(LatLng(f.lat, f.lon))
        val b = project(LatLng(f.lat + dLat, f.lon))
        val r = maxOf(kotlin.math.abs(a.y - b.y) * 0.75f, 7f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (cell in grid.cells) {
            val q = project(LatLng(cell.lat, cell.lon))
            p.color = HeatGrid.color(cell.heat, 70); c.drawCircle(q.x, q.y, r * 1.9f, p)
            p.color = HeatGrid.color(cell.heat, 220); c.drawCircle(q.x, q.y, r, p)
        }
    }

    private fun drawTracks(c: Canvas, tracks: List<List<LatLng>>, project: (LatLng) -> PointF) {
        val many = tracks.size > 1
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#0E1410"); strokeWidth = if (many) 9f else 16f
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = GOLD; strokeWidth = if (many) 5f else 9f
            if (many) alpha = 190
        }
        val paths = tracks.map { t ->
            Path().apply { t.forEachIndexed { i, ll -> project(ll).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        }
        paths.forEach { c.drawPath(it, casing) }
        paths.forEach { c.drawPath(it, line) }
        if (!many) {
            val t = tracks.first()
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            listOf(t.first() to Color.parseColor("#16A34A"), t.last() to Color.parseColor("#E11D48")).forEach { (ll, col) ->
                val q = project(ll)
                dot.color = Color.WHITE; c.drawCircle(q.x, q.y, 16f, dot)
                dot.color = col; c.drawCircle(q.x, q.y, 11f, dot)
            }
        }
    }

    private fun fit(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    /** Bounds with the map band's aspect (W:MAP_H) around the tracks, plus a margin. */
    private fun frame(pts: List<LatLng>): LatLngBounds {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        for (p in pts) {
            minLat = minOf(minLat, p.latitude); maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude); maxLon = maxOf(maxLon, p.longitude)
        }
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        val lonScale = cos(Math.toRadians(cLat)).coerceAtLeast(0.2)
        val aspect = W.toDouble() / MAP_H
        var spanLat = (maxLat - minLat).coerceAtLeast(0.004) * 1.35
        var spanLonScaled = (maxLon - minLon) * lonScale * 1.35
        // Make it the band's aspect; leave room at the bottom for the bike row.
        spanLonScaled = max(spanLonScaled, spanLat * aspect)
        spanLat = max(spanLat, spanLonScaled / aspect) * 1.12
        val centerLat = cLat - spanLat * 0.06
        val halfLat = spanLat / 2
        val halfLon = spanLonScaled / lonScale / 2
        return LatLngBounds.from(centerLat + halfLat, cLon + halfLon, centerLat - halfLat, cLon - halfLon)
    }

    private fun downsample(pts: List<LatLng>, maxPts: Int): List<LatLng> {
        if (pts.size <= maxPts) return pts
        val step = pts.size.toDouble() / maxPts
        val out = ArrayList<LatLng>(maxPts + 1)
        var i = 0.0
        while (i < pts.size) { out.add(pts[i.toInt()]); i += step }
        if (out.last() != pts.last()) out.add(pts.last())
        return out
    }

    private fun send(activity: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_text))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(activity.contentResolver, file.name, uri)
            }
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_chooser)))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
