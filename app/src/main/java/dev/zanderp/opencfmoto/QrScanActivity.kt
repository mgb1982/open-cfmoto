package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScanActivity : AppCompatActivity() {
    companion object {
        const val RESULT_QR = "qr_url"
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()
    private val handled = AtomicBoolean(false)
    private var camera: Camera? = null

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) decodePhoto(uri)
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        findViewById<Button>(R.id.btn_qr_photo).setOnClickListener {
            photoPicker.launch("image/*")
        }
        findViewById<Button>(R.id.btn_manual_wifi).setOnClickListener {
            ManualWifiPairing.show(this) { raw, _ ->
                if (!handled.compareAndSet(false, true)) return@show
                setResult(RESULT_OK, Intent().putExtra(RESULT_QR, raw))
                finish()
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        scanner.close()
        super.onDestroy()
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.preview_view)
        // Catch Throwable: on ROMs that fake their API level CameraX throws NoSuchMethodError (an Error).
        val providerFuture = try { ProcessCameraProvider.getInstance(this) } catch (e: Throwable) {
            cameraFailed(e); return
        }
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (e: Throwable) { cameraFailed(e); return@addListener }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
                setupZoomButtons()
            } catch (e: Throwable) {
                cameraFailed(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Camera unusable on this phone: say so and leave the "pick a photo of the QR" path available. */
    private fun cameraFailed(e: Throwable) {
        LogBus.log("[QR] camera start failed: $e")
        findViewById<TextView>(R.id.hint).text = getString(R.string.qr_camera_failed)
    }

    private fun setupZoomButtons() {
        findViewById<Button>(R.id.btn_zoom_1x).setOnClickListener { setZoom(1f) }
        findViewById<Button>(R.id.btn_zoom_2x).setOnClickListener { setZoom(2f) }
        findViewById<Button>(R.id.btn_zoom_3x).setOnClickListener { setZoom(3f) }
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
    }

    private fun decodePhoto(uri: Uri) {
        if (!handled.compareAndSet(false, true)) return
        try {
            val image = InputImage.fromFilePath(this, uri)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    if (qr != null) {
                        setResult(RESULT_OK, Intent().putExtra(RESULT_QR, qr))
                        finish()
                    } else {
                        handled.set(false)
                        Toast.makeText(this, getString(R.string.main_invalid_qr), Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener {
                    handled.set(false)
                    Toast.makeText(this, it.message ?: "QR photo failed", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            handled.set(false)
            Toast.makeText(this, e.message ?: "QR photo failed", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        if (handled.get()) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                if (qr != null && handled.compareAndSet(false, true)) {
                    setResult(RESULT_OK, Intent().putExtra(RESULT_QR, qr))
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }
}
