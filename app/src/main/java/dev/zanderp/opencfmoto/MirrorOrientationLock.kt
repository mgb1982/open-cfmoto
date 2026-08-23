// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Locks [MainActivity] to landscape/portrait while mirroring so MediaProjection matches the dash.
 * [MainActivity] already opts out of recreate on orientation (`configChanges`), so the
 * MediaProjection token survives the rotate.
 */
object MirrorOrientationLock {
    @Volatile
    private var saved: Int? = null

    fun apply(activity: Activity, canvasW: Int = 0, canvasH: Int = 0) {
        val shape = VideoPrefs.mirrorShape(activity, canvasW, canvasH)
        val want = when (shape) {
            null -> {
                clear(activity)
                return
            }
            MirrorShape.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            MirrorShape.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (saved == null) saved = activity.requestedOrientation
        if (activity.requestedOrientation != want) {
            activity.requestedOrientation = want
            LogBus.log("[MIRROR] lock ${VideoPrefs.mirrorOrientation(activity).name} → $shape")
        }
    }

    fun clear(activity: Activity) {
        val prev = saved ?: return
        saved = null
        activity.requestedOrientation = prev
        LogBus.log("[MIRROR] restore phone orientation")
    }
}
