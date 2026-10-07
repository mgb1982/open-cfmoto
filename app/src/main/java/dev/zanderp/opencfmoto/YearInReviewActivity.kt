// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import java.util.Locale

/** "Your year on the bike": story-style cards (tap right = next, left = back) and a share image. */
class YearInReviewActivity : AppCompatActivity() {

    private var trips: List<Trip> = emptyList()
    private var stats: YearInReview.Stats? = null
    private var cards: List<YearInReview.Card> = emptyList()
    private var index = 0
    private var monthMode = false

    private lateinit var progress: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var btnYear: MaterialButton
    private lateinit var btnMonth: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#18211B"))
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnYear = pill(getString(R.string.yir_year)) { monthMode = false; reload() }
        btnMonth = pill(getString(R.string.yir_month)) { monthMode = true; reload() }
        val share = pill(getString(R.string.share_button)) { stats?.let { YearInReview.share(this, it) } }
        listOf(btnYear, btnMonth).forEach { bar.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(share)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        stage.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                if (e.x > v.width / 3f) next() else prev()
                v.performClick()
            }
            true
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + dp(16), b.top + dp(8), b.right + dp(16), b.bottom + dp(16))
            insets
        }

        Thread({
            val all = try { TripStore.list(this) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                trips = all
                reload()
            }
        }, "yir-load").start()
    }

    private fun reload() {
        btnYear.alpha = if (monthMode) 0.5f else 1f
        btnMonth.alpha = if (monthMode) 1f else 0.5f
        val now = Calendar.getInstance()
        val month = if (monthMode) now.get(Calendar.MONTH) else null
        val all = trips
        Thread({
            // Includes the heat grid (every GPS point): off the UI thread.
            val s = YearInReview.compute(this, all, now.get(Calendar.YEAR), month)
            val c = if (s.trips.isEmpty()) emptyList() else YearInReview.cards(this, s)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                stats = s
                cards = c
                // Names for the longest-ride card, if not known yet.
                s.longest?.let { TripNames.ensure(this, listOf(it)) }
                index = 0
                show()
            }
        }, "yir-compute").start()
    }

    private fun next() { if (index < cards.size - 1) { index++; show() } }
    private fun prev() { if (index > 0) { index--; show() } }

    private fun show() {
        progress.removeAllViews()
        for (i in cards.indices) {
            progress.addView(View(this).apply {
                setBackgroundColor(if (i <= index) ContextCompat.getColor(this@YearInReviewActivity, R.color.brand_orange)
                    else Color.parseColor("#3A463E"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(3) })
        }
        stage.removeAllViews()
        val s = stats ?: return
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        if (cards.isEmpty()) {
            col.addView(text(getString(R.string.yir_empty, s.label), 18f, Color.parseColor("#AEBAAA")))
            stage.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        val c = cards[index]
        col.addView(text(getString(R.string.yir_story_title, s.label), 15f, Color.parseColor("#AEBAAA")))
        if (c.emoji == YearInReview.ZONE) {
            col.addView(object : View(this) {
                override fun onDraw(canvas: Canvas) {
                    YearInReview.drawHeat(canvas, s.heat, 0f, 0f, width.toFloat(), height.toFloat())
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(16) })
        } else {
            col.addView(text(c.emoji, 64f, Color.WHITE).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })
            if (c.big.isNotBlank()) col.addView(text(c.big, 54f, ContextCompat.getColor(this, R.color.brand_orange), R.font.archivo_black))
        }
        col.addView(text(c.label.uppercase(Locale.getDefault()), 15f, Color.parseColor("#F1F4EC")).apply { letterSpacing = 0.1f })
        if (c.sub.isNotBlank()) col.addView(text(c.sub, 15f, Color.parseColor("#AEBAAA")))
        if (index == cards.size - 1) col.addView(text(getString(R.string.yir_tap_share), 13f, Color.parseColor("#AEBAAA")).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        })
        stage.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun text(s: String, size: Float, color: Int, font: Int? = null) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        font?.let { typeface = ResourcesCompat.getFont(this@YearInReviewActivity, it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun pill(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, YearInReviewActivity::class.java))
    }
}
