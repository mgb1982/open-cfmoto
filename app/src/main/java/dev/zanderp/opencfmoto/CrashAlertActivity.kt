// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.lang.ref.WeakReference

/** Full-screen 🆘 countdown, over the lock screen. Huge "I'm OK"; small "Send now". */
class CrashAlertActivity : AppCompatActivity() {

    private lateinit var count: TextView
    private lateinit var info: TextView
    private lateinit var okBtn: MaterialButton
    private lateinit var sendBtn: MaterialButton
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (CrashDetector.alarmActive) {
                count.text = CrashDetector.secondsLeft().toString()
                ticker.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(170, 20, 20))
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = getString(if (CrashDetector.alarmTest) R.string.crash_alert_title_test else R.string.crash_alert_title)
            textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        count = TextView(this).apply {
            textSize = 96f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(count)
        info = TextView(this).apply {
            text = getString(R.string.crash_alert_info, CrashDetector.contacts(this@CrashAlertActivity).size)
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * dp).toInt())
        }
        root.addView(info)
        okBtn = MaterialButton(this).apply {
            text = getString(R.string.crash_im_ok)
            textSize = 30f
            setTextColor(Color.rgb(170, 20, 20))
            setBackgroundColor(Color.WHITE)
            cornerRadius = (40 * dp).toInt()
            setOnClickListener { CrashDetector.cancelAlarm(this@CrashAlertActivity, "phone") }
        }
        root.addView(okBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
        sendBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.crash_send_now)
            setTextColor(Color.WHITE)
            setOnClickListener { CrashDetector.sendNow(this@CrashAlertActivity) }
        }
        root.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (24 * dp).toInt()
        })
        setContentView(root)
        if (!CrashDetector.alarmActive) finish()
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    @Deprecated("Back must not dismiss a crash alarm by accident")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { /* use "I'm OK" */ }

    private fun renderSent(sentTo: List<String>, test: Boolean) {
        ticker.removeCallbacks(tick)
        count.textSize = 28f
        count.text = when {
            test -> getString(R.string.crash_sim_done, sentTo.size)
            sentTo.isEmpty() -> getString(R.string.crash_sent_none)
            else -> getString(R.string.crash_sent_to, sentTo.joinToString(", "))
        }
        info.text = if (test) CrashDetector.messageText(this, RideExtras.lastLocation(this, 10 * 60_000L), followUp = false, test = true) else ""
        sendBtn.visibility = android.view.View.GONE
        okBtn.text = getString(R.string.crash_close)
        okBtn.textSize = 22f
        okBtn.setOnClickListener { finish() }
    }

    companion object {
        private var current: WeakReference<CrashAlertActivity>? = null
        fun close() { current?.get()?.finish() }
        fun showSent(sentTo: List<String>, test: Boolean) { current?.get()?.renderSent(sentTo, test) }
    }
}
