// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * RGBA [ImageReader] → JPEG stills for the X-Cape 1200 Yunmo path.
 * Only started when [Xcape1200StillsProfile] is the Setup override.
 *
 * Capture and send are decoupled: every display frame is compressed into a latest-wins
 * slot; the send loop ticks at a fixed cadence and takes whatever is newest. Blocking
 * capture at the send rate leaves gaps the dash reads as judder.
 *
 * Quality walks 60 → 20 so the wire stays near what this HU will accept (~130 KB/s).
 * Shrinking the canvas cuts the picture off; quality is the only safe lever.
 */
class YunmoJpegCapturer(
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val log: (String) -> Unit,
) {
    private val periodMs = (1000L / fps.coerceIn(4, 15).toLong()).coerceAtLeast(1L)
    private val running = AtomicBoolean(false)
    private val latest = AtomicReference<ByteArray?>(null)
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile var surface: Surface? = null
        private set
    @Volatile var framesOut: Int = 0
        private set
    @Volatile var quality: Int = QUALITY_LADDER[0]
        private set

    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private var croppedCanvas: Canvas? = null
    private val jpegBuffer = ByteArrayOutputStream(128 * 1024)
    private val cropSrc = Rect()
    private val cropDst = Rect()
    private var compressedAt = 0L
    private var qualityStep = 0
    private var goodWindows = 0
    private var adaptAt = 0L
    private var adaptTicks = 0
    private var adaptAccepted = 0
    private val captured = AtomicInteger(0)
    private var sent = 0
    private var refused = 0
    private var bytesSent = 0L
    private var statsAt = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val w = width.and(1.inv()).coerceAtLeast(16)
        val h = height.and(1.inv()).coerceAtLeast(16)
        val ht = HandlerThread("yunmo-jpeg").also { it.start() }
        thread = ht
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        ir.setOnImageAvailableListener({ onImage(it) }, Handler(ht.looper))
        reader = ir
        surface = ir.surface
        val now = SystemClock.elapsedRealtime()
        adaptAt = now
        statsAt = now
        log("[YUNMO] JPEG stills capture ${w}x$h every ${periodMs}ms q=$quality (adaptive)")
    }

    /** Newest compressed still, or null if none yet. Does not consume. */
    fun latest(): ByteArray? = latest.get()

    /** Tell the quality ladder whether the send tick took the still. */
    fun noteOffer(accepted: Boolean, bytes: Int) {
        adaptTicks++
        if (accepted) {
            adaptAccepted++
            sent++
            bytesSent += bytes.toLong()
            framesOut++
        } else {
            refused++
        }
        adaptQuality()
        reportThroughput()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        surface = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
        latest.set(null)
        padded?.recycle(); padded = null
        cropped?.recycle(); cropped = null
        croppedCanvas = null
        log("[YUNMO] JPEG capture stopped after $framesOut frames")
    }

    private fun onImage(ir: ImageReader) {
        if (!running.get()) {
            try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - compressedAt < periodMs) {
            try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        val image = try {
            ir.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        compressedAt = now
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val paddedW = (plane.rowStride / pixelStride).coerceAtLeast(width)
            val source = paddedBitmap(paddedW).also { it.copyPixelsFromBuffer(plane.buffer) }
            val frame = if (paddedW == width) source else croppedBitmap().also { dest ->
                cropSrc.set(0, 0, width, height)
                cropDst.set(0, 0, width, height)
                croppedCanvas(dest).drawBitmap(source, cropSrc, cropDst, null)
            }
            jpegBuffer.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
            latest.set(jpegBuffer.toByteArray())
            captured.incrementAndGet()
        } catch (e: Exception) {
            log("[YUNMO] JPEG compress failed: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun adaptQuality() {
        val now = SystemClock.elapsedRealtime()
        if (now - adaptAt < ADAPT_INTERVAL_MS || adaptTicks == 0) return
        val acceptance = adaptAccepted.toDouble() / adaptTicks
        val prev = qualityStep
        if (acceptance < STARVED_BELOW && qualityStep < QUALITY_LADDER.lastIndex) {
            qualityStep++
            goodWindows = 0
        } else if (acceptance > COMFORTABLE_ABOVE && qualityStep > 0) {
            if (++goodWindows >= WINDOWS_BEFORE_CLIMB) {
                qualityStep--
                goodWindows = 0
            }
        } else {
            goodWindows = 0
        }
        if (qualityStep != prev) {
            quality = QUALITY_LADDER[qualityStep]
            log(
                "[YUNMO] still quality now $quality " +
                    "(dash took ${(acceptance * 100).toInt()}% of offered frames)",
            )
        }
        adaptAt = now
        adaptTicks = 0
        adaptAccepted = 0
    }

    private fun reportThroughput() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsAt
        if (elapsed < STATS_INTERVAL_MS) return
        val seconds = elapsed / 1000.0
        val avgKb = if (sent > 0) bytesSent / sent / 1024.0 else 0.0
        log(
            "[YUNMO] stills %.1f fps phone, %.1f fps wire, %.0f KB avg, %.0f KB/s q=$quality ($refused held)"
                .format(
                    captured.getAndSet(0) / seconds,
                    sent / seconds,
                    avgKb,
                    bytesSent / 1024.0 / seconds,
                ),
        )
        sent = 0
        refused = 0
        bytesSent = 0
        statsAt = now
    }

    private fun paddedBitmap(paddedW: Int): Bitmap {
        val existing = padded
        if (existing != null && !existing.isRecycled &&
            existing.width == paddedW && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(paddedW, height, Bitmap.Config.ARGB_8888).also { padded = it }
    }

    private fun croppedBitmap(): Bitmap {
        val existing = cropped
        if (existing != null && !existing.isRecycled) return existing
        croppedCanvas = null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cropped = it }
    }

    private fun croppedCanvas(dest: Bitmap): Canvas =
        croppedCanvas ?: Canvas(dest).also { croppedCanvas = it }

    companion object {
        val QUALITY_LADDER = intArrayOf(60, 50, 40, 32, 25, 20)
        const val ADAPT_INTERVAL_MS = 2_000L
        const val STARVED_BELOW = 0.5
        const val COMFORTABLE_ABOVE = 0.9
        const val WINDOWS_BEFORE_CLIMB = 3
        const val STATS_INTERVAL_MS = 5_000L
    }
}
