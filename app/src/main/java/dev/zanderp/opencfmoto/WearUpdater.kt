// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * ⌚ Watch app updater (v2.2). Installing the Wear OS companion used to need a computer (ADB). Now
 * the phone fetches the watch APK — the "…-WearOS-reloj.apk" asset of the latest GitHub release, or
 * an APK file the rider picks — and streams it to the watch over the Data Layer (ChannelClient).
 * The watch app saves it and asks the rider to install it there (PackageInstaller + confirmation).
 * Only the github flavour offers this (Play installs the watch app by itself).
 */
object WearUpdater {
    const val PATH_APK = "/ocm/wearapk"

    private val main = Handler(Looper.getMainLooper())

    /** "Update the watch app" chooser: latest release from GitHub, or an APK already on the phone. */
    fun showDialog(activity: Activity, pickFile: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.wear_update_title)
            .setMessage(R.string.wear_update_body)
            .setPositiveButton(R.string.wear_update_latest) { _, _ -> sendLatest(activity) }
            .setNeutralButton(R.string.wear_update_pick) { _, _ -> pickFile() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun sendLatest(activity: Activity) {
        val ctx = activity.applicationContext
        toast(ctx, ctx.getString(R.string.wear_update_downloading))
        Thread {
            val file = File(ctx.cacheDir, "wear-update.apk")
            val url = latestWearApkUrl()
            if (url == null) { fail(ctx, R.string.wear_update_no_asset); return@Thread }
            if (!download(url, file)) { fail(ctx, R.string.wear_update_download_failed); return@Thread }
            send(ctx, file)
        }.start()
    }

    fun sendFromUri(activity: Activity, uri: Uri) {
        val ctx = activity.applicationContext
        Thread {
            val file = File(ctx.cacheDir, "wear-update.apk")
            val ok = try {
                ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
            } catch (e: Exception) {
                LogBus.log("[WEAR-UPD] could not read picked file: $e"); false
            }
            if (!ok) { fail(ctx, R.string.wear_update_download_failed); return@Thread }
            // A quick sanity check so a phone APK or a random file never reaches the watch.
            val info = try { ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0) } catch (_: Exception) { null }
            if (info == null) { fail(ctx, R.string.wear_update_not_apk); return@Thread }
            LogBus.log("[WEAR-UPD] picked APK ${info.packageName} ${info.versionName}")
            send(ctx, file)
        }.start()
    }

    /** Blocking: find the Wear OS asset of the latest release. */
    private fun latestWearApkUrl(): String? = try {
        val conn = (URL("https://api.github.com/repos/${UpdateChecker.REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RideScreenAA")
        }
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var url: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", true) && name.contains("wear", true)) { url = a.optString("browser_download_url"); break }
                }
                LogBus.log("[WEAR-UPD] latest release ${json.optString("tag_name")} watch asset=${url != null}")
                url
            }
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        LogBus.log("[WEAR-UPD] release lookup failed: $e"); null
    }

    /** Blocking download, following GitHub's redirect to its CDN by hand (different host). */
    private fun download(start: String, out: File): Boolean {
        var url = start
        try {
            repeat(6) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10000; readTimeout = 30000
                    setRequestProperty("User-Agent", "RideScreenAA")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        url = conn.getHeaderField("Location") ?: return false
                        return@repeat
                    }
                    if (code != 200) { LogBus.log("[WEAR-UPD] download HTTP $code"); return false }
                    conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                    LogBus.log("[WEAR-UPD] downloaded ${out.length() / 1024} KB")
                    return true
                } finally { conn.disconnect() }
            }
        } catch (e: Exception) {
            LogBus.log("[WEAR-UPD] download failed: $e")
        }
        return false
    }

    /** Blocking: stream the APK to the first connected watch that runs our app. */
    private fun send(ctx: Context, file: File) {
        try {
            val nodes = Tasks.await(Wearable.getCapabilityClient(ctx)
                .getCapability(WATCH_CAPABILITY, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS).nodes
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (node == null) {
                // No watch with an updater-capable app: none paired/reachable, or its app predates v2.2
                // (that one last install still has to be done by hand).
                val any = try { Tasks.await(Wearable.getNodeClient(ctx).connectedNodes, 10, TimeUnit.SECONDS).isNotEmpty() } catch (_: Exception) { false }
                LogBus.log("[WEAR-UPD] no updatable watch (any watch connected=$any)")
                fail(ctx, if (any) R.string.wear_update_old_watch_app else R.string.wear_update_no_watch); return
            }
            val client = Wearable.getChannelClient(ctx)
            val total = file.length()
            // The size rides in the path so the watch can show its own progress ("/ocm/wearapk/<bytes>").
            val channel = Tasks.await(client.openChannel(node.id, "$PATH_APK/$total"), 15, TimeUnit.SECONDS)
            LogBus.log("[WEAR-UPD] sending ${total / 1024} KB to ${node.displayName}")
            toast(ctx, ctx.getString(R.string.wear_update_sending, node.displayName))
            progress(ctx, node.displayName, 0)
            val started = android.os.SystemClock.elapsedRealtime()
            var sent = 0L
            var lastPct = -1
            // Written by hand (not sendFile) so we can count bytes and show a progress bar.
            Tasks.await(client.getOutputStream(channel), 15, TimeUnit.SECONDS).use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; progress(ctx, node.displayName, pct) }
                    }
                    out.flush()
                }
            }
            val secs = (android.os.SystemClock.elapsedRealtime() - started) / 1000
            LogBus.log("[WEAR-UPD] sent ${sent / 1024} KB to ${node.displayName} in ${secs}s")
            done(ctx, ctx.getString(R.string.wear_update_sent, node.displayName))
        } catch (e: Exception) {
            LogBus.log("[WEAR-UPD] send failed: $e")
            done(ctx, ctx.getString(R.string.wear_update_send_failed))
        }
    }

    private const val NOTIF_CHANNEL = "wear_update"
    private const val NOTIF_ID = 4312

    /** Progress notification on the phone while the APK travels over Bluetooth (it takes minutes). */
    private fun progress(ctx: Context, watch: String, pct: Int) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(android.app.NotificationChannel(NOTIF_CHANNEL,
                ctx.getString(R.string.wear_update_title), android.app.NotificationManager.IMPORTANCE_LOW))
            nm.notify(NOTIF_ID, androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(ctx.getString(R.string.wear_update_progress_title, watch))
                .setContentText(ctx.getString(R.string.wear_update_progress_body, pct))
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build())
        } catch (_: Exception) {}
    }

    private fun done(ctx: Context, text: String) {
        try { ctx.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIF_ID) } catch (_: Exception) {}
        toast(ctx, text)
    }

    /** Declared by watch builds that can receive an update (wear/res/values/wear.xml). */
    private const val WATCH_CAPABILITY = "ridescreen_wear_updatable"

    private fun fail(ctx: Context, res: Int) = toast(ctx, ctx.getString(res))

    private fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
}
