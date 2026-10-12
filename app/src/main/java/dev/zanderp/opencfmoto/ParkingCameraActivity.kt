// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * 📸 In-app camera for the parking photo (v2.2). The first version handed the shot to the phone's
 * camera app through a FileProvider uri; on Manel's Samsung that ended in "camera error" and no photo.
 * CameraX (already used for the QR scanner) writes straight to our own file: no other app, no uri
 * grants. Result OK = the photo is in [Parking.pendingPhotoFile].
 */
class ParkingCameraActivity : AppCompatActivity() {

    private var capture: ImageCapture? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(TextView(this).apply {
            setText(R.string.park_cam_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding((20 * dp).toInt(), (48 * dp).toInt(), (20 * dp).toInt(), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val shutter = TextView(this).apply {
            text = "📸"
            textSize = 34f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((5 * dp).toInt(), ContextCompat.getColor(this@ParkingCameraActivity, R.color.brand_gold))
            }
            setOnClickListener { shoot() }
        }
        root.addView(shutter, FrameLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (48 * dp).toInt()
        })
        root.addView(TextView(this).apply {
            setText(android.R.string.cancel)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = (64 * dp).toInt()
        })
        setContentView(root)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, ic)
                capture = ic
            } catch (e: Exception) {
                LogBus.log("[PARK] camera start failed: $e")
                Toast.makeText(this, R.string.park_photo_no_camera, Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED); finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot() {
        val ic = capture ?: return
        if (busy) return
        busy = true
        val f = Parking.pendingPhotoFile(this).apply { delete() }
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    LogBus.log("[PARK] photo captured (${f.length() / 1024} KB)")
                    setResult(RESULT_OK); finish()
                }
                override fun onError(e: ImageCaptureException) {
                    busy = false
                    LogBus.log("[PARK] capture failed: ${e.imageCaptureError} ${e.message}")
                    Toast.makeText(this@ParkingCameraActivity, R.string.park_photo_failed, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}
