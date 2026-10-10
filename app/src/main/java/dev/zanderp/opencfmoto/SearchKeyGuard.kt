// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.Window

/**
 * A SEARCH key (handlebar remote, Bluetooth button, some keyboards) reaching one of our screens
 * crashed the app on Android 12: the system's default handling (PhoneWindow.launchDefaultSearch)
 * sends CLOSE_SYSTEM_DIALOGS, which apps may no longer do → SecurityException (crash report
 * ecb42c9c, API 31). We take the key first: with Android Auto on the dash it opens the Assistant
 * there (what a head unit's voice button does), otherwise it's ignored.
 */
object SearchKeyGuard {
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = wrap(activity)
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun wrap(activity: Activity) {
        val window = activity.window ?: return
        val original = window.callback ?: return
        if (original is Guarded) return
        window.callback = Guarded(original)
    }

    private class Guarded(private val inner: Window.Callback) : Window.Callback by inner {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    val sink = AaVideoBridge.keySink
                    if (sink != null) {
                        LogBus.log("[KEY] SEARCH → Android Auto Assistant")
                        try { sink(KeyEvent.KEYCODE_SEARCH) } catch (_: Exception) {}
                    } else {
                        LogBus.log("[KEY] SEARCH ignored (no Android Auto session)")
                    }
                }
                return true
            }
            return inner.dispatchKeyEvent(event)
        }
    }
}
