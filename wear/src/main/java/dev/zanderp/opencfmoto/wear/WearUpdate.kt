// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.wear

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.File

/**
 * ⌚ Self-update of the watch app (v2.2). The phone streams the new watch APK over a Data Layer
 * channel ([PATH_APK]); [SessionListenerService] saves it with [receive] and, once complete, this
 * shows a notification + [WearUpdateActivity], where one tap installs it through PackageInstaller
 * (Android asks to confirm, and the first time to allow "install unknown apps" for RideScreen).
 */
object WearUpdate {
    const val PATH_APK = "/ocm/wearapk"
    private const val TAG = "WearUpdate"
    private const val CHANNEL = "update"
    private const val NOTIF_ID = 7

    fun file(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** Called from the listener when the phone opens the channel: write it straight to [file]. */
    fun receive(ctx: Context, channel: ChannelClient.Channel) {
        val f = file(ctx)
        f.delete()
        Log.i(TAG, "receiving update from ${channel.nodeId}")
        Wearable.getChannelClient(ctx).receiveFile(channel, Uri.fromFile(f), false)
    }

    /** Transfer finished (or failed): tell the rider and open the install screen. */
    fun onReceived(ctx: Context, ok: Boolean) {
        val f = file(ctx)
        val valid = ok && f.length() > 0 &&
            ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.packageName == ctx.packageName
        Log.i(TAG, "transfer done ok=$ok size=${f.length()} valid=$valid")
        if (!valid) { f.delete(); return }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.wupd_title), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, WearUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentTitle(ctx.getString(R.string.wupd_title))
            .setContentText(ctx.getString(R.string.wupd_ready))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
        // Best effort: also open the screen right away (the watch is usually on the wrist, awake).
        try { ctx.startActivity(Intent(ctx, WearUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    fun clearNotification(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)

    /** Hands the APK to PackageInstaller; the result comes back to [InstallResultReceiver]. */
    fun install(ctx: Context): Boolean = try {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file(ctx).inputStream().use { input ->
                s.openWrite("ridescreen-wear.apk", 0, file(ctx).length()).use { out ->
                    input.copyTo(out); s.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "install failed: $e")
        false
    }
}

/** PackageInstaller's answer: show Android's confirmation, or report the failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("WearUpdate", "install status=$status $msg")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
                    Log.w("WearUpdate", "confirm screen failed: $e")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> WearUpdate.file(ctx).delete()
            else -> {
                val text = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        ctx.getString(R.string.wupd_failed_signature)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> ctx.getString(R.string.wupd_cancelled)
                    else -> ctx.getString(R.string.wupd_failed, msg ?: status.toString())
                }
                ctx.startActivity(Intent(ctx, WearUpdateActivity::class.java)
                    .putExtra(WearUpdateActivity.EXTRA_ERROR, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** "Update RideScreen AA on the watch": installed vs new version and a single Install button. */
class WearUpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private var askedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearUpdate.clearNotification(this)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((22 * dp).toInt(), (30 * dp).toInt(), (22 * dp).toInt(), (40 * dp).toInt())
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.wupd_title); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFC8D0C4.toInt()); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
        }
        col.addView(status)
        button = Button(this).apply {
            text = getString(R.string.wupd_install)
            setOnClickListener { onInstall() }
        }
        col.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        render(intent.getStringExtra(EXTRA_ERROR))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent.getStringExtra(EXTRA_ERROR))
    }

    override fun onResume() {
        super.onResume()
        // Back from the "install unknown apps" screen with the permission granted: carry on.
        if (askedPermission && packageManager.canRequestPackageInstalls()) { askedPermission = false; onInstall() }
    }

    private fun render(error: String?) {
        val f = WearUpdate.file(this)
        val newInfo = if (f.exists()) packageManager.getPackageArchiveInfo(f.absolutePath, 0) else null
        if (newInfo == null) {
            status.text = error ?: getString(R.string.wupd_nothing)
            button.visibility = android.view.View.GONE
            return
        }
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        status.text = (error?.let { "$it\n\n" } ?: "") + getString(R.string.wupd_versions, current, newInfo.versionName)
        button.visibility = android.view.View.VISIBLE
    }

    private fun onInstall() {
        if (!packageManager.canRequestPackageInstalls()) {
            askedPermission = true
            val ok = try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))); true
            } catch (_: Exception) { false }
            status.text = getString(if (ok) R.string.wupd_allow_unknown else R.string.wupd_no_unknown_setting)
            if (!ok) askedPermission = false
            return
        }
        button.isEnabled = false
        status.text = getString(R.string.wupd_installing)
        if (!WearUpdate.install(this)) {
            button.isEnabled = true
            render(getString(R.string.wupd_failed, "session"))
        }
    }

    companion object { const val EXTRA_ERROR = "error" }
}
