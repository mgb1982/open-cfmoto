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
    // v2.3 look
    private var style = VideoStyle.CLASSIC
    private var mapKind = VideoMap.LINE
    private var cards = true
    private var watermark = true
    private var streets: StreetTiles? = null
    private var still: android.graphics.Bitmap? = null
    private lateinit var preview: android.widget.ImageView
    private lateinit var styleRow: LinearLayout
    private lateinit var mapRow: LinearLayout
    private var previewJob = 0
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

        // ── Look (v2.3): style, map, opening/closing cards, live preview
        loadLook()
        root.addView(header(getString(R.string.tv_look_title)))
        preview = android.widget.ImageView(this).apply {
            adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK); visibility = View.GONE
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(6) })
        root.addView(body(getString(R.string.tv_style)))
        styleRow = chipRow(VideoStyle.values().map { getString(it.labelRes) }) { i -> style = VideoStyle.values()[i]; saveLook(); markLook(); refreshPreview() }
        root.addView(scrollRow(styleRow))
        root.addView(body(getString(R.string.tv_map)))
        mapRow = chipRow(VideoMap.values().map { getString(it.labelRes) }) { i -> mapKind = VideoMap.values()[i]; saveLook(); markLook(); refreshPreview() }
        root.addView(scrollRow(mapRow))
        root.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.tv_cards); isChecked = cards; setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, on -> cards = on; saveLook(); markLook() }
        })
        root.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            id = View.generateViewId()
            text = getString(R.string.tv_watermark); isChecked = watermark; setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, on -> watermark = on; saveLook() }
            tag = "wm"
        })

        // ── Speed
        root.addView(header(getString(R.string.tv_speed_title)))
        val speeds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        normalBtn = button(getString(R.string.tv_speed_normal)) { speed = 1f; mark() }
        fastBtn = button(getString(R.string.tv_speed_fast)) { speed = 4f; mark() }
        speeds.addView(normalBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        speeds.addView(fastBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(speeds)

        // ── Sync: nudge if the data runs ahead of / behind the picture (applies to every clip).
        root.addView(header(getString(R.string.tv_sync_title)))
        root.addView(body(getString(R.string.tv_sync_help)))
        offsetLabel = TextView(this).apply {
            textSize = 15f; setTextColor(color(R.color.text_primary)); gravity = android.view.Gravity.CENTER; setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(offsetLabel)
        val sync = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, d) in listOf("−5 s" to -5, "−1 s" to -1, "+1 s" to 1, "+5 s" to 5)) {
            sync.addView(button(label) { offsetS += d; markOffset(); refreshPreview() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
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
        markLook()
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
                refreshPreview()
            }
        }
    }

    private fun markOffset() {
        offsetLabel.text = if (offsetS == 0) getString(R.string.tv_sync_zero) else getString(R.string.tv_sync, (if (offsetS > 0) "+" else "") + offsetS)
    }

    private fun mark() {
        normalBtn.alpha = if (speed == 1f) 1f else 0.5f
        fastBtn.alpha = if (speed > 1f) 1f else 0.5f
    }

    private fun make(withCards: Boolean = cards) {
        if (clips.isEmpty() || transformer != null) return
        makeBtn.isEnabled = false
        resultRow.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        if (mapKind == VideoMap.STREETS && streets == null) {
            status.setText(R.string.tv_map_loading)
            io.execute {
                val st = StreetTiles.prepare(this, trip) { d, n -> runOnUiThread { progress.progress = d * 100 / maxOf(1, n) } }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    streets = st
                    if (st == null) status.setText(R.string.tv_map_failed)
                    makeBtn.isEnabled = true
                    if (st != null) make(withCards)
                }
            }
            return
        }
        val out = TripVideo.outputFile(this).apply { delete() }
        status.setText(R.string.tv_making)
        val look = TripVideo.Look(style, mapKind, cards, watermark)
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
                    LogBus.log("[VIDEO] export failed (cards=$withCards): ${exportException.errorCodeName} ${exportException.message}")
                    if (withCards) {
                        // The opening/closing cards are the newest part: try once more without them.
                        LogBus.log("[VIDEO] retrying without the opening/closing cards")
                        makeBtn.isEnabled = true
                        make(withCards = false)
                        return
                    }
                    status.text = getString(R.string.tv_failed, exportException.errorCodeName)
                    progress.visibility = View.GONE
                    makeBtn.isEnabled = true
                }
            })
            .build()
        transformer = t
        io.execute {
            val comp = try {
                TripVideo.composition(this, trip, clips, speed, offsetS * 1000L, look, streets, withCards)
            } catch (e: Exception) { LogBus.log("[VIDEO] composition failed: $e"); null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    t.start(comp ?: throw IllegalStateException("composition"), out.absolutePath)
                } catch (e: Exception) {
                    transformer = null
                    status.text = getString(R.string.tv_failed, e.javaClass.simpleName)
                    makeBtn.isEnabled = true
                    return@runOnUiThread
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
        }
    }

    // ── look: chips, persistence, preview

    private fun chipRow(labels: List<String>, onPick: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        labels.forEachIndexed { i, l ->
            addView(button(l) { onPick(i) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun scrollRow(row: LinearLayout) = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; addView(row)
    }

    private fun markLook() {
        for (i in 0 until styleRow.childCount) styleRow.getChildAt(i).alpha = if (i == style.ordinal) 1f else 0.45f
        for (i in 0 until mapRow.childCount) mapRow.getChildAt(i).alpha = if (i == mapKind.ordinal) 1f else 0.45f
        (window.decorView.findViewWithTag<View>("wm"))?.isEnabled = cards
    }

    private fun prefs() = getSharedPreferences("trip_video", MODE_PRIVATE)
    private fun loadLook() {
        val p = prefs()
        style = runCatching { VideoStyle.valueOf(p.getString("style", style.name)!!) }.getOrDefault(VideoStyle.CLASSIC)
        mapKind = runCatching { VideoMap.valueOf(p.getString("map", mapKind.name)!!) }.getOrDefault(VideoMap.LINE)
        cards = p.getBoolean("cards", true); watermark = p.getBoolean("wm", true)
    }
    private fun saveLook() = prefs().edit().putString("style", style.name).putString("map", mapKind.name)
        .putBoolean("cards", cards).putBoolean("wm", watermark).apply()

    /** A still from the first clip with the chosen look on top, a second or so after picking. */
    private fun refreshPreview() {
        val clip = clips.firstOrNull() ?: return
        val job = ++previewJob
        val wantStreets = mapKind == VideoMap.STREETS && streets == null
        if (wantStreets) status.setText(R.string.tv_map_loading)
        io.execute {
            if (job != previewJob) return@execute
            if (wantStreets) streets = StreetTiles.prepare(this, trip) { _, _ -> }
            val base = still ?: TripVideo.stillOf(this, clip).also { still = it } ?: return@execute
            val out = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val look = TripLook(this, trip, TripNames.title(this, trip), style, mapKind, streets)
            look.drawFrame(android.graphics.Canvas(out), out.width, out.height, clip.startMs + offsetS * 1000L + clip.durationMs / 2)
            runOnUiThread {
                if (job != previewJob || isFinishing || isDestroyed) return@runOnUiThread
                preview.setImageBitmap(out); preview.visibility = View.VISIBLE
                if (wantStreets) status.text = if (streets == null) getString(R.string.tv_map_failed)
                    else getString(R.string.tv_found, clips.size, clips.sumOf { it.durationMs } / 60000, clips.sumOf { it.durationMs } / 1000 % 60)
            }
        }
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(color(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(2))
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
