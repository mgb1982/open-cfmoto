// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.util.concurrent.Executors

/**
 * v2.3 · The trip video is made here, not in the screen: the rider can leave it, lock the phone or
 * switch apps. Progress in a notification; when it's done, "Video ready" with Watch / Share. One
 * video at a time. The screen just shows [TripVideoJob]'s state while it's open.
 */
object TripVideoJob {
    data class Request(
        val tripId: String, val clips: List<TripVideo.Clip>, val speed: Float, val offsetMs: Long,
        val look: TripVideo.Look, val music: Uri?, val duck: Boolean, val highlights: Boolean,
    )

    enum class Stage { IDLE, MAP, EXPORT, SAVING, DONE, FAILED }

    @Volatile var stage = Stage.IDLE; internal set
    @Volatile var progress = 0; internal set
    @Volatile var tripId: String? = null; internal set
    @Volatile var highlights = false; internal set
    @Volatile var result: Uri? = null; internal set
    @Volatile var error: String? = null; internal set
    /** Made, but without the chosen song (it couldn't be mixed). */
    @Volatile var musicDropped = false; internal set
    @Volatile internal var pending: Request? = null

    /** Main thread. Set by the open screen. */
    var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    internal fun changed() = main.post { listener?.invoke() }

    val busy get() = stage == Stage.MAP || stage == Stage.EXPORT || stage == Stage.SAVING

    /** @return false when another video is still being made. */
    fun start(ctx: Context, r: Request): Boolean {
        if (busy) return false
        pending = r; tripId = r.tripId; highlights = r.highlights
        stage = Stage.MAP; progress = 0; result = null; error = null; musicDropped = false
        changed()
        androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, TripVideoService::class.java))
        return true
    }

    fun cancel(ctx: Context) {
        if (!busy) return
        ctx.startService(Intent(ctx, TripVideoService::class.java).setAction(TripVideoService.ACTION_CANCEL))
    }
}

@OptIn(UnstableApi::class)
class TripVideoService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNotified = -1
    @Volatile private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            transformer?.cancel(); transformer = null
            finish(TripVideoJob.Stage.FAILED, null, getString(R.string.tv_cancelled))
            return START_NOT_STICKY
        }
        val req = TripVideoJob.pending
        if (req == null || transformer != null) { if (req == null) stopSelf(); return START_NOT_STICKY }
        TripVideoJob.pending = null
        finished = false
        try {
            ServiceCompat.startForeground(this, NOTIF_PROGRESS, progressNotification(0, map = true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            LogBus.log("[VIDEO] can't run in the background: $e")
            finish(TripVideoJob.Stage.FAILED, null, e.javaClass.simpleName); return START_NOT_STICKY
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideScreen:tripVideo").apply { acquire(90 * 60_000L) }
        io.execute { prepare(req) }
        return START_NOT_STICKY
    }

    private fun prepare(req: TripVideoJob.Request) {
        val trip = TripStore.get(this, req.tripId)
        if (trip == null) { ui.post { finish(TripVideoJob.Stage.FAILED, null, "trip") }; return }
        var streets: StreetTiles? = null
        if (req.look.map == VideoMap.STREETS) {
            streets = StreetTiles.prepare(this, trip, nightStyle(req.look.style)) { d, n ->
                TripVideoJob.progress = d * 100 / maxOf(1, n); TripVideoJob.changed(); notifyProgress(map = true)
            }
            if (streets == null) LogBus.log("[VIDEO] street map unavailable, using the route line")
        }
        // Attempts, simplest last: everything → without music → without the cards.
        val attempts = ArrayList<Pair<Uri?, Boolean>>()
        attempts += req.music to req.look.cards
        if (req.music != null && req.look.cards) attempts += req.music to false   // keep the song if the cards are the problem
        if (req.music != null) attempts += null to req.look.cards
        if (req.look.cards) attempts += null to false
        run(req, trip, streets, attempts, 0)
    }

    private fun run(req: TripVideoJob.Request, trip: Trip, streets: StreetTiles?, attempts: List<Pair<Uri?, Boolean>>, i: Int) {
        val (music, cards) = attempts[i]
        val comp = try {
            TripVideo.composition(this, trip, req.clips, req.speed, req.offsetMs, req.look, streets, cards, music, req.duck, req.highlights)
        } catch (e: Exception) { LogBus.log("[VIDEO] composition failed: $e"); null }
        ui.post {
            if (finished) return@post
            if (comp == null) { finish(TripVideoJob.Stage.FAILED, null, "composition"); return@post }
            val out = TripVideo.outputFile(this).apply { delete() }
            TripVideoJob.stage = TripVideoJob.Stage.EXPORT; TripVideoJob.progress = 0; TripVideoJob.changed()
            val t = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    TripVideoJob.musicDropped = req.music != null && music == null
                    LogBus.log("[VIDEO] export done (music=${music != null}, cards=$cards, best=${req.highlights}) · " +
                        "${exportResult.durationMs / 1000} s, ${exportResult.fileSizeBytes / 1_000_000} MB, audio=${exportResult.audioEncoderName ?: exportResult.audioMimeType ?: "none"}")
                    TripVideoJob.stage = TripVideoJob.Stage.SAVING; TripVideoJob.progress = 100; TripVideoJob.changed()
                    io.execute {
                        val uri = TripVideo.publish(this@TripVideoService, trip, out, if (req.highlights) "_best" else "")
                        ui.post {
                            if (uri != null) finish(TripVideoJob.Stage.DONE, uri, null)
                            else finish(TripVideoJob.Stage.FAILED, null, getString(R.string.tv_save_failed))
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                    transformer = null
                    val causes = generateSequence(e.cause) { it.cause }.take(4).joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
                    LogBus.log("[VIDEO] export failed (music=${music != null}, cards=$cards): ${e.errorCodeName} ${e.message}" +
                        if (causes.isNotEmpty()) " · $causes" else "")
                    if (i + 1 < attempts.size) {
                        LogBus.log("[VIDEO] retrying more simply (attempt ${i + 2}/${attempts.size})")
                        io.execute { run(req, trip, streets, attempts, i + 1) }
                    } else finish(TripVideoJob.Stage.FAILED, null, e.errorCodeName)
                }
            }).build()
            transformer = t
            try { t.start(comp, out.absolutePath) } catch (e: Exception) {
                transformer = null; finish(TripVideoJob.Stage.FAILED, null, e.javaClass.simpleName); return@post
            }
            val holder = ProgressHolder()
            ui.post(object : Runnable {
                override fun run() {
                    val tr = transformer ?: return
                    if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        TripVideoJob.progress = holder.progress; TripVideoJob.changed(); notifyProgress(map = false)
                    }
                    ui.postDelayed(this, 1000)
                }
            })
        }
    }

    private fun finish(stage: TripVideoJob.Stage, uri: Uri?, error: String?) {
        if (finished) return
        finished = true
        TripVideoJob.stage = stage; TripVideoJob.result = uri; TripVideoJob.error = error
        TripVideoJob.changed()
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        resultNotification(stage, uri, error)
        stopSelf()
    }

    override fun onDestroy() {
        transformer?.cancel(); transformer = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        io.shutdownNow()
        if (TripVideoJob.busy) { TripVideoJob.stage = TripVideoJob.Stage.FAILED; TripVideoJob.error = "stopped"; TripVideoJob.changed() }
        super.onDestroy()
    }

    // ── notifications

    private fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, getString(R.string.tv_notif_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, getString(R.string.tv_notif_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        this, 31, TripVideoActivity.intent(this, TripVideoJob.tripId.orEmpty()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(pct: Int, map: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java); channel(nm)
        val cancel = PendingIntent.getService(this, 32, Intent(this, TripVideoService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(if (TripVideoJob.highlights) R.string.tv_notif_title_best else R.string.tv_notif_title))
            .setContentText(if (map) getString(R.string.tv_map_loading) else getString(R.string.tv_notif_making, pct))
            .setProgress(100, pct, false)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(openScreen())
            .addAction(0, getString(R.string.tv_cancel), cancel)
            .build()
    }

    private fun notifyProgress(map: Boolean) {
        val pct = TripVideoJob.progress
        if (pct == lastNotified) return
        lastNotified = pct
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_PROGRESS, progressNotification(pct, map)) } catch (_: Exception) {}
    }

    private fun resultNotification(stage: TripVideoJob.Stage, uri: Uri?, error: String?) {
        if (TripVideoJob.listener != null && stage == TripVideoJob.Stage.DONE) return   // the screen is open: it shows it
        val nm = getSystemService(NotificationManager::class.java); channel(nm)
        val b = NotificationCompat.Builder(this, CH_DONE).setSmallIcon(R.drawable.ic_launcher_monochrome).setAutoCancel(true)
        if (stage == TripVideoJob.Stage.DONE && uri != null) {
            val view = PendingIntent.getActivity(this, 33,
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            val share = PendingIntent.getActivity(this, 34,
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            val title = TripVideoJob.tripId?.let { TripStore.get(this, it) }?.let { TripNames.title(this, it) }.orEmpty()
            b.setContentTitle(getString(R.string.tv_notif_done_title)).setContentText(title)
                .setContentIntent(view)
                .addAction(0, getString(R.string.tv_open), view)
                .addAction(0, getString(R.string.share_button), share)
        } else {
            if (error == getString(R.string.tv_cancelled)) return
            b.setContentTitle(getString(R.string.tv_notif_failed)).setContentText(error.orEmpty()).setContentIntent(openScreen())
        }
        try { nm.notify(NOTIF_DONE, b.build()) } catch (_: SecurityException) {}
    }

    companion object {
        const val ACTION_CANCEL = "dev.zanderp.opencfmoto.TRIP_VIDEO_CANCEL"
        private const val CH_PROGRESS = "trip_video"
        private const val CH_DONE = "trip_video_done"
        private const val NOTIF_PROGRESS = 4601
        private const val NOTIF_DONE = 4602
    }
}
