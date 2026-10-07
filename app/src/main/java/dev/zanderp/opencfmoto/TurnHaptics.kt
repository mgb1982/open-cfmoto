// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject

/**
 * Turn-by-turn vibrations on the watch, from Android Auto's navigation-status channel
 * ([dev.zanderp.opencfmoto.aa.AapControlNav]).
 *
 * Each manoeuvre buzzes twice: a soft "get ready" well before it and a strong "now" just before it.
 * The pattern says the direction without looking: 2 pulses = left, 3 = right, long + N short =
 * roundabout exit N, two long = U-turn, one very long = destination. The watch draws them in
 * PhoneLink.vibrateTurn.
 */
object TurnHaptics {
    enum class Kind(val id: String) {
        NONE(""), LEFT("left"), RIGHT("right"), SLIGHT_LEFT("sleft"), SLIGHT_RIGHT("sright"),
        UTURN("uturn"), ROUNDABOUT("round"), DESTINATION("dest"),
    }

    private const val PREFS = "turn_haptics"
    private const val KEY_ON = "on"

    // "Get ready" when the turn is this close (or this many seconds away), "now" at the second pair.
    private const val PRE_METERS = 250
    private const val PRE_SECONDS = 14
    private const val NOW_METERS = 45
    private const val NOW_SECONDS = 4

    /** Read by ServiceDiscoveryResponse: the nav channel is only advertised when this is on. */
    @Volatile var enabled = false
        private set

    @Volatile private var appCtx: Context? = null
    private var kind = Kind.NONE
    private var exit = 0
    private var road = ""
    private var firedPre = false
    private var firedNow = false
    private var firstDistance = -1
    @Volatile var lastDistance = -1
        private set

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
        if (enabled) LogBus.log("[NAV] watch turn vibrations ON: advertising the navigation-status service")
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        load(ctx)
    }

    /** Current manoeuvre for the watch's trip page (null when not navigating). */
    @Synchronized
    fun current(): JSONObject? {
        if (kind == Kind.NONE) return null
        return JSONObject().put("k", kind.id).put("n", exit).put("d", lastDistance).put("r", road)
    }

    @Synchronized
    fun onNavActive(active: Boolean) {
        LogBus.log("[NAV] guidance ${if (active) "active" else "stopped"}")
        if (!active) reset()
    }

    @Synchronized
    fun onManeuver(k: Kind, exitNumber: Int, roadName: String) {
        if (k == kind && exitNumber == exit && roadName == road) return
        LogBus.log("[NAV] next: ${k.name}${if (exitNumber > 0) " exit $exitNumber" else ""} ${roadName.take(40)}")
        kind = k
        exit = exitNumber
        road = roadName
        firedPre = false
        firedNow = false
        firstDistance = -1
    }

    @Synchronized
    fun onDistance(meters: Int, seconds: Int) {
        if (meters < 0) return
        // A rising distance means AA moved on to the next manoeuvre before telling us (or rerouted).
        if (lastDistance in 0 until meters - 30) {
            firedPre = false
            firedNow = false
            firstDistance = -1
        }
        lastDistance = meters
        if (firstDistance < 0) firstDistance = meters
        if (kind == Kind.NONE) return
        val nowDue = meters <= NOW_METERS || (seconds in 0..NOW_SECONDS)
        val preDue = meters <= PRE_METERS || (seconds in 0..PRE_SECONDS)
        when {
            nowDue && !firedNow -> {
                firedNow = true
                firedPre = true
                send("now", meters)
            }
            // Skip "get ready" when the manoeuvre appeared already close (back-to-back turns):
            // one clear "now" beats two buzzes in a row.
            preDue && !firedPre && firstDistance > NOW_METERS * 2 -> {
                firedPre = true
                send("pre", meters)
            }
        }
    }

    /** AA session ended (or nav channel reopened): forget any manoeuvre left over from before. */
    @Synchronized
    fun clear() = reset()

    private fun reset() {
        kind = Kind.NONE
        exit = 0
        road = ""
        firedPre = false
        firedNow = false
        firstDistance = -1
        lastDistance = -1
    }

    private fun send(phase: String, meters: Int) {
        val ctx = appCtx ?: return
        if (!enabled) return
        val o = JSONObject().put("k", kind.id).put("n", exit).put("d", meters).put("p", phase)
        LogBus.log("[NAV] buzz $phase ${kind.name} at ${meters} m")
        WearBridge.sendToWatches(ctx, WearBridge.PATH_TURN, o.toString())
    }
}
