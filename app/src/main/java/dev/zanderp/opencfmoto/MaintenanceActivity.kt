// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Maintenance logbook for the selected Garage bike (see [Maintenance]). Built in code: it's a list. */
class MaintenanceActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        scroll.addView(body)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val book = Maintenance.load(this)
        val bike = BikeMemory.selected(this)

        body.addView(text(getString(R.string.maint_title), 22f, R.color.text_primary, bold = true))
        bike?.let { body.addView(text(it.name, 13f, R.color.brand_orange)) }

        // Odometer.
        card { c ->
            c.addView(label(getString(R.string.maint_odometer)))
            val odo = book.odometer
            c.addView(text(odo?.let { Maintenance.fmtKm(it) } ?: getString(R.string.maint_odometer_unset), if (odo != null) 30f else 15f,
                R.color.text_primary, bold = odo != null, font = if (odo != null) R.font.archivo_black else null))
            c.addView(text(getString(R.string.maint_odometer_hint), 12f, R.color.text_secondary))
            c.addView(button(getString(R.string.maint_fix_km)) { askOdometer(book.odometer) })
        }

        // Tasks.
        body.addView(section(getString(R.string.maint_tasks)))
        for (t in book.tasks.filter { it.enabled }.sortedByDescending { Maintenance.status(book, it).fraction }) {
            val s = Maintenance.status(book, t)
            card { c ->
                c.addView(text(t.name, 16f, R.color.text_primary, bold = true))
                val col = when (s.level) {
                    Maintenance.Level.DUE -> R.color.status_error
                    Maintenance.Level.SOON -> R.color.status_busy
                    Maintenance.Level.OK -> R.color.status_live
                    Maintenance.Level.UNKNOWN -> R.color.text_secondary
                }
                c.addView(text(Maintenance.statusText(this, s), 13f, col))
                if (s.level != Maintenance.Level.UNKNOWN) {
                    c.addView(LinearProgressIndicator(this).apply {
                        max = 100
                        progress = (s.fraction * 100).toInt()
                        setIndicatorColor(color(col))
                        trackColor = color(R.color.surface_high)
                        layoutParams = lp().apply { topMargin = dp(6) }
                    })
                }
                c.addView(text(intervalText(t), 12f, R.color.text_secondary))
                c.addView(row(
                    button(getString(R.string.maint_done), filled = true) { askDone(t, book.odometer) },
                    button(getString(R.string.maint_edit)) { editTask(t, book) },
                ))
            }
        }
        body.addView(button(getString(R.string.maint_add_task)) { editTask(null, book) })

        // Fuel.
        body.addView(section(getString(R.string.maint_fuel)))
        card { c ->
            val cons = Maintenance.consumption(book)
            c.addView(text(
                if (cons == null) getString(R.string.maint_fuel_need_two)
                else getString(R.string.maint_fuel_stats, String.format(Locale.getDefault(), "%.2f", cons.first),
                    cons.second?.let { String.format(Locale.getDefault(), "%.3f €/km", it) } ?: "—", cons.third),
                14f, R.color.text_primary,
            ))
            c.addView(text(getString(R.string.maint_fuel_hint), 12f, R.color.text_secondary))
            c.addView(button(getString(R.string.maint_refuel), filled = true) { askRefuel() })
        }

        // History.
        val log = book.log.sortedByDescending { it.at }.take(20)
        if (log.isNotEmpty()) {
            body.addView(section(getString(R.string.maint_history)))
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (e in log) {
                val what = when (e.kind) {
                    "fuel" -> "⛽ " + String.format(Locale.getDefault(), "%.2f L", e.liters ?: 0.0) +
                        (e.euros?.let { String.format(Locale.getDefault(), " · %.2f €", it) } ?: "")
                    else -> "🔧 " + (book.tasks.firstOrNull { it.id == e.taskId }?.name ?: e.taskId.orEmpty()) +
                        " · " + Maintenance.fmtKm(e.km)
                }
                body.addView(text("${df.format(Date(e.at))} — $what", 13f, R.color.text_secondary))
            }
        }
    }

    private fun intervalText(t: Maintenance.Task): String {
        val parts = ArrayList<String>()
        t.everyKm?.let { parts += getString(R.string.maint_every_km, Maintenance.fmtKm(it.toDouble())) }
        t.everyMonths?.let { parts += getString(R.string.maint_every_months, it) }
        val last = ArrayList<String>()
        t.lastKm?.let { last += Maintenance.fmtKm(it) }
        t.lastAt?.let { last += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
        return parts.joinToString(" · ") + if (last.isNotEmpty()) "\n" + getString(R.string.maint_last, last.joinToString(" · ")) else ""
    }

    // ---------------------------------------------------------------- dialogs

    private fun askOdometer(current: Double?) {
        val input = number(current?.let { String.format(Locale.ROOT, "%.0f", it) }, getString(R.string.maint_odometer_input))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.maint_fix_km)
            .setMessage(R.string.maint_odometer_dialog)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().replace(',', '.').toDoubleOrNull()?.let { Maintenance.setOdometer(this, it); render() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askDone(t: Maintenance.Task, odo: Double?) {
        val input = number(odo?.let { String.format(Locale.ROOT, "%.0f", it) }, getString(R.string.maint_odometer_input))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.maint_done_title, t.name))
            .setMessage(R.string.maint_done_dialog)
            .setView(padded(input))
            .setPositiveButton(R.string.maint_done) { _, _ ->
                Maintenance.markDone(this, t.id, input.text.toString().replace(',', '.').toDoubleOrNull())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askRefuel() {
        val liters = number(null, getString(R.string.maint_liters), decimal = true)
        val euros = number(null, getString(R.string.maint_euros), decimal = true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(liters); addView(euros) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.maint_refuel)
            .setMessage(R.string.maint_refuel_dialog)
            .setView(padded(box))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val l = liters.text.toString().replace(',', '.').toDoubleOrNull()
                if (l == null || l <= 0) {
                    Toast.makeText(this, R.string.maint_liters_needed, Toast.LENGTH_SHORT).show()
                } else {
                    Maintenance.addRefuel(this, l, euros.text.toString().replace(',', '.').toDoubleOrNull())
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Edit (or, with [t] null, create) a task: name, intervals, and when it was last done. */
    private fun editTask(t: Maintenance.Task?, book: Maintenance.Book) {
        val name = EditText(this).apply { setText(t?.name.orEmpty()); hint = getString(R.string.maint_task_name) }
        val km = number(t?.everyKm?.toString(), getString(R.string.maint_interval_km))
        val months = number(t?.everyMonths?.toString(), getString(R.string.maint_interval_months))
        val lastKm = number(t?.lastKm?.let { String.format(Locale.ROOT, "%.0f", it) }, getString(R.string.maint_last_km))
        var lastAt: Long? = t?.lastAt
        val lastDate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun showDate() {
            lastDate.text = getString(R.string.maint_last_date) + ": " +
                (lastAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—")
        }
        showDate()
        lastDate.setOnClickListener {
            val c = Calendar.getInstance().apply { timeInMillis = lastAt ?: System.currentTimeMillis() }
            DatePickerDialog(this, { _, y, m, d ->
                lastAt = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
                showDate()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(name, km, months, lastKm, lastDate).forEach { addView(it) }
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(if (t == null) R.string.maint_add_task else R.string.maint_edit)
            .setMessage(R.string.maint_interval_hint)
            .setView(padded(box))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = name.text.toString().trim()
                val k = km.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val mo = months.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val lk = lastKm.text.toString().replace(',', '.').toDoubleOrNull()
                if (n.isBlank() || (k == null && mo == null)) {
                    Toast.makeText(this, R.string.maint_task_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (t == null) Maintenance.addTask(this, n, k, mo)
                else Maintenance.updateTask(this, t.copy(name = n, everyKm = k, everyMonths = mo, lastKm = lk, lastAt = lastAt))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (t != null) dlg.setNeutralButton(R.string.maint_delete) { _, _ ->
            Maintenance.deleteTask(this, t.id)
            render()
        }
        dlg.show()
    }

    // ---------------------------------------------------------------- view helpers

    private fun card(fill: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_stat_tile)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp().apply { topMargin = dp(10) }
        }
        fill(c)
        body.addView(c)
    }

    private fun section(s: String) = label(s).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(22) }

    private fun label(s: String) = text(s.uppercase(Locale.getDefault()), 12f, R.color.text_secondary).apply {
        letterSpacing = 0.08f
    }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false, font: Int? = null) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorRes))
        font?.let { typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MaintenanceActivity, it) }
        if (bold && font == null) setTypeface(typeface, Typeface.BOLD)
        layoutParams = lp().apply { topMargin = dp(2) }
    }

    private fun button(s: String, filled: Boolean = false, onClick: () -> Unit) =
        MaterialButton(
            this, null,
            if (filled) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = s
            setOnClickListener { onClick() }
            layoutParams = lp().apply { topMargin = dp(8) }
        }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
                topMargin = dp(8)
            }
            addView(v)
        }
    }

    private fun number(value: String?, hintText: String, decimal: Boolean = false) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        hint = hintText
        value?.let { setText(it) }
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(22), dp(4), dp(22), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, MaintenanceActivity::class.java))
    }
}
