// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** GitHub build: donations through a Ko-fi link (the Play build uses Google Play Billing instead). */
object Support {
    const val KOFI_URL = "https://ko-fi.com/mgb1982"

    fun onResume(activity: Activity) = Unit

    fun open(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.support_title)
            .setMessage(R.string.support_body)
            .setPositiveButton(R.string.support_kofi) { _, _ -> openUrl(activity, KOFI_URL) }
            .setNeutralButton(R.string.support_original) { _, _ -> openUrl(activity, AboutActivity.URL_KOFI) }
            .setNegativeButton(R.string.support_later, null)
            .show()
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.main_donate_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
