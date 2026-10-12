// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** 🆘 Crash detector settings: on/off, rider name, up to 3 contacts, simulate, test SMS. */
class CrashSettingsActivity : AppCompatActivity() {

    private lateinit var contactsBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var toggle: MaterialSwitch

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0).orEmpty()
                    val number = c.getString(1).orEmpty()
                    if (number.isNotBlank()) {
                        val list = CrashDetector.contacts(this).filter { it.number != number } + CrashDetector.Contact(name, number)
                        CrashDetector.setContacts(this, list.takeLast(CrashDetector.MAX_CONTACTS))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            setText(R.string.crash_title)
            textSize = 24f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(context, R.font.archivo_black)
        })
        root.addView(body(getString(R.string.crash_how)))

        toggle = MaterialSwitch(this).apply {
            setText(R.string.crash_enable)
            setTextColor(color(R.color.text_primary))
            isChecked = CrashDetector.enabled(this@CrashSettingsActivity)
            setOnCheckedChangeListener { _, on ->
                CrashDetector.setEnabled(this@CrashSettingsActivity, on)
                if (on) requestNeeded()
                refresh()
            }
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        status = body("")
        root.addView(status)

        root.addView(header(getString(R.string.crash_name_title)))
        root.addView(EditText(this).apply {
            setText(CrashDetector.riderName(this@CrashSettingsActivity))
            hint = getString(R.string.crash_name_hint)
            setTextColor(color(R.color.text_primary))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { CrashDetector.setRiderName(this@CrashSettingsActivity, s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        root.addView(header(getString(R.string.crash_contacts_title, CrashDetector.MAX_CONTACTS)))
        contactsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contactsBox)
        root.addView(button(getString(R.string.crash_add_contact)) {
            if (CrashDetector.contacts(this).size >= CrashDetector.MAX_CONTACTS) {
                Toast.makeText(this, getString(R.string.crash_contacts_full, CrashDetector.MAX_CONTACTS), Toast.LENGTH_SHORT).show()
            } else {
                try {
                    pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(header(getString(R.string.crash_test_title)))
        root.addView(button(getString(R.string.crash_simulate)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.crash_simulate_confirm)
                .setPositiveButton(R.string.crash_simulate) { _, _ -> CrashDetector.startAlarm(this, test = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        if (CrashDetector.smsSupported) {
            root.addView(button(getString(R.string.crash_test_sms)) {
                if (CrashDetector.contacts(this).isEmpty()) {
                    Toast.makeText(this, R.string.crash_no_contacts, Toast.LENGTH_SHORT).show(); return@button
                }
                if (!CrashDetector.canSms(this)) { requestNeeded(); return@button }
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.crash_test_sms_confirm, CrashDetector.contacts(this).joinToString(", ") { it.name.ifBlank { it.number } }))
                    .setPositiveButton(R.string.crash_test_sms) { _, _ ->
                        val sent = CrashDetector.sendAlerts(this, followUp = false, test = true, forceSend = true)
                        Toast.makeText(this, getString(R.string.crash_sent_to, sent.joinToString(", ").ifBlank { "—" }), Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }
        root.addView(body(getString(if (CrashDetector.smsSupported) R.string.crash_disclaimer else R.string.crash_disclaimer_play)))

        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestNeeded() {
        val want = ArrayList<String>()
        if (CrashDetector.smsSupported && !granted(Manifest.permission.SEND_SMS)) want += Manifest.permission.SEND_SMS
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) want += Manifest.permission.POST_NOTIFICATIONS
        if (want.isNotEmpty()) askPerms.launch(want.toTypedArray())
        else if (!fullScreenOk()) openFullScreenSettings()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun fullScreenOk(): Boolean =
        Build.VERSION.SDK_INT < 34 || (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun refresh() {
        val list = CrashDetector.contacts(this)
        contactsBox.removeAllViews()
        if (list.isEmpty()) contactsBox.addView(body(getString(R.string.crash_no_contacts)))
        for (c in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "${c.name}\n${c.number}"
                textSize = 15f
                setTextColor(color(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "✕"
                setOnClickListener {
                    CrashDetector.setContacts(this@CrashSettingsActivity, CrashDetector.contacts(this@CrashSettingsActivity).filter { it.number != c.number })
                    refresh()
                }
            })
            contactsBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val problems = ArrayList<String>()
        if (CrashDetector.enabled(this)) {
            if (list.isEmpty()) problems += getString(R.string.crash_warn_contacts)
            if (CrashDetector.smsSupported && !granted(Manifest.permission.SEND_SMS)) problems += getString(R.string.crash_warn_sms)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) problems += getString(R.string.crash_warn_notif)
            if (!fullScreenOk()) problems += getString(R.string.crash_warn_fullscreen)
        }
        status.text = when {
            !CrashDetector.enabled(this) -> getString(R.string.crash_status_off)
            problems.isEmpty() -> getString(R.string.crash_status_ready)
            else -> problems.joinToString("\n") { "⚠️ $it" }
        }
        status.setTextColor(color(if (CrashDetector.enabled(this) && problems.isEmpty()) R.color.status_live else R.color.text_secondary))
        status.setOnClickListener { if (problems.isNotEmpty()) requestNeeded() }
    }

    private fun header(s: String) = TextView(this).apply {
        text = s; textSize = 16f
        setTextColor(color(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 13f
        setTextColor(color(R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, CrashSettingsActivity::class.java))
    }
}
