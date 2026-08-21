// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/**
 * Manual bike-profile pick from Setup. [AUTO] lets CLIENT_INFO / QR scoring choose; anything else
 * pins that profile for the selected garage bike (via [BikeScope]).
 */
enum class ProfileOverride(val id: String, val shortLabel: String, val detail: String, @StringRes val detailRes: Int) {
    AUTO("auto", "Auto", "Detect from the bike (recommended)", R.string.pref_profile_auto),
    LEGACY("legacy", "Legacy", "CFDL16 / 450SR-style non-touch", R.string.pref_profile_legacy),
    NK800("nk800", "800NK", "CRCP / sdk 0.9.23.x non-touch", R.string.pref_profile_nk800),
    NK_ADV("nk_adv", "800NK Adv", "CFDL26 touch 720×712", R.string.pref_profile_nk_adv),
    CFDL26_LAND("cfdl26_land", "800MT", "CFDL26 landscape touch", R.string.pref_profile_cfdl26_land),
    CFDL26_PORT("cfdl26_port", "1000 MT-X", "CFDL26 portrait (handlebar-primary)", R.string.pref_profile_cfdl26_port),
    CLC450("clc450", "CL‑C450", "544×512 near-square", R.string.pref_profile_clc450),
    XCAPE_1200("xcape1200", "X-Cape 1200", "X-Cape 1200 JPEG stills", R.string.pref_profile_xcape1200),
    ;

    fun resolve(): BikeProfile? = when (this) {
        AUTO -> null
        LEGACY -> LegacyCfdl16Profile
        NK800 -> Nk800Profile
        NK_ADV -> Cfdl26NkTouchProfile
        CFDL26_LAND -> Cfdl26LandscapeProfile
        CFDL26_PORT -> Cfdl26PortraitProfile
        CLC450 -> ClC450Profile
        XCAPE_1200 -> Xcape1200StillsProfile
    }

    companion object {
        fun byId(id: String?): ProfileOverride =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object ProfilePrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY = "profile_override"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): ProfileOverride =
        ProfileOverride.byId(BikeScope.getString(prefs(ctx), ctx, KEY, null))

    fun set(ctx: Context, override: ProfileOverride) {
        if (override == ProfileOverride.AUTO) {
            BikeScope.remove(prefs(ctx), ctx, KEY)
        } else {
            BikeScope.putString(prefs(ctx), ctx, KEY, override.id)
        }
        BikeProfileHolder.profileOverride = override
    }

    fun applyToHolder(ctx: Context) {
        BikeProfileHolder.profileOverride = get(ctx)
    }
}
