// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RGBA [ImageReader] → JPEG stills for the X-Cape 1200 Yunmo path.
 * Only started when [Xcape1200StillsProfile] is the Setup override.
 */
class YunmoJpegCapturer(
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val log: (String) -> Unit,
) {
    private val minIntervalMs = 1000L / fps.coerceIn(4, 15).toLong()
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<ByteArray>(2)
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile var surface: Surface? = null
        private set
    @Volatile var framesOut: Int = 0
        private set
    private var lastCaptureAt = 0L
    private var scratch: Bitmap? = null

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
        log("[YUNMO] JPEG stills capture ${w}x$h @${1000 / minIntervalMs}fps q=60")
    }

    fun poll(timeoutMs: Long): ByteArray? =
        try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        surface = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null
        scratch?.recycle()
        scratch = null
        queue.clear()
        log("[YUNMO] JPEG capture stopped after $framesOut frames")
    }

    private fun onImage(ir: ImageReader) {
        if (!running.get()) {
            try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < minIntervalMs) {
            try { ir.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }
        val image = try {
            ir.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride
            val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
            val bmpW = width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(bmpW.coerceAtLeast(width), height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (bmpW != width) {
                Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
            } else {
                bmp
            }
            val out = ByteArrayOutputStream(cropped.byteCount / 8)
            cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
            scratch?.recycle()
            scratch = cropped
            val jpeg = out.toByteArray()
            lastCaptureAt = now
            framesOut++
            if (!queue.offer(jpeg)) {
                queue.poll()
                queue.offer(jpeg)
            }
        } catch (e: Exception) {
            log("[YUNMO] JPEG compress failed: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }
}
