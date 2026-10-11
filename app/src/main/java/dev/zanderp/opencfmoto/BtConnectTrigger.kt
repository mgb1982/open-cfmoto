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
        val want = AppSettings.btTriggerMac(context) ?: return
        val device = deviceFrom(intent) ?: return
        val mac = try {
            device.address
        } catch (_: SecurityException) {
            LogBus.log("→ BT trigger: no Bluetooth permission to read the device")
            return
        } ?: return
        if (!mac.equals(want, ignoreCase = true)) return
        if (AndroidAutoService.isRunning) {
            // v2: within 20 min of switching off, the service is still up and parked — it used to
            // swallow the trigger here. The dash's Bluetooth means the bike is on: resume now.
            if (AndroidAutoService.isParked) {
                LogBus.log("→ BT trigger: $mac connected — bike back, resuming the parked session")
                AndroidAutoService.requestResume()
            }
            return
        }
        if (BikeMemory.lastQr(context) == null) {
            LogBus.log("→ BT trigger $mac ignored — no saved bike")
            return
        }
        LogBus.log("→ BT trigger: $mac connected — starting Connect")
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_BT_TRIGGER, true)
        }
        // Fallback first: if Android blocks the background launch (silently), one tap still connects.
        notifyTapToConnect(context, launch)
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            LogBus.log("→ BT trigger: no \"display over other apps\" permission — tap the notification to connect")
            return
        }
        // Android 15+: "display over other apps" only allows a background activity start while the
        // app has a *visible* overlay window. Show a 1 px one for a moment, launch, then remove it.
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dot = View(app).apply { setBackgroundColor(Color.TRANSPARENT) }
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
            LogBus.log("BT trigger overlay failed: $e")
        }
        main.postDelayed({
            try {
                app.startActivity(launch)
            } catch (e: Exception) {
                LogBus.log("BT trigger launch failed: $e")
            }
            main.postDelayed({
                if (added) try { wm.removeView(dot) } catch (_: Exception) {}
                pending.finish()
            }, 2500)
        }, 400)
    }

    private fun notifyTapToConnect(ctx: Context, launch: Intent) {
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
