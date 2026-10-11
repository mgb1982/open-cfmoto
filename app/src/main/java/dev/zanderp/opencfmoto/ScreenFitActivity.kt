// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 🎯 "Fit to dash" (v2.1): one screen instead of margins + fit + panel-size pages.
 *
 * While connected, the bike shows a yellow frame around the area Android Auto is drawn into. The
 * rider moves each edge with arrows until the frame sits exactly on the visible edge of the dash;
 * every tap is applied live (ScreenMargins + compositor). The fit mode is live too. The panel shape
 * Android Auto lays itself out for (match-aspect W×H, the rider's manual size included) is shown
 * and editable here, never hidden; it needs an Android Auto restart, offered as "Apply now".
 */
class ScreenFitActivity : AppCompatActivity() {

    private lateinit var preview: FramePreview
    private lateinit var status: TextView
    private lateinit var marginsText: TextView
    private lateinit var shapeText: TextView
    private lateinit var shapeW: EditText
    private lateinit var shapeH: EditText
    private val fitButtons = HashMap<ScreenFit, MaterialButton>()

    private var t = 0; private var b = 0; private var l = 0; private var r = 0
    private var startMargins = intArrayOf(0, 0, 0, 0)
    private var startCustomised = false
    private lateinit var startFit: ScreenFit
    private var step = 2

    private val live: Boolean
        get() = (ConnectionState.phase == Phase.STREAMING) && AaVideoBridge.pipeline?.compositing == true

    private fun canvas(): Pair<Int, Int> =
        AaVideoBridge.pipeline?.canvasSize() ?: VideoPrefs.detectedPanelSize(this) ?: (800 to 480)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenMargins.load(this)
        t = ScreenMargins.top; b = ScreenMargins.bottom; l = ScreenMargins.left; r = ScreenMargins.right
        startMargins = intArrayOf(t, b, l, r)
        startCustomised = ScreenMargins.customised
        startFit = VideoPrefs.fit(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.fit_title)
            textSize = 24f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        status = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)
        root.addView(body(getString(R.string.fit_how)))

        // ── D-pad around the preview: each edge has its own pair of arrows.
        val pad = card()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        grid.addView(arrowPair(vertical = false, "▲", "▼", { t -= step }, { t += step }))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(arrowPair(vertical = true, "◀", "▶", { l -= step }, { l += step }))
        preview = FramePreview(this)
        mid.addView(preview, LinearLayout.LayoutParams(0, dp(150), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        mid.addView(arrowPair(vertical = true, "◀", "▶", { r += step }, { r -= step }))
        grid.addView(mid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })
        grid.addView(arrowPair(vertical = false, "▲", "▼", { b += step }, { b -= step }))
        marginsText = body("").apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        grid.addView(marginsText)
        val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val s1 = small(getString(R.string.fit_step_fine)) {}
        val s2 = small(getString(R.string.fit_step_coarse)) {}
        fun markStep() { s1.alpha = if (step == 2) 1f else 0.5f; s2.alpha = if (step == 10) 1f else 0.5f }
        s1.setOnClickListener { step = 2; markStep() }
        s2.setOnClickListener { step = 10; markStep() }
        markStep()
        steps.addView(s1); steps.addView(s2)
        grid.addView(steps)
        pad.addView(grid)
        root.addView(pad)

        // ── Fit (live)
        root.addView(header(getString(R.string.fit_mode_title)))
        val fits = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((fit, label) in listOf(
            ScreenFit.FILL to R.string.fit_mode_fill,
            ScreenFit.FIT to R.string.fit_mode_fit,
            ScreenFit.STRETCH to R.string.fit_mode_stretch,
        )) {
            val btn = small(getString(label)) { setFit(fit) }
            fitButtons[fit] = btn
            fits.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        root.addView(fits)
        root.addView(body(getString(R.string.fit_mode_hint)))

        // ── Panel shape (match aspect) — visible and editable, applied on reconnect
        root.addView(header(getString(R.string.fit_shape_title)))
        shapeText = body("")
        root.addView(shapeText)
        val (aw, ah) = VideoPrefs.aspectTarget(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shapeW = number(aw); shapeH = number(ah)
        row.addView(shapeW, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = " × "; textSize = 18f; setTextColor(color(R.color.text_primary)) })
        row.addView(shapeH, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        val shapeBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shapeBtns.addView(small(getString(R.string.fit_shape_from_frame)) {
            val (cw, ch) = canvas()
            shapeW.setText((cw - l - r).coerceAtLeast(16).toString())
            shapeH.setText((ch - t - b).coerceAtLeast(16).toString())
            saveShape(MatchAspectMode.MANUAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        shapeBtns.addView(small(getString(R.string.fit_shape_save)) { saveShape(MatchAspectMode.MANUAL) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        shapeBtns.addView(small(getString(R.string.fit_shape_auto)) { saveShape(MatchAspectMode.AUTO) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(shapeBtns)
        root.addView(body(getString(R.string.fit_shape_hint)))

        // ── Bottom actions
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
        actions.addView(small(getString(R.string.fit_undo)) { undo() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        actions.addView(small(getString(R.string.fit_reset)) {
            ScreenMargins.reset(this)
            t = ScreenMargins.top; b = ScreenMargins.bottom; l = ScreenMargins.left; r = ScreenMargins.right
            refresh()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        actions.addView(small(getString(R.string.fit_done)) { finish() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        root.addView(TextView(this).apply {
            setText(R.string.fit_more_options)
            textSize = 13f
            setTextColor(color(R.color.brand_gold))
            setPadding(0, dp(18), 0, 0)
            setOnClickListener { finish() }
        })

        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        AaVideoBridge.pipeline?.setCalibration(true)
        refresh()
    }

    override fun onPause() {
        AaVideoBridge.pipeline?.setCalibration(false)
        super.onPause()
    }

    // ── Actions

    private fun applyMargins() {
        val (cw, ch) = canvas()
        // Keep at least a 64 px usable area per axis, whatever the arrows say.
        t = t.coerceIn(0, ScreenMargins.MAX); b = b.coerceIn(0, ScreenMargins.MAX)
        l = l.coerceIn(0, ScreenMargins.MAX); r = r.coerceIn(0, ScreenMargins.MAX)
        if (ch - t - b < 64) { if (t > b) t = ch - 64 - b else b = ch - 64 - t }
        if (cw - l - r < 64) { if (l > r) l = cw - 64 - r else r = cw - 64 - l }
        ScreenMargins.set(this, t, b, l, r) // persists + live refresh on the dash
        refresh()
    }

    private fun setFit(fit: ScreenFit) {
        VideoPrefs.setFit(this, fit)
        AaVideoBridge.pipeline?.setLiveFit(fit)
        refresh()
    }

    private fun saveShape(mode: MatchAspectMode) {
        val w = shapeW.text.toString().toIntOrNull()
        val h = shapeH.text.toString().toIntOrNull()
        if (mode == MatchAspectMode.MANUAL && (w == null || h == null || w < 16 || h < 16)) {
            Toast.makeText(this, R.string.fit_shape_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val (cw, ch) = VideoPrefs.aspectTarget(this)
        VideoPrefs.setMatchAspect(this, mode, w ?: cw, h ?: ch)
        LogBus.log("[FIT] panel shape → $mode ${w}x$h")
        refresh()
        if (live) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.fit_shape_reconnect)
                .setPositiveButton(R.string.fit_shape_reconnect_now) { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_RECONNECT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                .setNegativeButton(R.string.fit_shape_reconnect_later, null)
                .show()
        } else {
            Toast.makeText(this, R.string.fit_shape_saved_next, Toast.LENGTH_SHORT).show()
        }
    }

    private fun undo() {
        val (st, sb, sl, sr) = startMargins.toList()
        if (startCustomised) ScreenMargins.set(this, st, sb, sl, sr) else ScreenMargins.reset(this)
        t = ScreenMargins.top; b = ScreenMargins.bottom; l = ScreenMargins.left; r = ScreenMargins.right
        setFit(startFit)
    }

    private fun refresh() {
        val ok = live
        status.setText(if (ok) R.string.fit_status_live else R.string.fit_status_offline)
        status.setTextColor(color(if (ok) R.color.status_live else R.color.text_secondary))
        val (cw, ch) = canvas()
        preview.set(cw, ch, t, b, l, r)
        marginsText.text = getString(R.string.fit_margins_now, t, b, l, r, cw - l - r, ch - t - b)
        val fit = VideoPrefs.fit(this)
        fitButtons.forEach { (f, btn) -> btn.alpha = if (f == fit) 1f else 0.5f }
        val mode = VideoPrefs.matchAspectMode(this)
        val (aw, ah) = VideoPrefs.aspectTarget(this)
        val detected = VideoPrefs.detectedPanelSize(this)
        shapeText.text = when (mode) {
            MatchAspectMode.MANUAL -> getString(R.string.fit_shape_manual, aw, ah)
            MatchAspectMode.OFF -> getString(R.string.fit_shape_off)
            MatchAspectMode.AUTO -> if (detected != null) getString(R.string.fit_shape_auto_is, detected.first, detected.second)
                else getString(R.string.fit_shape_auto_unknown)
        }
    }

    // ── Small UI helpers

    @SuppressLint("ClickableViewAccessibility")
    private fun arrowPair(vertical: Boolean, a: String, z: String, onA: () -> Unit, onZ: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((label, action) in listOf(a to onA, z to onZ)) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 18f
                minWidth = dp(52); minimumWidth = dp(52)
                insetTop = 0; insetBottom = 0
            }
            repeatOnHold(btn) { action(); applyMargins() }
            box.addView(btn, LinearLayout.LayoutParams(dp(52), dp(48)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        return box
    }

    /** Tap = one step; hold = keeps stepping (like a TV remote). */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(v: View, action: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val rep = object : Runnable {
            override fun run() { action(); h.postDelayed(this, 90) }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; action(); h.postDelayed(rep, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; h.removeCallbacks(rep) }
            }
            true
        }
    }

    private fun card() = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        radius = dp(18).toFloat()
        setCardBackgroundColor(color(R.color.surface))
        setContentPadding(dp(10), dp(12), dp(10), dp(12))
    }

    private fun header(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(color(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(4), 0, 0)
    }

    private fun small(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { onClick() }
        }

    private fun number(v: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(v.toString())
        gravity = Gravity.CENTER
        setTextColor(color(R.color.text_primary))
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Phone-side sketch of the dash: grey canvas, yellow frame = where Android Auto is drawn. */
    private class FramePreview(ctx: Context) : View(ctx) {
        private var cw = 800; private var ch = 480
        private var t = 0; private var b = 0; private var l = 0; private var r = 0
        private val bg = Paint().apply { color = Color.rgb(40, 40, 44) }
        private val area = Paint().apply { color = Color.rgb(20, 60, 90) }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 209, 26); style = Paint.Style.STROKE; strokeWidth = 5f
        }

        fun set(cw: Int, ch: Int, t: Int, b: Int, l: Int, r: Int) {
            this.cw = cw; this.ch = ch; this.t = t; this.b = b; this.l = l; this.r = r
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (cw <= 0 || ch <= 0) return
            val s = minOf(width.toFloat() / cw, height.toFloat() / ch)
            val ox = (width - cw * s) / 2
            val oy = (height - ch * s) / 2
            c.drawRect(ox, oy, ox + cw * s, oy + ch * s, bg)
            val rect = RectF(ox + l * s, oy + t * s, ox + (cw - r) * s, oy + (ch - b) * s)
            c.drawRect(rect, area)
            c.drawRect(rect, frame)
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ScreenFitActivity::class.java))
    }
}
