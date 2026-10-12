// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto.wear

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.lang.ref.WeakReference

/**
 * 🆘 Crash alarm on the watch: strong vibration + a full-screen "I'm OK" that cancels it on the phone.
 * Driven by the phone's /ocm/crash messages ("start:<s>", "stop", "sent").
 */
object CrashAlert {
    const val PATH = "/ocm/crash"
    private const val CHANNEL = "crash_alert"
    private const val NOTIF_ID = 4502

    @Volatile var endsAt = 0L
        private set
    @Volatile var state = "idle"   // idle | counting | sent
        private set

    fun onMessage(ctx: Context, text: String) {
        when {
            text.startsWith("start:") -> {
                val s = text.substringAfter(':').toIntOrNull() ?: 60
                endsAt = SystemClock.elapsedRealtime() + s * 1000L
                state = "counting"
                vibrate(ctx, true)
                notifyAlarm(ctx)
            }
            text == "stop" -> { state = "idle"; vibrate(ctx, false); dismiss(ctx); CrashActivity.refresh() }
            text == "sent" -> { state = "sent"; vibrate(ctx, false); dismiss(ctx); CrashActivity.refresh() }
        }
    }

    fun imOk(ctx: Context) {
        PhoneLink.send(ctx, PATH, "ok")
        state = "idle"
        vibrate(ctx, false)
        dismiss(ctx)
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun vibrate(ctx: Context, on: Boolean) {
        try {
            val v = vibrator(ctx) ?: return
            if (on) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0)) else v.cancel()
        } catch (_: Exception) {}
    }

    private fun notifyAlarm(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.crash_title), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentTitle(ctx.getString(R.string.crash_title))
            .setContentText(ctx.getString(R.string.crash_im_ok))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: Exception) {}
        try { ctx.startActivity(Intent(ctx, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun dismiss(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }
}

class CrashActivity : Activity() {
    private lateinit var count: TextView
    private lateinit var btn: Button
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); if (CrashAlert.state == "counting") h.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(170, 20, 20))
        }
        root.addView(TextView(this).apply {
            setText(R.string.crash_title); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        count = TextView(this).apply { textSize = 36f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        root.addView(count)
        btn = Button(this).apply {
            setText(R.string.crash_im_ok)
            textSize = 18f
            setTextColor(Color.rgb(170, 20, 20))
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                if (CrashAlert.state == "counting") CrashAlert.imOk(this@CrashActivity)
                finish()
            }
        }
        val dp = resources.displayMetrics.density
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (64 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { h.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    private fun render() {
        when (CrashAlert.state) {
            "counting" -> count.text = ((CrashAlert.endsAt - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0).toString()
            "sent" -> { count.textSize = 16f; count.setText(R.string.crash_sent); btn.setText(R.string.crash_close) }
            else -> finish()
        }
    }

    companion object {
        private var current: WeakReference<CrashActivity>? = null
        fun refresh() { current?.get()?.let { a -> a.runOnUiThread { a.render() } } }
    }
}
