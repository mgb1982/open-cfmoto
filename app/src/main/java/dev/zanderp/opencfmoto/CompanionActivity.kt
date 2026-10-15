// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * v2.3 · 👥 Passenger mode screen, both roles: the rider pairs passengers and sees who's connected;
 * the passenger finds the bike and, once connected, gets the remote (destination, places, D-pad,
 * music). See [PaxLink].
 */
class CompanionActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var afterPermission: (() -> Unit)? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) afterPermission?.invoke()
        else MaterialAlertDialogBuilder(this).setMessage(R.string.pax_perms_needed).setPositiveButton(android.R.string.ok, null).show()
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        PaxLink.onChange = { if (!isFinishing) render() }
        PaxLink.onConfirmCode = { who, code, accept, reject ->
            if (!isFinishing) MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pax_code_title, who))
                .setMessage(getString(R.string.pax_code_msg, code))
                .setCancelable(false)
                .setPositiveButton(R.string.pax_code_yes) { _, _ -> accept() }
                .setNegativeButton(R.string.pax_code_no) { _, _ -> reject() }
                .show() else reject()
        }
        render()
    }

    override fun onPause() {
        PaxLink.onChange = null
        PaxLink.onConfirmCode = null
        super.onPause()
    }

    override fun onDestroy() {
        // Leaving the screen ends a search / pairing (a live passenger connection or the rider's
        // background visibility while riding stay).
        if (PaxLink.mode == PaxLink.Mode.PASSENGER_SEARCHING || PaxLink.mode == PaxLink.Mode.RIDER_PAIRING) PaxLink.stopAll()
        super.onDestroy()
    }

    private fun withPermissions(then: () -> Unit) {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) then() else { afterPermission = then; askPerms.launch(need.toTypedArray()) }
    }

    private fun render() {
        root.removeAllViews()
        root.addView(title(getString(R.string.pax_title)))
        when (PaxLink.mode) {
            PaxLink.Mode.PASSENGER_CONNECTED -> renderRemote()
            PaxLink.Mode.PASSENGER_SEARCHING -> renderSearching()
            else -> renderHome()
        }
    }

    private fun renderHome() {
        root.addView(body(getString(R.string.pax_intro)))

        // ── I'm the passenger
        root.addView(header(getString(R.string.pax_as_passenger)))
        root.addView(body(getString(R.string.pax_as_passenger_d)))
        root.addView(button(getString(R.string.pax_search)) { withPermissions { PaxLink.startSearch(this) } })
        val riders = PaxLink.riders(this)
        if (riders.isNotEmpty()) root.addView(body(getString(R.string.pax_known_riders, riders.joinToString(", ") { it.name })))

        // ── I'm the rider
        root.addView(header(getString(R.string.pax_as_rider)))
        val pax = PaxLink.passengers(this)
        val state = when {
            PaxLink.mode == PaxLink.Mode.RIDER_PAIRING -> getString(R.string.pax_pairing_now, PaxLink.myName(this))
            PaxLink.mode == PaxLink.Mode.RIDER_VISIBLE && PaxLink.peerName != null -> getString(R.string.pax_connected_rider, PaxLink.peerName)
            PaxLink.mode == PaxLink.Mode.RIDER_VISIBLE -> getString(R.string.pax_visible)
            pax.isEmpty() -> getString(R.string.pax_none_paired)
            else -> getString(R.string.pax_visible_when_riding)
        }
        root.addView(body(state))
        if (PaxLink.mode == PaxLink.Mode.RIDER_PAIRING) {
            root.addView(button(getString(R.string.pax_stop_pairing)) { PaxLink.stopAll() })
        } else {
            root.addView(button(getString(R.string.pax_pair_new)) { withPermissions { PaxLink.startRider(this, pairing = true) } })
        }
        for (p in pax) {
            root.addView(button(getString(R.string.pax_forget, p.name)) {
                MaterialAlertDialogBuilder(this).setMessage(getString(R.string.pax_forget_confirm, p.name))
                    .setPositiveButton(R.string.pax_forget_yes) { _, _ -> PaxLink.forgetPassenger(this, p.id); render() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            })
        }
        root.addView(body(getString(R.string.pax_privacy)))
    }

    private fun renderSearching() {
        root.addView(body(getString(R.string.pax_searching)))
        val known = PaxLink.riders(this).map { it.id }.toSet()
        for (f in PaxLink.found.values.toList()) {
            val label = if (f.id in known && !f.pairing) getString(R.string.pax_connect_to, f.name) else getString(R.string.pax_pair_with, f.name)
            root.addView(button(label) { PaxLink.connect(this, f) })
        }
        root.addView(button(getString(R.string.pax_cancel)) { PaxLink.stopAll() })
    }

    private fun renderRemote() {
        root.addView(body(getString(R.string.pax_connected_to, PaxLink.peerName ?: "…")))

        // Destination
        root.addView(header(getString(R.string.pax_dest_title)))
        val input = EditText(this).apply {
            hint = getString(R.string.dest_address_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(color(R.color.text_primary))
        }
        fun go() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            if (PaxLink.sendDestination(q)) { input.setText(""); toast(getString(R.string.pax_sent)) }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { go(); true } else false }
        root.addView(input)
        root.addView(button(getString(R.string.pax_send_dest)) { go() })
        val places = PaxLink.riderPlaces
        if (places.isNotEmpty()) root.addView(row(places.map { (slot, name) ->
            small("📍 $name") { PaxLink.sendPlace(slot); toast(getString(R.string.pax_sent)) }
        }))

        // Android Auto remote
        root.addView(header(getString(R.string.pax_aa_title)))
        root.addView(row(listOf(spacer(), small("▲") { PaxLink.sendKey(AaInput.KEY_UP) }, spacer())))
        root.addView(row(listOf(
            small("◀") { PaxLink.sendKey(AaInput.KEY_LEFT) },
            small("OK") { PaxLink.sendKey(AaInput.KEY_ENTER) },
            small("▶") { PaxLink.sendKey(AaInput.KEY_RIGHT) },
        )))
        root.addView(row(listOf(spacer(), small("▼") { PaxLink.sendKey(AaInput.KEY_DOWN) }, spacer())))
        root.addView(row(listOf(
            small("⟲") { PaxLink.sendScroll(-1) },
            small("⟳") { PaxLink.sendScroll(+1) },
        )))
        root.addView(row(listOf(
            small(getString(R.string.pax_back)) { PaxLink.sendKey(AaInput.KEY_BACK) },
            small(getString(R.string.pax_home)) { PaxLink.sendKey(AaInput.KEY_HOME) },
        )))

        // Music
        root.addView(header(getString(R.string.pax_music_title)))
        root.addView(row(listOf(
            small("⏮") { PaxLink.sendKey(AaInput.KEY_PREVIOUS) },
            small("⏯") { PaxLink.sendKey(AaInput.KEY_PLAY_PAUSE) },
            small("⏭") { PaxLink.sendKey(AaInput.KEY_NEXT) },
        )))
        root.addView(row(listOf(
            small("🔉 −") { PaxLink.sendVolume(-1) },
            small("🔊 +") { PaxLink.sendVolume(+1) },
        )))

        root.addView(button(getString(R.string.pax_disconnect)) { PaxLink.stopAll() })
    }

    // ── tiny view helpers (same look as the other programmatic screens)

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 24f; setTextColor(color(R.color.text_primary)); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private fun header(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(color(R.color.text_primary)); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(4)) }
    private fun body(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(color(R.color.text_secondary)); setPadding(0, dp(6), 0, 0) }
    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }
    private fun small(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        textSize = 18f; minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
    }
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    private fun row(views: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        views.forEach { v ->
            if (v.layoutParams == null) v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(v)
        }
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, CompanionActivity::class.java))
    }
}
