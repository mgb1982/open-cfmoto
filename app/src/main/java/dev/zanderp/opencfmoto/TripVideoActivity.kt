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
    private var saved: Uri? = null
    private lateinit var bestBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton
    private lateinit var musicLabel: TextView
    private lateinit var musicRemove: MaterialButton
    private lateinit var duckSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private var music: Uri? = null
    private var duck = true
    private var offsetRemembered = false
    private lateinit var madeHeader: TextView
    private lateinit var madeBox: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val askVideos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { scan() }

    // v2.3 · 🎵 a song from the phone (kept readable across restarts)
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        music = uri; saveLook(); markMusic()
    }

    // v2.3 · 🗑 deleting a made video goes through Android's own confirmation
    private val confirmDelete = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refreshMade() }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        // ── v2.3: the videos already made of this trip (they live in the gallery; found by name)
        madeHeader = header(getString(R.string.tv_made_title)).apply { visibility = View.GONE }
        root.addView(madeHeader)
        madeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(madeBox)

        // ── Look (v2.3): style, map, opening/closing cards, live preview
        loadLook()
        root.addView(header(getString(R.string.tv_look_title)))
        preview = android.widget.ImageView(this).apply {
            adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK); visibility = View.GONE
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(6) })
        root.addView(body(getString(R.string.tv_style)))
        styleRow = chipRow(VideoStyle.values().map { getString(it.labelRes) }) { i ->
            val before = nightStyle(style); style = VideoStyle.values()[i]
            if (nightStyle(style) != before) streets = null
            saveLook(); markLook(); refreshPreview()
        }
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
            sync.addView(button(label) { offsetS += d; offsetRemembered = false; rememberOffset(); markOffset(); refreshPreview() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        root.addView(sync)
        markOffset()

        // ── Music (v2.3)
        root.addView(header(getString(R.string.tv_music_title)))
        musicLabel = body("")
        root.addView(musicLabel)
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mrow.addView(button(getString(R.string.tv_music_pick)) { pickMusic.launch(arrayOf("audio/*")) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        musicRemove = button(getString(R.string.tv_music_remove)) { music = null; saveLook(); markMusic() }
        mrow.addView(musicRemove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(mrow)
        duckSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.tv_music_duck); isChecked = duck; setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, on -> duck = on; saveLook() }
        }
        root.addView(duckSwitch)
        markMusic()

        makeBtn = button(getString(R.string.tv_make)) { make() }
        root.addView(makeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        // ✨ Best of the ride: short vertical reel (v2.3)
        bestBtn = button(getString(R.string.tv_highlights)) { make(highlights = true) }
        root.addView(bestBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(body(getString(R.string.tv_highlights_help)))
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        cancelBtn = button(getString(R.string.tv_cancel)) { TripVideoJob.cancel(this) }.apply { visibility = View.GONE }
        root.addView(cancelBtn)
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

    override fun onResume() {
        super.onResume()
        refreshMade()   // also catches videos deleted from the gallery meanwhile
        TripVideoJob.listener = { renderJob() }
        renderJob()
    }

    override fun onPause() {
        TripVideoJob.listener = null
        super.onPause()
    }

    /** The background job's state, when it's about this trip. */
    private fun renderJob() {
        if (TripVideoJob.tripId != trip.id) { cancelBtn.visibility = View.GONE; return }
        val busy = TripVideoJob.busy
        makeBtn.isEnabled = !busy && clips.isNotEmpty(); bestBtn.isEnabled = makeBtn.isEnabled
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        when (TripVideoJob.stage) {
            TripVideoJob.Stage.MAP -> { progress.visibility = View.VISIBLE; progress.progress = TripVideoJob.progress; status.setText(R.string.tv_map_loading) }
            TripVideoJob.Stage.EXPORT -> { progress.visibility = View.VISIBLE; progress.progress = TripVideoJob.progress; status.setText(R.string.tv_making) }
            TripVideoJob.Stage.SAVING -> { progress.visibility = View.VISIBLE; progress.progress = 100; status.setText(R.string.tv_saving) }
            TripVideoJob.Stage.DONE -> {
                progress.visibility = View.GONE
                if (saved != TripVideoJob.result) { saved = TripVideoJob.result; refreshMade() }
                status.text = getString(R.string.tv_done) + if (TripVideoJob.musicDropped) "\n" + getString(R.string.tv_music_dropped) else ""
                resultRow.visibility = View.VISIBLE
            }
            TripVideoJob.Stage.FAILED -> {
                progress.visibility = View.GONE
                status.text = if (TripVideoJob.error == getString(R.string.tv_cancelled)) getString(R.string.tv_cancelled)
                    else getString(R.string.tv_failed, TripVideoJob.error.orEmpty())
            }
            TripVideoJob.Stage.IDLE -> {}
        }
    }

    private fun markMusic() {
        val m = music
        musicLabel.text = if (m == null) getString(R.string.tv_music_none) else "🎵 " + displayName(m)
        musicRemove.visibility = if (m == null) View.GONE else View.VISIBLE
        duckSwitch.visibility = if (m == null) View.GONE else View.VISIBLE
    }

    private fun displayName(u: Uri): String = try {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: u.lastPathSegment.orEmpty()
    } catch (_: Exception) { u.lastPathSegment.orEmpty() }

    private fun delete(m: TripVideo.Made) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = android.provider.MediaStore.createDeleteRequest(contentResolver, listOf(m.uri))
                confirmDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.tv_delete_confirm)
                    .setPositiveButton(R.string.tv_delete) { _, _ ->
                        try { contentResolver.delete(m.uri, null, null) } catch (e: Exception) { LogBus.log("[VIDEO] delete failed: $e") }
                        refreshMade()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        } catch (e: Exception) { LogBus.log("[VIDEO] delete failed: $e") }
    }

    private fun refreshMade() {
        if (io.isShutdown) return
        io.execute {
            val made = TripVideo.madeFor(this, trip)
            val thumbs = made.map { TripVideo.thumbnail(this, it.uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                madeBox.removeAllViews()
                made.forEachIndexed { i, m -> madeBox.addView(madeRow(m, thumbs[i])) }
                madeHeader.visibility = if (made.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun madeRow(m: TripVideo.Made, thumb: android.graphics.Bitmap?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
            thumb?.let { setImageBitmap(it) }
            contentDescription = getString(R.string.tv_open)
            setOnClickListener { open(m.uri) }
        }, LinearLayout.LayoutParams(dp(112), dp(63)).apply { marginEnd = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val when_ = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(m.addedMs))
        val secs = m.durationMs / 1000
        col.addView(TextView(this).apply {
            text = "$when_ · ${secs / 60}:${String.format(Locale.US, "%02d", secs % 60)} · " +
                android.text.format.Formatter.formatShortFileSize(context, m.sizeBytes)
            textSize = 13f; setTextColor(color(R.color.text_primary))
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(button(getString(R.string.tv_open)) { open(m.uri) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        btns.addView(button(getString(R.string.share_button)) { share(m.uri) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        btns.addView(button("🗑") { delete(m) }.apply { contentDescription = getString(R.string.tv_delete) })
        col.addView(btns)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    override fun onDestroy() {
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
        makeBtn.isEnabled = false; bestBtn.isEnabled = false
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
                makeBtn.isEnabled = true; bestBtn.isEnabled = true
                recallOffset()
                renderJob()
                refreshPreview()
            }
        }
    }

    private fun markOffset() {
        offsetLabel.text = (if (offsetS == 0) getString(R.string.tv_sync_zero) else getString(R.string.tv_sync, (if (offsetS > 0) "+" else "") + offsetS)) +
            if (offsetRemembered) "\n" + getString(R.string.tv_sync_remembered) else ""
    }

    // ── v2.3: the data/picture offset depends on each camera's clock, so it's remembered per camera.
    // A camera is told apart by how it names its files (digits blanked out) and its frame size.
    private fun cameraKey(c: TripVideo.Clip): String =
        c.name.lowercase().replace(Regex("[0-9a-f]{6,}"), "*").replace(Regex("[0-9]"), "9") + "|" + c.width + "x" + c.height

    private fun syncPrefs() = getSharedPreferences("trip_video_sync", MODE_PRIVATE)

    private fun rememberOffset() {
        val e = syncPrefs().edit()
        clips.map { cameraKey(it) }.distinct().forEach { e.putInt(it, offsetS) }
        e.apply()
    }

    private fun recallOffset() {
        if (offsetS != 0 || clips.isEmpty()) return
        val key = cameraKey(clips.first())
        val p = syncPrefs()
        if (!p.contains(key)) return
        offsetS = p.getInt(key, 0)
        offsetRemembered = offsetS != 0
        LogBus.log("[VIDEO] sync offset ${offsetS}s remembered for camera $key")
        markOffset()
    }

    private fun mark() {
        normalBtn.alpha = if (speed == 1f) 1f else 0.5f
        fastBtn.alpha = if (speed > 1f) 1f else 0.5f
    }

    /** Hands the job to [TripVideoService]: the rider can leave the screen meanwhile. */
    private fun make(highlights: Boolean = false) {
        if (clips.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val req = TripVideoJob.Request(trip.id, clips, speed, offsetS * 1000L, TripVideo.Look(style, mapKind, cards, watermark),
            music, duck, highlights)
        resultRow.visibility = View.GONE
        if (!TripVideoJob.start(this, req)) {
            android.widget.Toast.makeText(this, R.string.tv_busy, android.widget.Toast.LENGTH_LONG).show(); return
        }
        LogBus.log("[VIDEO] started in the background (best=$highlights, music=${music != null}, style=$style, map=$mapKind)")
        renderJob()
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
        music = p.getString("music", null)?.let { Uri.parse(it) }?.takeIf { u ->
            contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
        }
        duck = p.getBoolean("duck", true)
    }
    private fun saveLook() = prefs().edit().putString("style", style.name).putString("map", mapKind.name)
        .putBoolean("cards", cards).putBoolean("wm", watermark)
        .putString("music", music?.toString()).putBoolean("duck", duck).apply()

    /** A still from the first clip with the chosen look on top, a second or so after picking. */
    private fun refreshPreview() {
        val clip = clips.firstOrNull() ?: return
        val job = ++previewJob
        val wantStreets = mapKind == VideoMap.STREETS && streets == null
        if (wantStreets) status.setText(R.string.tv_map_loading)
        io.execute {
            if (job != previewJob) return@execute
            if (wantStreets) streets = StreetTiles.prepare(this, trip, nightStyle(style)) { _, _ -> }
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

    private fun open(uri: Uri? = saved) {
        if (uri == null) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {}
    }

    private fun share(uri: Uri? = saved) {
        if (uri == null) return
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
        fun intent(ctx: Context, id: String) = Intent(ctx, TripVideoActivity::class.java).putExtra(EXTRA_ID, id)
        fun start(ctx: Context, id: String) = ctx.startActivity(intent(ctx, id))
    }
}
