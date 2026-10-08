// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import java.util.Date

/** 🏆 Trophies: earned ones first (with the date), then the locked ones with their progress. */
class TrophiesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.bg)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = getString(R.string.tr_screen_title)
            textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        header = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(header)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Thread({
            val all = try { Trophies.compute(TripStore.summaries(this)) } catch (_: Exception) { emptyList() }
            runOnUiThread { if (!isFinishing && !isDestroyed) render(all) }
        }, "trophies").start()
    }

    private fun render(all: List<Trophies.Trophy>) {
        list.removeAllViews()
        val earned = all.filter { it.unlocked }.sortedByDescending { it.unlockedAt }
        header.text = getString(R.string.tr_header, earned.size, all.size)
        earned.forEach { list.addView(card(it)) }
        // Locked: only the next tier of each family (no wall of greyed-out 10,000 km goals),
        // closest to done first.
        val next = all.filter { !it.unlocked }.groupBy { it.family }.values.map { it.first() }
            .sortedByDescending { it.progress / it.target }
        if (next.isNotEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.tr_next)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(18), 0, dp(2))
            })
            next.forEach { list.addView(card(it)) }
        }
    }

    private fun card(t: Trophies.Trophy): MaterialCardView {
        val gold = ContextCompat.getColor(this, R.color.brand_gold)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            radius = dp(18).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
            if (t.unlocked) {
                strokeColor = gold
                strokeWidth = dp(1)
            }
            alpha = if (t.unlocked) 1f else 0.72f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = if (t.unlocked) t.medal else "🔒"
            textSize = 30f
            setPadding(0, 0, dp(14), 0)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = Trophies.title(context, t)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = Trophies.description(context, t)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        if (t.unlocked) {
            col.addView(TextView(this).apply {
                text = getString(R.string.tr_earned_on, DateFormat.getMediumDateFormat(context).format(Date(t.unlockedAt!!)))
                textSize = 12f
                setTextColor(gold)
                setPadding(0, dp(4), 0, 0)
            })
        } else if (t.family.tiers.size > 1) {
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = ((t.progress / t.target) * 1000).toInt()
                progressTintList = ColorStateList.valueOf(gold)
                progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
            col.addView(TextView(this).apply {
                text = Trophies.progressText(t)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, TrophiesActivity::class.java))
    }
}
