// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 🎬 Build a video of one trip from the clips filmed during it (see [TripVideo]). */
@OptIn(UnstableApi::class)
class TripVideoActivity : AppCompatActivity() {

    private lateinit var trip: Trip
    private lateinit var status: TextView
    private lateinit var list: TextView
    private lateinit var progress: ProgressBar
    private lateinit var normalBtn: MaterialButton
    private lateinit var fastBtn: MaterialButton
    private lateinit var makeBtn: MaterialButton
    private lateinit var resultRow: LinearLayout
    private var clips: List<TripVideo.Clip> = emptyList()
    private var speed = 1f
    private var offsetS = 0
    private lateinit var offsetLabel: TextView
    private var transformer: Transformer? = null
    private var saved: Uri? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val askVideos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent.getStringExtra(EXTRA_ID)?.let { TripStore.get(this, it) }
        if (t == null || t.points.size < 2) { finish(); return }
        trip = t
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.tv_title)
            textSize = 24f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        root.addView(body(TripNames.title(this, trip) + " · " + trip.timeRangeText()))
        root.addView(body(getString(R.string.tv_how)))
        status = body("").apply { setTextColor(color(R.color.text_primary)); textSize = 15f }
        root.addView(status)
        list = body("")
        root.addView(list)

        val speeds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        normalBtn = button(getString(R.string.tv_speed_normal)) { speed = 1f; mark() }
        fastBtn = button(getString(R.string.tv_speed_fast)) { speed = 4f; mark() }
        speeds.addView(normalBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        speeds.addView(fastBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(speeds)
        // Manual nudge if the overlay runs ahead of / behind the picture (applies to every clip).
        val sync = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        offsetLabel = body("").apply { setPadding(0, 0, 0, 0) }
        sync.addView(button("−5 s") { offsetS -= 5; markOffset() })
        sync.addView(button("−1 s") { offsetS -= 1; markOffset() })
        sync.addView(offsetLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        sync.addView(button("+1 s") { offsetS += 1; markOffset() })
        sync.addView(button("+5 s") { offsetS += 5; markOffset() })
        root.addView(sync)
        markOffset()
        makeBtn = button(getString(R.string.tv_make)) { make() }
        root.addView(makeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        resultRow.addView(button(getString(R.string.tv_open)) { open() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        resultRow.addView(button(getString(R.string.share_button)) { share() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(resultRow)
        root.addView(body(getString(R.string.tv_meta_tip)))

        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        mark()
        if (hasVideoAccess()) scan() else askVideos.launch(videoPermissions())
    }

    override fun onDestroy() {
        transformer?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun videoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoAccess(): Boolean = videoPermissions().any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scan() {
        status.setText(R.string.tv_searching)
        makeBtn.isEnabled = false
        io.execute {
            val found = TripVideo.findClips(this, trip)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                clips = found
                if (found.isEmpty()) {
                    status.setText(if (hasVideoAccess()) R.string.tv_none else R.string.tv_no_permission)
                    list.text = ""
                    return@runOnUiThread
                }
                val total = found.sumOf { it.durationMs } / 1000
                status.text = getString(R.string.tv_found, found.size, total / 60, total % 60)
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                list.text = found.joinToString("\n") { "• ${fmt.format(Date(it.startMs))} · ${it.durationMs / 1000} s · ${it.name}" }
                makeBtn.isEnabled = true
            }
        }
    }

    private fun markOffset() {
        offsetLabel.text = getString(R.string.tv_sync, (if (offsetS > 0) "+" else "") + offsetS)
        offsetLabel.gravity = android.view.Gravity.CENTER
    }

    private fun mark() {
        normalBtn.alpha = if (speed == 1f) 1f else 0.5f
        fastBtn.alpha = if (speed > 1f) 1f else 0.5f
    }

    private fun make() {
        if (clips.isEmpty() || transformer != null) return
        val out = TripVideo.outputFile(this).apply { delete() }
        makeBtn.isEnabled = false
        resultRow.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.setText(R.string.tv_making)
        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    ui.removeCallbacksAndMessages(null)
                    progress.progress = 100
                    io.execute {
                        val uri = TripVideo.publish(this@TripVideoActivity, trip, out)
                        runOnUiThread {
                            saved = uri
                            status.setText(if (uri != null) R.string.tv_done else R.string.tv_save_failed)
                            resultRow.visibility = if (uri != null) View.VISIBLE else View.GONE
                            makeBtn.isEnabled = true
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    ui.removeCallbacksAndMessages(null)
                    LogBus.log("[VIDEO] export failed: ${exportException.errorCodeName} ${exportException.message}")
                    status.text = getString(R.string.tv_failed, exportException.errorCodeName)
                    progress.visibility = View.GONE
                    makeBtn.isEnabled = true
                }
            })
            .build()
        transformer = t
        try {
            t.start(TripVideo.composition(this, trip, clips, speed, offsetS * 1000L), out.absolutePath)
        } catch (e: Exception) {
            transformer = null
            status.text = getString(R.string.tv_failed, e.javaClass.simpleName)
            makeBtn.isEnabled = true
            return
        }
        val holder = ProgressHolder()
        ui.post(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress.progress = holder.progress
                ui.postDelayed(this, 500)
            }
        })
    }

    private fun open() {
        val uri = saved ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {}
    }

    private fun share() {
        val uri = saved ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "trip_id"
        fun start(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, TripVideoActivity::class.java).putExtra(EXTRA_ID, id))
    }
}
