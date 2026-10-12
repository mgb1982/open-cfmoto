// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * 📷 In-app viewer for the parking photo (v2.2). It used to open in an external gallery app; now it
 * stays here: full screen, pinch or double-tap to zoom (to read a plaza number), drag to pan, tap ✕
 * or Back to close. Honours the EXIF rotation CameraX writes.
 */
class ParkingPhotoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = Parking.photoFile(this)
        val bmp = if (f.exists()) decode(f) else null
        if (bmp == null) {
            Toast.makeText(this, R.string.park_photo_failed, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ZoomImageView(this, bmp), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 26f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        root.addView(TextView(this).apply {
            text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified()))
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (36 * dp).toInt())
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
    }

    /** Downsample to roughly screen size (photos are ~3 MB / 12 MP) and apply the EXIF rotation. */
    private fun decode(f: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val raw = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        val deg = when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (raw == null || deg == 0f) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true)
    } catch (e: Throwable) {
        LogBus.log("[PARK] photo decode failed: $e")
        null
    }

    /** Minimal zoomable image: fit-center at rest, pinch / double-tap zoom up to 5×, drag to pan. */
    @SuppressLint("ViewConstructor")
    private class ZoomImageView(ctx: Context, private val bmp: Bitmap) : ImageView(ctx) {
        private val m = Matrix()
        private var baseScale = 1f
        private var zoom = 1f

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val next = (zoom * d.scaleFactor).coerceIn(1f, 5f)
                val f = next / zoom
                zoom = next
                m.postScale(f, f, d.focusX, d.focusY)
                clampAndApply()
                return true
            }
        })
        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom > 1f) { m.postTranslate(-dx, -dy); clampAndApply() }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1f) fitCenter() else {
                    zoom = 2.5f
                    m.postScale(2.5f, 2.5f, e.x, e.y)
                    clampAndApply()
                }
                return true
            }
        })

        init {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            fitCenter()
        }

        private fun fitCenter() {
            if (width == 0 || height == 0) return
            baseScale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            zoom = 1f
            m.reset()
            m.postScale(baseScale, baseScale)
            m.postTranslate((width - bmp.width * baseScale) / 2f, (height - bmp.height * baseScale) / 2f)
            imageMatrix = m
        }

        /** Keep the picture covering the view when zoomed, and centred where it's smaller than it. */
        private fun clampAndApply() {
            val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()).also { m.mapRect(it) }
            val dx = when {
                r.width() <= width -> (width - r.width()) / 2f - r.left
                r.left > 0 -> -r.left
                r.right < width -> width - r.right
                else -> 0f
            }
            val dy = when {
                r.height() <= height -> (height - r.height()) / 2f - r.top
                r.top > 0 -> -r.top
                r.bottom < height -> height - r.bottom
                else -> 0f
            }
            m.postTranslate(dx, dy)
            imageMatrix = m
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            gestures.onTouchEvent(e)
            return true
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ParkingPhotoActivity::class.java))
    }
}
