// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** v2.3 · The look of a trip video: overlay style + kind of map. */
enum class VideoStyle(val labelRes: Int) {
    CLASSIC(R.string.tvs_classic), SPORT(R.string.tvs_sport), MINIMAL(R.string.tvs_minimal),
    CINEMA(R.string.tvs_cinema), RETRO(R.string.tvs_retro), TELEMETRY(R.string.tvs_telemetry),
}

fun nightStyle(s: VideoStyle) = s == VideoStyle.SPORT || s == VideoStyle.TELEMETRY || s == VideoStyle.RETRO || s == VideoStyle.CINEMA

enum class VideoMap(val labelRes: Int) { LINE(R.string.tvm_line), SPEED(R.string.tvm_speed), STREETS(R.string.tvm_streets) }

/**
 * Real streets for the mini-map, rendered by the app's own map engine (MapLibre + OpenFreeMap, the
 * same as the dash map) into 1024 px blocks along the route, once, and cached on disk. Pixel space
 * is classic Web Mercator with 256 px tiles at [zoom] (MapLibre zoom = zoom − 1 for 512 px tiles).
 */
class StreetTiles private constructor(private val dir: File, val zoom: Int) {
    private val cache = object : LruCache<String, Bitmap>(8) {}

    fun worldPx(lat: Double, lon: Double): PointF {
        val n = 256.0 * (1 shl zoom)
        val x = (lon + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
        return PointF(x.toFloat(), y.toFloat())
    }

    fun metersPerPx(lat: Double) = 156_543.03 * cos(Math.toRadians(lat)) / (1 shl zoom)

    fun block(bx: Int, by: Int): Bitmap? {
        val key = "$bx/$by"
        cache.get(key)?.let { return it }
        val f = File(dir, "${bx}_$by.png")
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.also { cache.put(key, it) }
    }

    companion object {
        const val WINDOW_M = 260.0            // map shown = this many metres around you (radius)
        const val BLOCK = 1024
        private const val MAX_BLOCKS = 60

        private fun latLonOf(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
            val n = 256.0 * (1 shl zoom)
            val lon = x / n * 360.0 - 180.0
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y / n))))
            return lat to lon
        }

        /** Render what the route needs (blocking; call off the main thread). Null if it can't. */
        fun prepare(ctx: Context, trip: Trip, night: Boolean, progress: (Int, Int) -> Unit): StreetTiles? {
            val style = if (night) MapLibreDashController.STYLE_NIGHT else MapLibreDashController.STYLE_DAY
            File(ctx.cacheDir, "osm-tiles").deleteRecursively()   // the old raster cache (got "access blocked" tiles)
            for (z in intArrayOf(16, 15, 14)) {
                val dir = File(ctx.cacheDir, "video-streets/${if (night) "n" else "d"}$z").apply { mkdirs() }
                val t = StreetTiles(dir, z)
                val need = LinkedHashSet<Long>()
                val step = max(1, trip.points.size / 600)
                for (i in trip.points.indices step step) {
                    val p = trip.points[i]
                    val c = t.worldPx(p.lat, p.lon)
                    val r = (WINDOW_M * 1.5 / t.metersPerPx(p.lat)).toFloat()
                    for (bx in floor((c.x - r) / BLOCK).toInt()..floor((c.x + r) / BLOCK).toInt())
                        for (by in floor((c.y - r) / BLOCK).toInt()..floor((c.y + r) / BLOCK).toInt())
                            need += (bx.toLong() shl 32) or (by.toLong() and 0xffffffffL)
                }
                if (need.size > MAX_BLOCKS) continue
                var done = 0; var failed = 0
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                for (k in need) {
                    val bx = (k shr 32).toInt(); val by = k.toInt()
                    val f = File(dir, "${bx}_$by.png")
                    if (!f.exists()) {
                        val (lat, lon) = latLonOf((bx + 0.5) * BLOCK, (by + 0.5) * BLOCK, z)
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var bmp: Bitmap? = null
                        var snap: org.maplibre.android.snapshotter.MapSnapshotter? = null
                        main.post {
                            try {
                                snap = org.maplibre.android.snapshotter.MapSnapshotter(ctx.applicationContext,
                                    org.maplibre.android.snapshotter.MapSnapshotter.Options(BLOCK, BLOCK)
                                        .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromUri(style))
                                        .withCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
                                            .target(org.maplibre.android.geometry.LatLng(lat, lon)).zoom(z - 1.0).build())
                                        .withPixelRatio(1f)
                                        .withLogo(false))
                                snap?.start({ s -> bmp = s.bitmap.copy(Bitmap.Config.ARGB_8888, false); latch.countDown() },
                                    { e -> LogBus.log("[VIDEO] street block failed: $e"); latch.countDown() })
                            } catch (e: Exception) { LogBus.log("[VIDEO] snapshotter: $e"); latch.countDown() }
                        }
                        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
                        main.post { try { snap?.cancel() } catch (_: Exception) {} }
                        val b = bmp
                        if (b != null) { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
                        else if (++failed > 3 && failed > need.size / 3) { LogBus.log("[VIDEO] street map unavailable"); return null }
                    }
                    progress(++done, need.size)
                }
                LogBus.log("[VIDEO] street map z$z: ${need.size} blocks ($failed failed)")
                return t
            }
            return null
        }
    }
}

/**
 * Draws one look on a canvas: the per-frame overlay ([drawFrame]), the opening card ([drawIntro])
 * and the closing card ([drawOutro]). Shared by the video export and the in-app preview.
 */
class TripLook(
    private val ctx: Context,
    private val trip: Trip,
    private val title: String,
    val style: VideoStyle,
    val map: VideoMap,
    private val streets: StreetTiles?,
) {
    private val pts = trip.points
    private val minLat = pts.minOf { it.lat }; private val maxLat = pts.maxOf { it.lat }
    private val minLon = pts.minOf { it.lon }; private val maxLon = pts.maxOf { it.lon }
    private val cosLat = cos(Math.toRadians((minLat + maxLat) / 2))
    private val cumKm: DoubleArray = DoubleArray(pts.size).also { a ->
        for (i in 1 until pts.size) a[i] = a[i - 1] + metres(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon) / 1000.0
    }
    private val totalKm = max(cumKm.lastOrNull() ?: 0.0, trip.distanceKm)
    private val maxKmh = max(20f, pts.maxOf { it.speedMs } * 3.6f)
    private val speedSeries: FloatArray = run {
        val n = 160
        FloatArray(n) { i -> pts[(i.toLong() * (pts.size - 1) / (n - 1)).toInt()].speedMs * 3.6f }
    }

    private val amber = Color.rgb(255, 176, 32)
    private val gold = Color.rgb(214, 180, 110)
    private val sky = Color.rgb(110, 190, 255)
    private val yellow = Color.rgb(255, 193, 7)
    private val red = Color.rgb(229, 57, 53)

    private val tfCondItalic = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val tfCond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    // Weight 300 from the default sans: some phones resolve "sans-serif-light" to the very same
    // Typeface object as monospace bold, which sent Minimal/Cinema text through the pixel font.
    private val tfLight = Typeface.create(Typeface.SANS_SERIF, 300, false)
    private val tfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tfSerifItalic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val tfMono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    /** Dot-matrix letters: only the Retro look, never by font identity alone. */
    private fun pixel(tf: Typeface) = style == VideoStyle.RETRO && tf === tfMono

    /** Width as [text] will draw it (pixel font included). */
    private fun measure(s: String, tf: Typeface, size: Float): Float {
        if (pixel(tf)) return PixelFont.width(s, size)
        p.reset(); p.isAntiAlias = true; p.typeface = tf; p.textSize = size
        return p.measureText(s)
    }
    private val tfArchivo = try { ResourcesCompat.getFont(ctx, R.font.archivo_semibold) } catch (_: Exception) { null } ?: Typeface.DEFAULT_BOLD
    private val tfArchivoBlack = try { ResourcesCompat.getFont(ctx, R.font.archivo_black) } catch (_: Exception) { null } ?: Typeface.DEFAULT_BOLD

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Dark map tiles for the dark looks. */
    fun night() = nightStyle(style)

    // ── where we are

    private fun indexAt(tMs: Long): Int {
        var i = pts.binarySearchBy(tMs) { it.time }
        if (i < 0) i = (-i - 1).coerceIn(0, pts.lastIndex)
        return i
    }

    private fun bearing(i: Int): Float {
        val a = pts[(i - 4).coerceAtLeast(0)]; val b = pts[(i + 4).coerceAtMost(pts.lastIndex)]
        if (metres(a.lat, a.lon, b.lat, b.lon) < 8) return lastBearing
        val y = (b.lon - a.lon) * cosLat; val x = b.lat - a.lat
        lastBearing = Math.toDegrees(atan2(y, x)).toFloat()
        return lastBearing
    }
    private var lastBearing = 0f

    // ── text helpers

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, tf: Typeface, color: Int = Color.WHITE,
                      align: Paint.Align = Paint.Align.LEFT, shadow: Boolean = true) {
        if (pixel(tf)) { PixelFont.draw(c, s, x, y, size, color, align, shadow); return }
        p.reset(); p.isAntiAlias = true
        p.typeface = tf; p.textSize = size; p.color = color; p.textAlign = align
        if (shadow) p.setShadowLayer(size * 0.12f, 0f, size * 0.04f, Color.argb(170, 0, 0, 0))
        c.drawText(s, x, y, p)
    }

    private fun fill(color: Int) = p.apply { reset(); isAntiAlias = true; this.color = color; style = Paint.Style.FILL }

    private fun km(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ── map

    /** Route fitted north-up into [box] (line or speed-coloured); the ridden part in [done]. */
    private fun routeFit(c: Canvas, box: RectF, color: Int, done: Int?, width: Float, idx: Int, dotR: Float) {
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-4)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-4) * cosLat
        val s = min(box.width() / spanLon, box.height() / spanLat).toFloat()
        fun x(lon: Double) = box.centerX() + ((lon - (minLon + maxLon) / 2) * cosLat * s).toFloat()
        fun y(lat: Double) = box.centerY() - ((lat - (minLat + maxLat) / 2) * s).toFloat()
        val step = max(1, pts.size / 500)
        stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND; stroke.strokeWidth = width
        if (map == VideoMap.SPEED) {
            var i = 0
            while (i + step < pts.size) {
                val a = pts[i]; val b = pts[i + step]
                stroke.color = speedColor(b.speedMs * 3.6f, if (i <= idx) 255 else 110)
                c.drawLine(x(a.lon), y(a.lat), x(b.lon), y(b.lat), stroke)
                i += step
            }
        } else {
            val all = Path(); val ridden = Path()
            for (i in pts.indices step step) {
                val q = pts[i]
                if (i == 0) { all.moveTo(x(q.lon), y(q.lat)); ridden.moveTo(x(q.lon), y(q.lat)) }
                else { all.lineTo(x(q.lon), y(q.lat)); if (i <= idx) ridden.lineTo(x(q.lon), y(q.lat)) }
            }
            stroke.color = color; c.drawPath(all, stroke)
            if (done != null) { stroke.color = done; c.drawPath(ridden, stroke) }
        }
        if (idx >= 0 && dotR > 0f) dot(c, x(pts[idx].lon), y(pts[idx].lat), dotR)
    }

    private fun dot(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r * 1.35f, fill(Color.WHITE))
        c.drawCircle(x, y, r, fill(red))
    }

    private fun speedColor(kmh: Float, alpha: Int): Int {
        val t = (kmh / maxKmh).coerceIn(0f, 1f)
        val hsv = floatArrayOf(220f - 220f * t, 0.85f, 1f)
        return Color.HSVToColor(alpha, hsv)
    }

    /** Real streets around the current position, heading up, clipped to [box] (circle or rounded). */
    private fun streetsAround(c: Canvas, box: RectF, circle: Boolean, idx: Int, routeColor: Int, dotR: Float) {
        val st = streets ?: return
        val here = pts[idx]
        val centre = st.worldPx(here.lat, here.lon)
        val radiusPx = (StreetTiles.WINDOW_M / st.metersPerPx(here.lat)).toFloat()
        val scale = (min(box.width(), box.height()) / 2f) / radiusPx
        c.save()
        val clip = Path().apply {
            if (circle) addCircle(box.centerX(), box.centerY(), min(box.width(), box.height()) / 2f, Path.Direction.CW)
            else addRoundRect(box, box.width() * 0.08f, box.width() * 0.08f, Path.Direction.CW)
        }
        c.clipPath(clip)
        c.drawColor(if (night()) Color.rgb(30, 34, 38) else Color.rgb(230, 228, 222))
        c.translate(box.centerX(), box.centerY())
        c.rotate(-bearing(idx))
        c.scale(scale, scale)
        c.translate(-centre.x, -centre.y)
        val reach = radiusPx * 1.5f
        val bp = Paint(Paint.FILTER_BITMAP_FLAG)
        val B = StreetTiles.BLOCK
        for (bx in floor((centre.x - reach) / B).toInt()..floor((centre.x + reach) / B).toInt())
            for (by in floor((centre.y - reach) / B).toInt()..floor((centre.y + reach) / B).toInt()) {
                st.block(bx, by)?.let { c.drawBitmap(it, bx * B.toFloat(), by * B.toFloat(), bp) }
            }
        // The route on top of the streets.
        val path = Path()
        val step = max(1, pts.size / 800)
        for (i in pts.indices step step) {
            val w = st.worldPx(pts[i].lat, pts[i].lon)
            if (i == 0) path.moveTo(w.x, w.y) else path.lineTo(w.x, w.y)
        }
        stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeWidth = dotR * 0.9f / scale; stroke.color = routeColor
        c.drawPath(path, stroke)
        c.restore()
        // Slight shade so white text around stays readable, then "you are here" and attribution.
        dot(c, box.centerX(), box.centerY(), dotR)
        text(c, "© OpenStreetMap", box.right - box.width() * 0.06f, box.bottom - box.width() * 0.05f, box.width() * 0.055f,
            Typeface.DEFAULT, if (night()) Color.argb(200, 220, 220, 220) else Color.argb(200, 40, 40, 40), Paint.Align.RIGHT, shadow = false)
        if (circle) {
            stroke.strokeWidth = box.width() * 0.025f; stroke.color = Color.WHITE
            c.drawCircle(box.centerX(), box.centerY(), min(box.width(), box.height()) / 2f, stroke)
        }
    }

    /** The map in this style's slot: streets if chosen and available, else the fitted route. */
    private fun minimap(c: Canvas, box: RectF, circle: Boolean, panel: Int?, border: Int?, line: Int, done: Int?, idx: Int, u: Float) {
        if (map == VideoMap.STREETS && streets != null) {
            streetsAround(c, box, circle, idx, done ?: line, 1.5f * u)
            if (border != null) {
                stroke.strokeWidth = 0.6f * u; stroke.color = border
                if (circle) c.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, stroke)
                else c.drawRoundRect(box, box.width() * 0.08f, box.width() * 0.08f, stroke)
            }
            return
        }
        if (panel != null) {
            if (circle) c.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, fill(panel))
            else c.drawRoundRect(box, 2 * u, 2 * u, fill(panel))
        }
        if (border != null) {
            stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 0.6f * u; stroke.color = border
            if (circle) c.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, stroke)
            else c.drawRoundRect(box, 2 * u, 2 * u, stroke)
        }
        val inset = box.width() * (if (circle) 0.2f else 0.1f)
        routeFit(c, RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset), line, done, 0.7f * u, idx, 1.3f * u)
    }

    // ── per-frame overlay

    fun drawFrame(c: Canvas, w: Int, h: Int, tMs: Long) {
        val u = min(w, h) / 100f
        val idx = indexAt(tMs)
        val pt = pts[idx]
        val kmh = (pt.speedMs * 3.6f).roundToInt().coerceAtLeast(0)
        val clock = hhmm.format(Date(tMs))
        val kmDone = cumKm[idx]
        when (style) {
            VideoStyle.CLASSIC -> {
                text(c, title, 3 * u, 7 * u, fitSize(title, Typeface.DEFAULT_BOLD, w - 40 * u, 4.2f * u), Typeface.DEFAULT_BOLD)
                text(c, clock, 3 * u, 12 * u, 3.4f * u, Typeface.DEFAULT)
                text(c, "$kmh", 3 * u, h - 6 * u, 12 * u, Typeface.DEFAULT_BOLD)
                text(c, "km/h", 4.5f * u + measure("$kmh", Typeface.DEFAULT_BOLD, 12 * u), h - 6 * u, 4 * u, Typeface.DEFAULT)
                val s = 30 * u
                minimap(c, RectF(w - s - 3 * u, 3 * u, w - 3 * u, 3 * u + s), false, Color.argb(110, 0, 0, 0), null, yellow, null, idx, u)
            }
            VideoStyle.SPORT -> {
                // Title chip + time chip
                // Room for the chip: everything left of the round map (26u + margins).
                val ts = fitSize(title.uppercase(), tfCondItalic, w - 3 * u - 26 * u - 3 * u - 4 * u - 5 * u, 4.6f * u)
                p.typeface = tfCondItalic; p.textSize = ts
                val tw = p.measureText(title.uppercase())
                c.drawRoundRect(RectF(3 * u, 3 * u, 3 * u + tw + 5 * u, 10.5f * u), 1.8f * u, 1.8f * u, fill(Color.argb(140, 0, 0, 0)))
                c.drawPath(Path().apply { moveTo(3 * u, 3 * u); lineTo(4.4f * u, 3 * u); lineTo(3 * u, 10.5f * u); close() }, fill(amber))
                text(c, title.uppercase(), 5.5f * u, 6.75f * u + ts * 0.36f, ts, tfCondItalic, shadow = false)
                c.drawRoundRect(RectF(3 * u, 11.5f * u, 16 * u, 16 * u), 1.4f * u, 1.4f * u, fill(amber))
                text(c, clock, 9.5f * u, 15.1f * u, 3.3f * u, tfCond, Color.rgb(20, 20, 20), Paint.Align.CENTER, false)
                // Gauge
                val cx = 18 * u; val cy = h - 19 * u; val r = 13 * u
                c.drawCircle(cx, cy, r + 2.2f * u, fill(Color.argb(120, 0, 0, 0)))
                stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 1.6f * u
                stroke.strokeCap = Paint.Cap.ROUND
                val arc = RectF(cx - r, cy - r, cx + r, cy + r)
                stroke.color = Color.argb(70, 255, 255, 255); c.drawArc(arc, 135f, 270f, false, stroke)
                stroke.color = amber; c.drawArc(arc, 135f, 270f * (kmh / 120f).coerceIn(0f, 1f), false, stroke)
                text(c, "$kmh", cx, cy + 3 * u, 11 * u, tfCondItalic, align = Paint.Align.CENTER)
                text(c, "KM/H", cx, cy + 8.5f * u, 3 * u, tfCond, amber, Paint.Align.CENTER)
                // Distance bar
                val bx0 = 38 * u; val bx1 = w - 4 * u; val by = h - 7 * u
                c.drawRoundRect(RectF(bx0 - 1.5f * u, by - 6 * u, bx1 + 1.5f * u, by + 3.5f * u), 2 * u, 2 * u, fill(Color.argb(130, 0, 0, 0)))
                text(c, "${km(kmDone)} KM", bx0, by - 1.8f * u, 3 * u, tfCond, shadow = false)
                text(c, "${km(totalKm)} KM", bx1, by - 1.8f * u, 3 * u, tfCond, Color.argb(190, 255, 255, 255), Paint.Align.RIGHT, false)
                c.drawRoundRect(RectF(bx0, by + 0.4f * u, bx1, by + 1.5f * u), u, u, fill(Color.argb(70, 255, 255, 255)))
                c.drawRoundRect(RectF(bx0, by + 0.4f * u, bx0 + (bx1 - bx0) * (kmDone / totalKm).toFloat().coerceIn(0f, 1f), by + 1.5f * u), u, u, fill(amber))
                // Round mini-map with north
                val ms = 26 * u
                val box = RectF(w - ms - 3 * u, 3 * u, w - 3 * u, 3 * u + ms)
                minimap(c, box, true, Color.argb(190, 15, 18, 20), amber, Color.argb(130, 255, 255, 255), amber, idx, u)
                if (map != VideoMap.STREETS || streets == null) text(c, "N", box.centerX(), box.top + 3.4f * u, 2.6f * u, tfCond, amber, Paint.Align.CENTER, false)
            }
            VideoStyle.MINIMAL -> {
                val line = "$clock   ·   ${title.uppercase()}"
                text(c, line, 4 * u, 6.5f * u, fitSize(line, tfMedium, w - 8 * u, 2.6f * u), tfMedium)
                text(c, "$kmh", 4 * u, h - 4 * u, 16 * u, tfLight)
                text(c, "km/h", 5.5f * u + measure("$kmh", tfLight, 16 * u), h - 5 * u, 3.8f * u, tfLight)
                val s = 20 * u
                minimap(c, RectF(w - s - 3 * u, h - s - 3 * u, w - 3 * u, h - 3 * u), false, null,
                    if (map == VideoMap.STREETS) Color.WHITE else null, Color.WHITE, null, idx, u)
            }
            VideoStyle.CINEMA -> {
                val bar = 13 * u
                c.drawRect(0f, 0f, w.toFloat(), bar, fill(Color.BLACK))
                c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), fill(Color.BLACK))
                val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(tMs)).uppercase()
                val rightW = max(measure("$date · $clock", tfLight, 2.3f * u), measure("$kmh KM/H", tfMedium, 2.8f * u))
                text(c, title, 4.5f * u, h - bar / 2 + 1.8f * u, fitSize(title, tfSerifItalic, w - 9 * u - rightW - 4 * u, 5.2f * u),
                    tfSerifItalic, Color.rgb(240, 232, 214), shadow = false)
                text(c, "$date · $clock", w - 4.5f * u, h - bar / 2 - 1.2f * u, 2.3f * u, tfLight, Color.rgb(200, 190, 170), Paint.Align.RIGHT, false)
                text(c, "$kmh KM/H", w - 4.5f * u, h - bar / 2 + 3.2f * u, 2.8f * u, tfMedium, Color.rgb(240, 232, 214), Paint.Align.RIGHT, false)
                val s = bar - 3 * u
                minimap(c, RectF(w - s - 4.5f * u, 1.5f * u, w - 4.5f * u, 1.5f * u + s), false, null, null, gold, null, idx, u * 0.7f)
            }
            VideoStyle.RETRO -> {
                // Scanlines
                val sl = fill(Color.argb(40, 0, 0, 0))
                var y = 0f
                while (y < h) { c.drawRect(0f, y, w.toFloat(), y + 0.25f * u, sl); y += 0.55f * u }
                if ((tMs / 500) % 2 == 0L) c.drawCircle(6 * u, 7 * u, 1.7f * u, fill(Color.rgb(255, 40, 40)))
                text(c, "REC", 9 * u, 8.6f * u, 4.4f * u, tfMono)
                text(c, "SP ▶", w - 4 * u, 8.6f * u, 4.4f * u, tfMono, align = Paint.Align.RIGHT)
                val rt = deaccent(title.uppercase()).replace("→", ">")
                text(c, rt, 4.5f * u, 14 * u, fitSize(rt, tfMono, w - 4.5f * u - 18 * u - 8 * u, 3.2f * u), tfMono)
                text(c, String.format(Locale.US, "%03d KM/H", kmh), 4.5f * u, h - 7 * u, 6 * u, tfMono)
                val d = SimpleDateFormat("MMM.dd yyyy", Locale.ENGLISH).format(Date(tMs)).uppercase()
                text(c, d, w - 4.5f * u, h - 12 * u, 4.4f * u, tfMono, align = Paint.Align.RIGHT)
                text(c, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(tMs)), w - 4.5f * u, h - 6.5f * u, 4.4f * u, tfMono, align = Paint.Align.RIGHT)
                val s = 18 * u
                minimap(c, RectF(w - s - 4 * u, 12 * u, w - 4 * u, 12 * u + s), false, null, null, Color.WHITE, null, idx, u)
            }
            VideoStyle.TELEMETRY -> {
                text(c, title, 4 * u, 7 * u, fitSize(title, tfArchivo, w - 4 * u - 24 * u - 6 * u, 4 * u), tfArchivo)
                val sub = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tMs)) + " · " + clock
                text(c, sub, 4 * u, 11.5f * u, fitSize(sub, tfArchivo, w - 4 * u - 24 * u - 6 * u, 2.6f * u), tfArchivo,
                    Color.argb(220, 255, 255, 255))
                // Glass panel
                val panel = RectF(3 * u, h - 22 * u, w - 3 * u, h - 3 * u)
                c.drawRoundRect(panel, 2.5f * u, 2.5f * u, fill(Color.argb(175, 12, 16, 20)))
                stroke.reset(); stroke.isAntiAlias = true; stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 0.15f * u
                stroke.color = Color.argb(60, 255, 255, 255); c.drawRoundRect(panel, 2.5f * u, 2.5f * u, stroke)
                text(c, ctx.getString(R.string.tvl_speed), panel.left + 3 * u, panel.top + 4.5f * u, 2 * u, tfArchivo, sky, shadow = false)
                text(c, "$kmh", panel.left + 3 * u, panel.top + 13.5f * u, 10 * u, tfArchivoBlack, shadow = false)
                text(c, "km/h", panel.left + 3 * u, panel.top + 17.5f * u, 2.4f * u, tfArchivo, Color.argb(210, 220, 230, 240), shadow = false)
                text(c, ctx.getString(R.string.tvl_distance), panel.right - 3 * u, panel.top + 4.5f * u, 2 * u, tfArchivo, sky, Paint.Align.RIGHT, false)
                text(c, km(kmDone), panel.right - 3 * u, panel.top + 12 * u, 6.5f * u, tfArchivoBlack, align = Paint.Align.RIGHT, shadow = false)
                text(c, ctx.getString(R.string.tvl_of_km, km(totalKm)), panel.right - 3 * u, panel.top + 16.5f * u, 2.2f * u, tfArchivo,
                    Color.argb(210, 220, 230, 240), Paint.Align.RIGHT, false)
                // Speed over the whole trip, filled up to now
                val gx0 = panel.left + 22 * u; val gx1 = panel.right - 21 * u
                val gy0 = panel.top + 3 * u; val gy1 = panel.bottom - 3 * u
                val n = speedSeries.size
                val frac = (idx.toFloat() / pts.lastIndex).coerceIn(0f, 1f)
                val cur = (frac * (n - 1)).roundToInt()
                fun gx(i: Int) = gx0 + (gx1 - gx0) * i / (n - 1)
                fun gy(v: Float) = gy1 - (gy1 - gy0) * (v / maxKmh).coerceIn(0f, 1f)
                val area = Path().apply { moveTo(gx0, gy1); for (i in 0..cur) lineTo(gx(i), gy(speedSeries[i])); lineTo(gx(cur), gy1); close() }
                c.drawPath(area, fill(Color.argb(70, 80, 170, 255)))
                val all = Path(); val done = Path()
                for (i in 0 until n) { if (i == 0) { all.moveTo(gx(i), gy(speedSeries[i])); done.moveTo(gx(i), gy(speedSeries[i])) } else { all.lineTo(gx(i), gy(speedSeries[i])); if (i <= cur) done.lineTo(gx(i), gy(speedSeries[i])) } }
                stroke.strokeWidth = 0.35f * u; stroke.color = Color.argb(90, 255, 255, 255); c.drawPath(all, stroke)
                stroke.strokeWidth = 0.5f * u; stroke.color = sky; c.drawPath(done, stroke)
                stroke.strokeWidth = 0.15f * u; stroke.color = Color.argb(140, 255, 255, 255); c.drawLine(gx(cur), gy0, gx(cur), gy1, stroke)
                c.drawCircle(gx(cur), gy(speedSeries[cur]), 0.9f * u, fill(Color.WHITE))
                // Mini-map, glass square
                val s = 24 * u
                minimap(c, RectF(w - s - 3 * u, 3 * u, w - 3 * u, 3 * u + s), false, Color.argb(175, 12, 16, 20), Color.argb(60, 255, 255, 255),
                    Color.argb(110, 255, 255, 255), sky, idx, u)
            }
        }
    }

    // ── opening and closing cards

    private fun cardBackground(c: Canvas, w: Int, h: Int, frame: Bitmap?) {
        if (frame != null) {
            val small = Bitmap.createScaledBitmap(frame, max(1, frame.width / 24), max(1, frame.height / 24), true)
            c.drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(Color.argb(150, 8, 10, 12))
        } else c.drawColor(Color.rgb(20, 24, 28))
        p.reset(); p.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(180, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
    }

    private fun accent() = when (style) {
        VideoStyle.SPORT -> amber; VideoStyle.CINEMA -> gold; VideoStyle.TELEMETRY -> sky
        VideoStyle.CLASSIC -> yellow; else -> Color.WHITE
    }

    private fun headline() = when (style) {
        VideoStyle.SPORT -> tfCondItalic; VideoStyle.MINIMAL -> tfLight; VideoStyle.CINEMA -> tfSerifItalic
        VideoStyle.RETRO -> tfMono; VideoStyle.TELEMETRY -> tfArchivoBlack; else -> Typeface.DEFAULT_BOLD
    }

    fun drawIntro(c: Canvas, w: Int, h: Int, frame: Bitmap?) {
        cardBackground(c, w, h, frame)
        val u = min(w, h) / 100f
        val t = if (style == VideoStyle.RETRO) deaccent(title.uppercase()).replace("→", ">") else if (style == VideoStyle.SPORT) title.uppercase() else title
        val size = fitSize(t, headline(), w - 12 * u, 8 * u)
        text(c, t, w / 2f, h * 0.46f, size, headline(), align = Paint.Align.CENTER)
        val date = DateFormat.getDateInstance(DateFormat.FULL).format(Date(trip.start))
        text(c, date.replaceFirstChar { it.uppercase() }, w / 2f, h * 0.46f + 6 * u, 3.2f * u, tfLight, accent(), Paint.Align.CENTER)
        val mins = ((trip.end - trip.start) / 60_000L).toInt()
        text(c, "${km(totalKm)} km  ·  $mins min", w / 2f, h * 0.46f + 11 * u, 3 * u, tfMedium, Color.argb(220, 255, 255, 255), Paint.Align.CENTER)
        val s = 26 * u
        routeFit(c, RectF(w / 2f - s / 2, h * 0.62f, w / 2f + s / 2, h * 0.62f + s), accent(), null, 0.8f * u, pts.lastIndex, 0f)
    }

    fun drawOutro(c: Canvas, w: Int, h: Int, frame: Bitmap?, watermark: Boolean) {
        cardBackground(c, w, h, frame)
        val u = min(w, h) / 100f
        val portrait = h > w
        val mapBox = if (portrait) RectF(8 * u, h * 0.12f, w - 8 * u, h * 0.12f + (w - 16 * u)) else RectF(6 * u, 8 * u, w * 0.55f, h - 8 * u)
        routeFit(c, mapBox, accent(), null, 1.1f * u, pts.lastIndex, 1.6f * u)
        val mins = ((trip.end - trip.start) / 60_000L).toInt()
        val avg = if (trip.movingTimeMs > 0) (trip.distanceMeters / (trip.movingTimeMs / 1000.0) * 3.6).roundToInt() else 0
        val rows = listOf(
            ctx.getString(R.string.tvl_distance) to "${km(totalKm)} km",
            ctx.getString(R.string.tvl_time) to "$mins min",
            ctx.getString(R.string.tvl_avg) to "$avg km/h",
            ctx.getString(R.string.tvl_max) to "${trip.maxKmh} km/h",
        )
        val x0 = if (portrait) 10 * u else w * 0.62f
        var y = if (portrait) mapBox.bottom + 9 * u else h * 0.3f
        text(c, title, x0, y, fitSize(title, headline(), w - x0 - 6 * u, 5.5f * u), headline())
        y += 8 * u
        for ((k, v) in rows) {
            text(c, k, x0, y, 2.4f * u, tfMedium, accent())
            text(c, v, x0, y + 5.2f * u, 4.6f * u, headline())
            y += 10 * u
        }
        if (watermark) brand(c, w, h, u, portrait, x0)
    }

    /**
     * v2.3 · "Made with" + the app icon + RideScreen AA, big enough to read on a phone (it used to be
     * one small line), and the project's address under it once [BRAND_LINK] is set.
     */
    private fun brand(c: Canvas, w: Int, h: Int, u: Float, portrait: Boolean, x0: Float) {
        val icon = 9 * u
        val nameSize = 4.6f * u
        val name = "RideScreen AA"
        p.reset(); p.isAntiAlias = true; p.typeface = tfArchivoBlack; p.textSize = nameSize
        val nameW = p.measureText(name)
        val blockW = icon + 2.2f * u + nameW
        val left = if (portrait) (w - blockW) / 2f else x0
        val linkH = if (BRAND_LINK.isNotEmpty()) 4 * u else 0f
        val top = h - 5 * u - linkH - icon
        try {
            androidx.core.content.res.ResourcesCompat.getDrawable(ctx.resources, R.mipmap.ic_launcher_round, null)?.let { d ->
                d.setBounds(left.toInt(), top.toInt(), (left + icon).toInt(), (top + icon).toInt()); d.draw(c)
            }
        } catch (_: Exception) {}
        val tx = left + icon + 2.2f * u
        text(c, ctx.getString(R.string.tvl_made_with), tx, top + 3.2f * u, 2.3f * u, tfMedium, Color.argb(200, 255, 255, 255))
        text(c, name, tx, top + icon - 0.6f * u, nameSize, tfArchivoBlack)
        if (BRAND_LINK.isNotEmpty()) {
            text(c, BRAND_LINK, if (portrait) w / 2f else left, h - 4.5f * u, 2.6f * u, tfMedium, accent(),
                if (portrait) Paint.Align.CENTER else Paint.Align.LEFT)
        }
    }

    private fun fitSize(s: String, tf: Typeface, maxW: Float, start: Float): Float {
        if (pixel(tf)) { var sz = start; while (sz > 8f && PixelFont.width(s, sz) > maxW) sz *= 0.92f; return sz }
        p.reset(); p.typeface = tf; var size = start
        while (size > 8f) { p.textSize = size; if (p.measureText(s) <= maxW) break; size *= 0.92f }
        return size
    }

    private fun deaccent(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    companion object {
        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            val y = Math.toRadians(lat2 - lat1)
            return sqrt(x * x + y * y) * 6_371_000.0
        }
    }
}

/** 5×7 dot-matrix letters for the Retro VHS look (camcorder-style), drawn as little squares. */
/** Shown under the logo at the end of trip videos (web / Instagram); empty = nothing. */
const val BRAND_LINK = ""

object PixelFont {
    private val G: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###.."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        ':' to arrayOf(".....", "..#..", "..#..", ".....", "..#..", "..#..", "....."),
        '.' to arrayOf(".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
        ',' to arrayOf(".....", ".....", ".....", ".....", ".##..", "..#..", ".#..."),
        '>' to arrayOf(".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#..."),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '/' to arrayOf("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
        '▶' to arrayOf("#....", "##...", "###..", "####.", "###..", "##...", "#...."),
        '·' to arrayOf(".....", ".....", ".....", "..#..", ".....", ".....", "....."),
    )
    private val px = Paint().apply { style = Paint.Style.FILL }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.uppercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace("→", ">")

    /** [size] = letter height; returns the drawn width. */
    fun width(s: String, size: Float): Float { val d = size / 7f; return (norm(s).length * 6 - 1) * d }

    fun draw(c: Canvas, s0: String, x: Float, baseline: Float, size: Float, color: Int, align: Paint.Align, shadow: Boolean) {
        val s = norm(s0)
        val d = size / 7f
        val wTot = width(s, size)
        val x0 = when (align) { Paint.Align.CENTER -> x - wTot / 2; Paint.Align.RIGHT -> x - wTot; else -> x }
        val top = baseline - size
        fun pass(dx: Float, dy: Float, col: Int) {
            px.color = col
            var cx = x0 + dx
            for (ch in s) {
                val g = G[ch]
                if (g != null) for (r in 0 until 7) for (k in 0 until 5) if (g[r][k] == '#')
                    c.drawRect(cx + k * d, top + dy + r * d, cx + (k + 1) * d - d * 0.12f, top + dy + (r + 1) * d - d * 0.12f, px)
                cx += 6 * d
            }
        }
        if (shadow) pass(d * 0.6f, d * 0.6f, Color.argb(200, 0, 0, 0))
        pass(0f, 0f, color)
    }
}
