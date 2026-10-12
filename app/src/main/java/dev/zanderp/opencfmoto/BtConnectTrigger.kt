// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Optional: when a chosen bonded device ACL-connects (helmet remote, watch, …),
 * open [MainActivity] and start Connect with the saved bike QR.
 */
class BtConnectTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        // v2.2: every early return is logged ([BT-TRIG]) — "doesn't connect by itself" reports came with
        // logs that showed nothing at all, so we couldn't tell "not configured" from "wrong device"
        // from "broadcast never arrived".
        val device = deviceFrom(intent)
        val mac = try { device?.address } catch (_: SecurityException) {
            LogBus.log("[BT-TRIG] ACL connected but no Bluetooth permission to read the device")
            return
        }
        val name = try { device?.name } catch (_: SecurityException) { null }
        val want = AppSettings.btTriggerMac(context)
        LogBus.log("[BT-TRIG] connected: ${name ?: "?"} ($mac) · trigger=${want ?: "not set"} · AA service=${AndroidAutoService.isRunning}")
        if (want == null || mac == null) return
        if (!mac.equals(want, ignoreCase = true)) {
            LogBus.log("[BT-TRIG] not the trigger device — ignored")
            return
        }
        if (AndroidAutoService.isRunning) {
            // v2: within 20 min of switching off, the service is still up and parked — it used to
            // swallow the trigger here. The dash's Bluetooth means the bike is on: resume now.
            if (AndroidAutoService.isParked) {
                LogBus.log("→ BT trigger: $mac connected — bike back, resuming the parked session")
                AndroidAutoService.requestResume()
            } else LogBus.log("[BT-TRIG] session already running — nothing to do")
            return
        }
        val saved = BikeMemory.lastQr(context)
        if (saved == null) {
            LogBus.log("→ BT trigger $mac ignored — no saved bike")
            return
        }
        // The dash's Bluetooth can be up with the bike off (and the phone may connect walking past).
        // Only the bike's Wi-Fi means "on": connect if it's visible, else watch for it for a few minutes.
        when (BikeWifi.isSsidInRange(context, saved.ssid)) {
            true -> {
                LogBus.log("→ BT trigger: $mac connected and the bike's Wi-Fi is up — starting Connect")
                val pending = goAsync()
                launchConnect(context.applicationContext, "BT trigger") { pending.finish() }
            }
            else -> {
                LogBus.log("→ BT trigger: $mac connected — bike Wi-Fi not seen yet, watching for it")
                if (!BikeWatchService.start(context, BikeWatchService.Mode.SHORT)) {
                    val pending = goAsync()
                    launchConnect(context.applicationContext, "BT trigger (no watch)") { pending.finish() }
                }
            }
        }
    }

    internal fun notifyTapToConnect(ctx: Context, launch: Intent) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.bt_trigger_channel), NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 4310, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_power)
                .setContentTitle(ctx.getString(R.string.bt_trigger_title, BikeMemory.lastBikeName(ctx) ?: ""))
                .setContentText(ctx.getString(R.string.bt_trigger_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000L)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (e: Exception) {
            LogBus.log("BT trigger notification failed: $e")
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        /**
         * Open the app and start Connect from the background: a "tap to connect" notification first
         * (in case Android blocks the launch), then — with "display over other apps" — a 1 px overlay
         * for a moment, because Android 15+ only lets an overlay holder start an activity from the
         * background while one of its overlay windows is visible.
         */
        fun launchConnect(ctx: Context, reason: String, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            val launch = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_BT_TRIGGER, true)
            }
            BtConnectTrigger().notifyTapToConnect(app, launch)
            if (!Settings.canDrawOverlays(app)) {
                LogBus.log("→ $reason: no \"display over other apps\" permission — tap the notification to connect")
                done(); return
            }
            val main = Handler(Looper.getMainLooper())
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dot = View(app).apply { setBackgroundColor(Color.TRANSPARENT) }
            main.post {
                var added = false
                try {
                    wm.addView(dot, WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply { gravity = Gravity.TOP or Gravity.START })
                    added = true
                } catch (e: Exception) {
                    LogBus.log("$reason overlay failed: $e")
                }
                main.postDelayed({
                    try { app.startActivity(launch) } catch (e: Exception) { LogBus.log("$reason launch failed: $e") }
                    main.postDelayed({
                        if (added) try { wm.removeView(dot) } catch (_: Exception) {}
                        done()
                    }, 2500)
                }, 400)
            }
        }

        private const val CHANNEL = "bike_detected"
        const val NOTIF_ID = 4310

        /** Connect started / live: the "tap to connect" fallback is no longer needed. */
        fun clearNotification(ctx: Context) {
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            } catch (_: Exception) {}
        }
    }
}
