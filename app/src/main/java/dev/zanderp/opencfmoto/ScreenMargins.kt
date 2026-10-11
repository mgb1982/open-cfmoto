// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Black borders held back from the edges of the dash, in dash pixels.
 *
 * Per-edge (top/bottom/left/right) so each bike's chrome (e.g. 800NK Advanced MotoPlay pull-down)
 * can be blanked without a rebuild. Edited on the dedicated Screen margins page.
 *
 * Until the rider customises, [BikeProfile.defaultMargins] for the active profile apply.
 */
object ScreenMargins {
    private const val PREF = "screen_margins"
    const val MAX = 400

    @Volatile private var userTop = 0
    @Volatile private var userBottom = 0
    @Volatile private var userLeft = 0
    @Volatile private var userRight = 0

    @Volatile var customised: Boolean = false
        private set

    val top: Int get() = if (customised) userTop else BikeProfileHolder.active.defaultMargins[0]
    val bottom: Int get() = if (customised) userBottom else BikeProfileHolder.active.defaultMargins[1]
    val left: Int get() = if (customised) userLeft else BikeProfileHolder.active.defaultMargins[2]
    val right: Int get() = if (customised) userRight else BikeProfileHolder.active.defaultMargins[3]

    val any: Boolean get() = top != 0 || bottom != 0 || left != 0 || right != 0

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        customised = wasCustomised(
            hasFlag = p.contains("customised"),
            flag = p.getBoolean("customised", false),
            hasEdgeKeys = p.contains("top") || p.contains("bottom") ||
                p.contains("left") || p.contains("right"),
        )
        userTop = p.getInt("top", 0)
        userBottom = p.getInt("bottom", 0)
        userLeft = p.getInt("left", 0)
        userRight = p.getInt("right", 0)
    }

    internal fun wasCustomised(hasFlag: Boolean, flag: Boolean, hasEdgeKeys: Boolean): Boolean =
        if (hasFlag) flag else hasEdgeKeys

    fun set(context: Context, t: Int, b: Int, l: Int, r: Int) {
        userTop = t.coerceIn(0, MAX)
        userBottom = b.coerceIn(0, MAX)
        userLeft = l.coerceIn(0, MAX)
        userRight = r.coerceIn(0, MAX)
        customised = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", true)
            .putInt("top", userTop).putInt("bottom", userBottom)
            .putInt("left", userLeft).putInt("right", userRight)
            .apply()
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    /** Back to the active profile's defaults (not necessarily zero). */
    fun reset(context: Context) {
        customised = false
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("customised", false).apply()
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    fun summary(): String = when {
        !any -> "None — Android Auto uses the whole dash"
        !customised -> "Top $top · bottom $bottom · left $left · right $right px (profile default)"
        else -> "Top $top · bottom $bottom · left $left · right $right px"
    }
}
